package group.gnometrading.orchestrator.session.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.gateway.GatewayFixture;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.gateway.HelloRefusal;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sessions and an order gateway in one process, over real media drivers, the gateway's account on a fake venue:
 * orders out and reports back, replay after a reconnect, a poison session evicted while another trades, and a gateway
 * restarted from its ownership log with a fill arriving while it was down.
 */
class GatewayInProcessTest {

    private static final int INGRESS_PORT = 40977;
    private static final long UNIT = 1_000_000L;

    @TempDir
    Path state;

    private final AeronRuntime sessionAeron =
            AeronRuntime.launch("in-process-sessions", AeronProfile.GATEWAY, Throwable::printStackTrace);
    private final ScheduledExecutorService linkThread = Executors.newSingleThreadScheduledExecutor();
    private GatewayFixture.Venue venue;
    private GatewayFixture gateway;
    private TestSession first;
    private TestSession second;

    @BeforeEach
    void setUp() throws Exception {
        this.venue = GatewayFixture.Venue.start(this.state);
        this.venue.register("session-a", 42, 3);
        this.venue.register("session-b", 43, 4);
        this.gateway = GatewayFixture.start("in-process-gateway", this.venue, INGRESS_PORT);
        this.first = new TestSession("session-a", 42, 3);
        this.second = new TestSession("session-b", 43, 4);
        await(
                () -> this.first.ready() && this.second.ready(),
                () -> "both sessions welcomed; links " + this.first.link.state + "/" + this.first.link.refusal + " "
                        + this.second.link.state + "/" + this.second.link.refusal + ", account "
                        + this.gateway.account().state());
    }

    @AfterEach
    void tearDown() throws Exception {
        this.linkThread.shutdownNow();
        this.first.close();
        this.second.close();
        this.gateway.close();
        this.sessionAeron.close();
        this.venue.server().close();
    }

    @Test
    void twoSessionsTradeSideBySideEachHearingOnlyItsOwnReports() throws Exception {
        this.first.order(1);
        this.second.order(1);
        await(() -> this.venue.server().requests().size() == 2, "both orders at the venue");
        this.venue.transport().push("ACK s42-1");
        this.venue.transport().push("FILL s43-1 " + UNIT);

        await(() -> this.first.reports().size() == 1 && this.second.reports().size() == 1, "a report each");
        assertEquals(ExecType.NEW, this.first.reports().get(0).decoder.execType());
        assertEquals(ExecType.PARTIAL_FILL, this.second.reports().get(0).decoder.execType());
        assertEquals(3, this.first.reports().get(0).getClientOidStrategyId());
        assertEquals(4, this.second.reports().get(0).getClientOidStrategyId());
    }

    @Test
    void anOrderCrossingAnotherSessionsWithinOneRoundTripIsRefusedBeforeTheVenue() throws Exception {
        this.first.order(1, Side.Bid, 400_000_000);
        this.second.order(1, Side.Ask, 400_000_000);

        // The two sessions send on threads of their own, so either may reach the gateway first: that one goes, and
        // the other, which would trade against it, is refused.
        await(() -> !this.first.reports().isEmpty() || !this.second.reports().isEmpty(), "the crossing order answered");
        final TestSession refused = this.first.reports().isEmpty() ? this.second : this.first;
        assertEquals(ExecType.REJECT, refused.reports().get(0).decoder.execType());
        assertEquals(RejectReason.SELF_TRADE, refused.reports().get(0).decoder.rejectReason());
        await(() -> this.venue.server().requests().size() == 1, "the other order at the venue");
        Thread.sleep(200);
        assertEquals(1, this.venue.server().requests().size(), "only one reached the venue");
        assertEquals(1, this.first.reports().size() + this.second.reports().size(), "one refused, none acked yet");
    }

