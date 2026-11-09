package group.gnometrading.orchestrator.gateway.journal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.schemas.Side;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Recording an order happens on the lane's thread before every submit: it must not allocate. */
@Tag("allocation")
class OwnershipLogAllocationTest {

    private static final int ORDERS = 50_000;

    @Test
    void recordingAnOrderAllocatesNothing() throws Exception {
        final Path path = Files.createTempDirectory("ownership").resolve("account.log");
        final OrderContext ctx = new OrderContext();
        ctx.side = Side.Ask;
        ctx.correlationIdLength = 40;
        ctx.exchangeOrderIdLength = 36;
        // Large enough that no rewrite happens while measuring.
        try (OwnershipLog log = OwnershipLog.open(path, 256L << 20, recovered -> {})) {
            record(log, ctx, 0, ORDERS);
            final com.sun.management.ThreadMXBean threads =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            final long before = threads.getCurrentThreadAllocatedBytes();
            record(log, ctx, ORDERS, ORDERS);
            final long allocated = threads.getCurrentThreadAllocatedBytes() - before;
            System.out.println("#### ownership log allocated " + allocated + "B over " + ORDERS + " orders");
            assertTrue(allocated < 4_096, "the ownership log allocated " + allocated + " bytes");
        }
    }

    private static void record(final OwnershipLog log, final OrderContext ctx, final int from, final int count) {
        for (int i = from; i < from + count; i++) {
            ctx.sessionSlot = 42;
            ctx.clientOidCounter = i;
            log.added(ctx);
            log.venueIdLearned(ctx);
            log.ended(ctx);
        }
    }
}
