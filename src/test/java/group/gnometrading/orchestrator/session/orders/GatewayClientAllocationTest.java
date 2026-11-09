package group.gnometrading.orchestrator.session.orders;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.gateway.GatewayFixture;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.Side;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Once warm, the session's gateway client must not allocate: every order the strategy sends and every report back
 * passes through its thread. Driven through a whole gateway in process, the venue filling each order outright.
 */
@Tag("allocation")
class GatewayClientAllocationTest {

    private static final int ORDERS = 4_000;
    private static final long SLOT = 42;
    private static final int STRATEGY_ID = 3;

    @Test
    @Timeout(180)
    void theClientThreadDoesNotAllocateOnceWarm() throws Exception {
        final GatewayFixture.Venue venue = GatewayFixture.Venue.start(Files.createTempDirectory("client-alloc"));
        venue.register("session-a", SLOT, STRATEGY_ID);
        try (GatewayFixture gateway = GatewayFixture.start("client-alloc-gateway", venue, 40978);
                AeronRuntime aeron =
                        AeronRuntime.launch("client-alloc-session", AeronProfile.GATEWAY, Throwable::printStackTrace)) {
            final GlobalSequence sequence = new GlobalSequence();
            final SequencedRingBuffer<Intent> omsOrders = new SequencedRingBuffer<>(Intent::new, sequence);
            final SequencedRingBuffer<OrderExecutionReport> toOms =
                    new SequencedRingBuffer<>(OrderExecutionReport::new, sequence);
            final AtomicLong reports = new AtomicLong();
            final SequencedPoller omsReader =
                    toOms.createPoller((seq, templateId, buffer, length) -> reports.incrementAndGet());
            final GatewayLink link = new GatewayLink(
                    "live:test:main",
                    GatewayFixture.EXCHANGE_ID,
                    GatewayFixture.ACCOUNT_ID,
                    List.<long[]>of(new long[] {GatewayFixture.EXCHANGE_ID, GatewayFixture.SECURITY_ID}),
                    1024);
            final GatewayClient client = new GatewayClient(
                    List.of(link),
                    omsOrders,
                    toOms,
                    new GatewayClient.Identity(SLOT, "session-a", STRATEGY_ID, 5_000, 2_000),
                    1024,
                    System::nanoTime,
                    new SystemEpochNanoClock());
            final GatewayLinks links = new GatewayLinks(
                    new NullLogger(),
                    aeron.aeron(),
                    "127.0.0.1",
                    List.of(link),
                    key -> new GatewayLinks.Endpoint("127.0.0.1", 40978),
                    System::nanoTime);
            final AtomicLong clientThread = new AtomicLong();
            final GnomeAgentRunner runner = new GnomeAgentRunner(
                    new GnomeAgent() {
                        @Override
                        public int doWork() throws Exception {
                            clientThread.compareAndSet(0, Thread.currentThread().getId());
                            return client.doWork();
                        }
                    },
                    Throwable::printStackTrace);
            GnomeAgentRunner.startOnThread(runner);
            try {
                while (link.state != GatewayLink.READY) {
                    links.run();
                    Thread.sleep(20);
                }
                trade(venue, omsOrders, omsReader, reports, 1, ORDERS / 2);
                final com.sun.management.ThreadMXBean threads =
                        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                final long before = threads.getThreadAllocatedBytes(clientThread.get());
                trade(venue, omsOrders, omsReader, reports, ORDERS / 2 + 1, ORDERS);
                final long allocated = threads.getThreadAllocatedBytes(clientThread.get()) - before;
                System.out.println("#### gateway client allocated " + allocated + "B over " + (ORDERS / 2) + " orders");
                assertTrue(allocated < 16_384, "the gateway client allocated " + allocated + " bytes");
            } finally {
                runner.close();
                links.close();
            }
        } finally {
            venue.server().close();
        }
    }

    /** Sends orders {@code from} to {@code to}, each filled outright by the venue, and waits for every report. */
    private static void trade(
            final GatewayFixture.Venue venue,
            final SequencedRingBuffer<Intent> omsOrders,
            final SequencedPoller omsReader,
            final AtomicLong reports,
            final int from,
            final int to)
            throws Exception {
        final Order order = new Order();
        order.encoder
                .exchangeId(GatewayFixture.EXCHANGE_ID)
                .securityId(GatewayFixture.SECURITY_ID)
                .price(400_000_000)
                .size(1_000_000)
                .side(Side.Bid);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        for (int counter = from; counter <= to; counter++) {
            order.encodeClientOid(counter, STRATEGY_ID);
            omsOrders.publishRaw(order.buffer, OrderDecoder.TEMPLATE_ID, order.totalMessageSize());
            // Filled once the venue has the order, which frees its slot for the next.
            while (venue.server().received() < counter) {
                omsReader.poll();
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("order " + counter + " never reached the venue");
                }
                Thread.onSpinWait();
            }
            venue.transport().push("FILL s42-" + counter + " 1000000");
        }
        while (reports.get() < to) {
            omsReader.poll();
            if (System.nanoTime() > deadline) {
                throw new AssertionError("only " + reports.get() + " of " + to + " reports came back");
            }
            Thread.sleep(1);
        }
    }
}