    @Test
    void anOrderCrossingAnotherSessionsOnAnotherAccountIsRefusedToo() throws Exception {
        // In place of the one-account gateway: one holding two accounts, each on a fake venue of its own.
        this.first.close();
        this.second.close();
        this.gateway.close();
        final GatewayFixture.Venue otherVenue = GatewayFixture.Venue.start(this.state.resolve("other"));
        try {
            this.venue
                    .registry()
                    .register(
                            "session-c",
                            44,
                            4,
                            GatewayFixture.EXCHANGE_ID,
                            GatewayFixture.LISTING_ID,
                            GatewayFixture.SECOND_ACCOUNT_ID);
            this.gateway =
                    GatewayFixture.startOnTwoAccounts("in-process-gateway", this.venue, otherVenue, INGRESS_PORT);
            this.first = new TestSession("session-a", 42, 3);
            this.second = new TestSession("session-c", 44, 4, GatewayFixture.SECOND_ACCOUNT_ID);
            await(() -> this.first.ready() && this.second.ready(), "both sessions welcomed, on their own accounts");

            this.first.order(1, Side.Bid, 400_000_000);
            this.second.order(1, Side.Ask, 400_000_000);

            await(
                    () -> !this.first.reports().isEmpty()
                            || !this.second.reports().isEmpty(),
                    "the crossing order answered");
            final TestSession refused = this.first.reports().isEmpty() ? this.second : this.first;
            assertEquals(
                    RejectReason.SELF_TRADE, refused.reports().get(0).decoder.rejectReason());
            Thread.sleep(200);
            assertEquals(
                    1,
                    this.venue.server().requests().size()
                            + otherVenue.server().requests().size(),
                    "only one reached a venue");
        } finally {
            otherVenue.server().close();
        }
    }

    @Test
    void anOrderThatHasFilledNoLongerBlocksAnotherSession() throws Exception {
        this.first.order(1, Side.Bid, 400_000_000);
        await(() -> this.venue.server().requests().size() == 1, "the order at the venue");
        this.venue.transport().push("ACK s42-1");
        this.venue.transport().push("FILL s42-1 " + 3 * UNIT);
        await(() -> this.first.reports().size() == 2, "its fill");

        this.second.order(1, Side.Ask, 400_000_000);

        await(() -> this.venue.server().requests().size() == 2, "the other session's order at the venue");
    }

    @Test
    void aStrategyKillCancelsAndRefusesOnlyThatStrategysOrdersUntilItLifts() throws Exception {
        this.first.order(1);
        this.second.order(1);
        await(() -> this.venue.server().requests().size() == 2, "both orders at the venue");
        this.venue.transport().push("ACK s42-1");
        this.venue.transport().push("ACK s43-1");
        await(() -> this.first.reports().size() == 1 && this.second.reports().size() == 1, "both acknowledged");

        this.gateway.kills("[{\"policy_type\":\"KILL_SWITCH\",\"strategy_id\":3}]");

        await(() -> deletes().size() == 1, "the killed strategy's order cancelled");
        assertTrue(deletes().get(0).contains("s42-1"), "the killed strategy's: " + deletes());
        this.first.order(2);
        await(() -> this.first.reports().size() == 2, "the killed strategy's new order answered");
        assertEquals(RejectReason.HALTED, this.first.reports().get(1).decoder.rejectReason());
        this.second.order(2);
        await(() -> posts() == 3, "the other strategy still trading");

        this.gateway.kills("[]");
        this.first.order(3);
        await(() -> posts() == 4, "the killed strategy trading again once the kill lifts");
        assertEquals(1, deletes().size(), "the other strategy's order untouched");
    }

    private List<String> deletes() {
        return this.venue.server().requests().stream()
                .filter(r -> r.method().equals("DELETE"))
                .map(r -> r.path())
                .toList();
    }

    private long posts() {
        return this.venue.server().requests().stream()
                .filter(r -> r.method().equals("POST"))
                .count();
    }

