package group.gnometrading.orchestrator.gateway.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.schemas.Side;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnershipLogTest {

    private static final long SIZE = 64 * 1024;

    @TempDir
    Path directory;

    private Path path() {
        return this.directory.resolve("account-5.log");
    }

    private static OrderContext order(final long slot, final long counter) {
        final OrderContext ctx = new OrderContext();
        ctx.sessionSlot = slot;
        ctx.clientOidCounter = counter;
        ctx.clientOidStrategyId = 3;
        ctx.exchangeId = 1;
        ctx.securityId = 7;
        ctx.side = Side.Bid;
        ctx.flags = 1;
        ctx.originalQty = 5_000_000;
        ctx.limitPrice = 400_000_000;
        ctx.submittedAtMillis = 1_700_000_000_000L + counter;
        ctx.awaitsFinalFill = counter % 2 == 0;
        final byte[] id = ("s" + slot + "-" + counter).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(id, 0, ctx.correlationIdBytes, 0, id.length);
        ctx.correlationIdLength = id.length;
        return ctx;
    }

    private static void venueId(final OrderContext ctx, final String id) {
        final byte[] bytes = id.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, ctx.exchangeOrderIdBytes, 0, bytes.length);
        ctx.exchangeOrderIdLength = bytes.length;
    }

    private static String correlation(final OrderContext ctx) {
        return new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII);
    }

    @Test
    void theOrdersStillOpenComeBackWithTheirTermsAndVenueIds() throws Exception {
        try (OwnershipLog log = OwnershipLog.open(path(), SIZE, ctx -> {})) {
            final OrderContext first = order(42, 1);
            final OrderContext second = order(42, 2);
            final OrderContext other = order(43, 1);
            log.added(first);
            log.added(second);
            log.added(other);
            venueId(second, "v-2");
            log.venueIdLearned(second);
            log.ended(first);
        }
        final List<OrderContext> open = OwnershipLog.read(path());
        assertEquals(
                List.of("s42-2", "s43-1"),
                open.stream().map(OwnershipLogTest::correlation).toList());
        final OrderContext second = open.get(0);
        assertEquals(42, second.sessionSlot);
        assertEquals(2, second.clientOidCounter);
        assertEquals(3, second.clientOidStrategyId);
        assertEquals(7, second.securityId);
        assertEquals(Side.Bid, second.side);
        assertEquals(1, second.flags);
        assertEquals(5_000_000, second.originalQty);
        assertEquals(400_000_000, second.limitPrice);
        assertEquals(1_700_000_000_002L, second.submittedAtMillis);
        assertTrue(second.awaitsFinalFill);
        assertEquals("v-2", new String(second.exchangeOrderIdBytes, 0, second.exchangeOrderIdLength));
        assertEquals(0, open.get(1).exchangeOrderIdLength);
    }

    @Test
    void anAmendedOrderComesBackAtItsNewTermsKeepingItsVenueId() throws Exception {
        try (OwnershipLog log = OwnershipLog.open(path(), SIZE, ctx -> {})) {
            final OrderContext order = order(42, 1);
            log.added(order);
            venueId(order, "v-1");
            log.venueIdLearned(order);
            order.limitPrice = 350_000_000;
            order.originalQty = 2_000_000;
            log.amended(order);
        }
        final List<OrderContext> open = OwnershipLog.read(path());
        assertEquals(1, open.size());
        assertEquals(350_000_000, open.get(0).limitPrice);
        assertEquals(2_000_000, open.get(0).originalQty);
        assertEquals("v-1", new String(open.get(0).exchangeOrderIdBytes, 0, open.get(0).exchangeOrderIdLength));
    }

    @Test
    void aRecordTornOrDamagedEndsTheLogThereAndNothingAfterItCounts() throws Exception {
        try (OwnershipLog log = OwnershipLog.open(path(), SIZE, ctx -> {})) {
            log.added(order(42, 1));
            log.added(order(42, 2));
            log.added(order(42, 3));
        }
        // Flip a byte inside the second record's correlation id.
        final List<OrderContext> before = OwnershipLog.read(path());
        assertEquals(3, before.size());
        try (RandomAccessFile file = new RandomAccessFile(path().toFile(), "rw")) {
            final byte[] all = new byte[4096];
            file.readFully(all);
            final byte[] needle = "s42-2".getBytes(StandardCharsets.US_ASCII);
            final int at = indexOf(all, needle);
            file.seek(at);
            file.write('X');
        }
        assertEquals(
                List.of("s42-1"),
                OwnershipLog.read(path()).stream()
                        .map(OwnershipLogTest::correlation)
                        .toList());
    }

    @Test
    void reopeningStartsAFreshLogHoldingOnlyTheOpenOrders() throws Exception {
        try (OwnershipLog log = OwnershipLog.open(path(), SIZE, ctx -> {})) {
            for (int i = 1; i <= 100; i++) {
                log.added(order(42, i));
            }
            for (int i = 1; i <= 99; i++) {
                log.ended(order(42, i));
            }
        }
        final List<String> recovered = new ArrayList<>();
        try (OwnershipLog log = OwnershipLog.open(path(), SIZE, ctx -> recovered.add(correlation(ctx)))) {
            log.added(order(44, 1));
        }
        assertEquals(List.of("s42-100"), recovered);
        assertEquals(
                List.of("s42-100", "s44-1"),
                OwnershipLog.read(path()).stream()
                        .map(OwnershipLogTest::correlation)
                        .toList());
    }

    @Test
    void aFullLogIsRewrittenWithOnlyTheOrdersTheLaneHolds() throws Exception {
        final List<OrderContext> held = new ArrayList<>();
        try (OwnershipLog log = new OwnershipLogs(path()).open(held)) {
            // Far more records than fit: every order but the last few ends.
            for (int i = 1; i <= 2_000; i++) {
                final OrderContext ctx = order(42, i);
                log.added(ctx);
                held.add(ctx);
                if (i % 10 != 0) {
                    log.ended(ctx);
                    held.remove(ctx);
                }
            }
        }
        final List<OrderContext> open = OwnershipLog.read(path());
        assertEquals(200, open.size());
        assertEquals("s42-2000", correlation(open.get(open.size() - 1)));
    }

    /** Opens a log whose lane is stood in for by a list of held orders. */
    private record OwnershipLogs(Path path) {
        OwnershipLog open(final List<OrderContext> held) throws Exception {
            final OwnershipLog log = OwnershipLog.open(this.path, SIZE, ctx -> {});
            log.held(visitor -> held.forEach(visitor));
            return log;
        }
    }

    private static int indexOf(final byte[] haystack, final byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("not found");
    }
}