    @Test
    void aSessionThatReconnectsIsSentWhatItMissedOnce() throws Exception {
        this.first.order(1);
        await(() -> this.venue.server().requests().size() == 1, "the order at the venue");
        this.venue.transport().push("ACK s42-1");
        await(() -> this.first.reports().size() == 1, "the acknowledgement");

        // The session stops hearing the gateway and its link drops; a fill sent meanwhile goes unheard, and waits in
        // the session's replay ring.
        this.first.link.replies = null;
        this.first.link.state = GatewayLink.DOWN;
        this.venue.transport().push("FILL s42-1 " + UNIT);

        await(() -> this.first.ready() && this.first.reports().size() == 2, "the fill it missed");
        assertEquals(ExecType.PARTIAL_FILL, this.first.reports().get(1).decoder.execType());
        Thread.sleep(300);
        assertEquals(2, this.first.reports().size(), "nothing sent twice");
    }

    @Test
    void aPoisonSessionIsEvictedWhileTheOtherKeepsTrading() throws Exception {
        final UnsafeBuffer garbage = new UnsafeBuffer(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9});
        await(() -> this.second.link.publication.offer(garbage, 0, 9) > 0, "the garbage sent");
        await(() -> this.second.link.refusal == HelloRefusal.EVICTED || !this.gateway.linked(43), "the eviction");

        this.first.order(1);
        await(() -> this.venue.server().requests().size() == 1, "the other's order at the venue");
        this.venue.transport().push("ACK s42-1");
        await(() -> this.first.reports().size() == 1, "its acknowledgement");

        this.second.order(1);
        await(() -> !this.second.reports().isEmpty(), "the evicted session's order refused");
        assertEquals(
                RejectReason.GATEWAY_UNAVAILABLE,
                this.second.reports().get(0).decoder.rejectReason());
    }

    @Test
    void aRestartedGatewayStillRefusesAnOrderCrossingOneItTookBack() throws Exception {
        this.first.order(1, Side.Bid, 400_000_000);
        await(() -> this.venue.server().requests().size() == 1, "the bid at the venue");
        this.venue.transport().push("ACK s42-1");
        await(() -> this.first.reports().size() == 1, "acknowledged");

        this.gateway.kill();
        this.venue.protocol().catchUp = List.of("ACK s42-1");
        this.gateway = GatewayFixture.start("in-process-gateway", this.venue, INGRESS_PORT);
        await(() -> this.gateway.account().lane().workingOrders() == 1, "the bid taken back");
        // Its link may still read ready from before the restart; the new gateway linking it is what counts.
        await(() -> this.gateway.linked(43) && this.second.ready(), "the other session linked to the new gateway");

        this.second.order(1, Side.Ask, 400_000_000);

        await(() -> !this.second.reports().isEmpty(), "the crossing ask answered");
        assertEquals(
                RejectReason.SELF_TRADE, this.second.reports().get(0).decoder.rejectReason());
        assertEquals(1, this.venue.server().requests().size(), "nothing new reached the venue");
    }

    @Test
    void aRestartedGatewayTakesBackItsOrdersAndAFillDuringTheOutageReachesTheSessionOnce() throws Exception {
        this.first.order(1);
        this.first.order(2);
        await(() -> this.venue.server().requests().size() == 2, "both orders at the venue");
        this.venue.transport().push("ACK s42-1");
        this.venue.transport().push("ACK s42-2");
        await(() -> this.first.reports().size() == 2, "both acknowledged");

        this.gateway.kill();
        // While it is down, order 1 fills; the restarted gateway's catch-up finds that.
        this.venue.protocol().catchUp = List.of("ACK s42-1", "FILL s42-1 " + UNIT, "ACK s42-2");
        this.gateway = GatewayFixture.start("in-process-gateway", this.venue, INGRESS_PORT);
        await(() -> this.gateway.account().lane().workingOrders() == 2, "the orders taken back");

        await(() -> this.first.ready() && this.first.reports().size() == 3, "the fill made during the outage");
        final OrderExecutionReport fill = this.first.reports().get(2);
        assertEquals(ExecType.PARTIAL_FILL, fill.decoder.execType());
        assertEquals(1, fill.getClientOidCounter());
        assertEquals(UNIT, fill.decoder.filledQty());
        assertEquals(500_000_000L, fill.decoder.fillPrice());
        Thread.sleep(300);
        assertEquals(3, this.first.reports().size(), "nothing lost or doubled");
        assertEquals(
                2,
                this.venue.server().requests().stream()
                        .filter(r -> r.method().equals("POST"))
                        .count(),
                "nothing sent again");
    }

    @Test
    void aStartingSessionAsksTheVenueThroughTheGatewayWithNoKeyOfItsOwn() throws Exception {
        final VenueOrder resting = new VenueOrder("x-1", "v-x-1", UNIT, 400_000_000L, 7, false);
        final VenueOrder ended = new VenueOrder("x-2", "v-x-2", 3 * UNIT, 1_200_000_000L, 9, true);
        this.venue.orders().orders.put("x-1", resting);
        this.venue.orders().orders.put("x-2", ended);
        final VenueOrderQuery query = this.first.recoveryQuery();

        assertEquals(List.of(resting), query.listOpenOrders(GatewayFixture.listing()));
        assertEquals(Optional.of(ended), query.getOrder(GatewayFixture.listing(), "x-2", 0));
        assertEquals(Optional.empty(), query.getOrder(GatewayFixture.listing(), "x-3", 0));
    }

    @Test
    void aSessionCantCancelAnotherStrategysRestingOrder() throws Exception {
        this.second.order(1);
        await(() -> this.venue.server().requests().size() == 1, "the other strategy's order at the venue");
        this.venue.transport().push("ACK s43-1");
        await(() -> this.second.reports().size() == 1, "acknowledged");
        final VenueOrder theirs = new VenueOrder("s43-1", "v-s43-1", 0, 0, 0, false);
        this.venue.orders().orders.put("s43-1", theirs);

        final IOException refused = assertThrows(
                IOException.class, () -> this.first.recoveryQuery().cancel(GatewayFixture.listing(), theirs));

        assertTrue(refused.getMessage().contains("refused"), refused.getMessage());
        assertTrue(
                this.venue.server().requests().stream()
                        .noneMatch(r -> r.method().equals("DELETE")),
                "nothing cancelled at the venue");
    }

    @Test
    void aSessionCancelsWhatAnEndedSessionOfItsStrategyLeftAfterARestart() throws Exception {
        this.venue.register("session-c", 44, 3);
        final TestSession ended = new TestSession("session-c", 44, 3);
        try {
            await(ended::ready, "the earlier session welcomed");
            ended.order(1);
            await(() -> this.venue.server().requests().size() == 1, "its order at the venue");
            this.venue.transport().push("ACK s44-1");
            await(() -> ended.reports().size() == 1, "acknowledged");
        } finally {
            ended.close();
        }
        final VenueOrder left = new VenueOrder("s44-1", "v-s44-1", 0, 0, 0, false);
        this.venue.orders().orders.put("s44-1", left);
        this.venue.registry().end("session-c");
        this.gateway.kill();
        this.gateway = GatewayFixture.start("in-process-gateway", this.venue, INGRESS_PORT);
        await(this.first::ready, "the running session welcomed again");
        // The venue takes the cancel: its lookups say so from then on.
        final Thread venueCancels = new Thread(() -> {
            while (this.venue.server().requests().stream()
                    .noneMatch(r -> r.method().equals("DELETE"))) {
                Thread.onSpinWait();
            }
            this.venue.orders().orders.put("s44-1", new VenueOrder("s44-1", "v-s44-1", 0, 0, 0, true));
        });
        venueCancels.setDaemon(true);
        venueCancels.start();

        assertTrue(this.first.recoveryQuery().cancel(GatewayFixture.listing(), left));

        assertTrue(this.venue.orders().orders.get("s44-1").terminal());
    }

    // ----- a session -----

    /** One session's OMS rings and its gateway client, on threads of their own. */
    private final class TestSession implements AutoCloseable {
        final GatewayLink link;
        private final GatewayClient client;
        private final GatewayLinks links;
        private final SequencedRingBuffer<Intent> omsOrders;
        private final List<OrderExecutionReport> received = new CopyOnWriteArrayList<>();
        private final SequencedPoller omsReader;
        private final int strategyId;
        private final GnomeAgentRunner runner;

        TestSession(final String sessionId, final long slot, final int strategyId) {
            this(sessionId, slot, strategyId, GatewayFixture.ACCOUNT_ID);
        }

        TestSession(final String sessionId, final long slot, final int strategyId, final int accountId) {
            this.strategyId = strategyId;
            final GlobalSequence sequence = new GlobalSequence();
            this.omsOrders = new SequencedRingBuffer<>(Intent::new, sequence);
            final SequencedRingBuffer<OrderExecutionReport> toOms =
                    new SequencedRingBuffer<>(OrderExecutionReport::new, sequence);
            this.omsReader = toOms.createPoller((seq, templateId, buffer, length) -> {
                final OrderExecutionReport report = new OrderExecutionReport();
                report.buffer.putBytes(0, buffer, 0, length);
                this.received.add(report);
            });
            this.link = new GatewayLink(
                    "live:test:main",
                    GatewayFixture.EXCHANGE_ID,
                    accountId,
                    List.<long[]>of(new long[] {GatewayFixture.EXCHANGE_ID, GatewayFixture.SECURITY_ID}),
                    64);
            this.client = new GatewayClient(
                    List.of(this.link),
                    this.omsOrders,
                    toOms,
                    new GatewayClient.Identity(slot, sessionId, strategyId, 5_000, 2_000),
                    64,
                    System::nanoTime,
                    new SystemEpochNanoClock());
            this.links = new GatewayLinks(
                    new NullLogger(),
                    GatewayInProcessTest.this.sessionAeron.aeron(),
                    "127.0.0.1",
                    List.of(this.link),
                    key -> new GatewayLinks.Endpoint("127.0.0.1", INGRESS_PORT),
                    System::nanoTime);
            GatewayInProcessTest.this.linkThread.scheduleWithFixedDelay(this.links, 0, 50, TimeUnit.MILLISECONDS);
            this.runner = new GnomeAgentRunner(this.client, Throwable::printStackTrace);
            GnomeAgentRunner.startOnThread(this.runner);
        }

        boolean ready() {
            return this.link.state == GatewayLink.READY;
        }

        VenueOrderQuery recoveryQuery() {
            return new GatewayVenueOrderQuery(this.client, TimeUnit.SECONDS.toNanos(10));
        }

        void order(final long counter) {
            order(counter, Side.Bid, 400_000_000);
        }

        void order(final long counter, final Side side, final long price) {
            final Order order = new Order();
            order.encoder
                    .exchangeId(GatewayFixture.EXCHANGE_ID)
                    .securityId(GatewayFixture.SECURITY_ID)
                    .price(price)
                    .size(3 * UNIT)
                    .side(side);
            order.encoder.flags().postOnly(true);
            order.encodeClientOid(counter, this.strategyId);
            this.omsOrders.publishRaw(order.buffer, OrderDecoder.TEMPLATE_ID, order.totalMessageSize());
        }

        List<OrderExecutionReport> reports() {
            try {
                this.omsReader.poll();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return this.received;
        }

        @Override
        public void close() throws Exception {
            this.runner.close();
            this.links.close();
        }
    }

    private static void await(final BooleanSupplier condition, final String what) throws InterruptedException {
        await(condition, () -> what);
    }

    private static void await(final BooleanSupplier condition, final java.util.function.Supplier<String> what)
            throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what.get());
            }
            Thread.sleep(5);
        }
    }
}
