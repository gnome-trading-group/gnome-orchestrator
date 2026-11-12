package group.gnometrading.orchestrator.session.orders;

import static group.gnometrading.orchestrator.gateway.PaperGatewayFixture.P;
import static group.gnometrading.orchestrator.gateway.PaperGatewayFixture.S;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.gateway.PaperGatewayFixture;
import group.gnometrading.orchestrator.gateway.PaperGatewayFixture.Book;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Paper sessions and paper gateways in one process, over real media drivers, as a paper session trades now: its
 * gateway client links to each venue's paper gateway, which simulates the venue on books of the session's own, fed the
 * shards' records, under the venue's limits and the gateway's caps as if the session were alone on it.
 */
class PaperGatewayInProcessTest {

    // Each test's gateways on ports of their own, so none waits on a port the one before still holds.
    private static final AtomicInteger PORTS = new AtomicInteger(40960);
    private static final int EXCHANGE = 21;
    private static final int OTHER_EXCHANGE = 22;

    @TempDir
    Path state;

    private final AeronRuntime sessionAeron =
            AeronRuntime.launch("paper-in-process-sessions", AeronProfile.GATEWAY, Throwable::printStackTrace);
    private final ScheduledExecutorService linkThread = Executors.newSingleThreadScheduledExecutor();
    private final List<AutoCloseable> closing = new ArrayList<>();
    private final int ingressPort = PORTS.getAndAdd(2);
    private final int otherIngressPort = this.ingressPort + 1;

    @AfterEach
    void tearDown() throws Exception {
        this.linkThread.shutdownNow();
        for (int i = this.closing.size() - 1; i >= 0; i--) {
            try {
                this.closing.get(i).close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        this.sessionAeron.close();
    }

    @Test
    void anOrderAcrossTheSpreadFillsAgainstTheBookAsTheVenueShowsIt() throws Exception {
        final PaperGatewayFixture.Venue venue = venue(EXCHANGE, 1, "venue");
        final PaperGatewayFixture gateway = start("paper-fills", this.ingressPort, venue);
        final Book book = venue.book(0);
        final TestSession session = session(venue, "session-a", 42, 3, gateway);
        gateway.quote(book, 100 * P, 10 * S, 102 * P, 40 * S);
        settle();

        session.order(book, 1, Side.Bid, 102 * P, 5 * S);

        await(() -> session.has(1, ExecType.FILL), () -> "a fill; heard " + session.describe());
        final OrderExecutionReport fill = session.last(1);
        assertEquals(OrderStatus.FILLED, fill.decoder.orderStatus());
        assertEquals(102 * P, fill.decoder.fillPrice());
        assertEquals(5 * S, fill.decoder.filledQty());
        assertTrue(session.venueId(1).startsWith("session-a-"), "venue ids name the session, as a live venue's do");
    }

    @Test
    void aNewOrderOnABookWithNoRecordYetIsRefusedNotReady() throws Exception {
        final PaperGatewayFixture.Venue venue = venue(EXCHANGE, 1, "venue");
        final PaperGatewayFixture gateway = start("paper-not-ready", this.ingressPort, venue);
        final TestSession session = session(venue, "session-a", 42, 3, gateway);

        session.order(venue.book(0), 1, Side.Bid, 100 * P, 5 * S);

        await(() -> session.has(1, ExecType.REJECT), () -> "a reject; heard " + session.describe());
        assertEquals(RejectReason.GATEWAY_NOT_READY, session.last(1).decoder.rejectReason());
    }

    @Test
    void twoSessionsWhoseOrdersCrossBothRestEachOnItsOwnBook() throws Exception {
        final PaperGatewayFixture.Venue venue = venue(EXCHANGE, 1, "venue");
        final PaperGatewayFixture gateway = start("paper-cross", this.ingressPort, venue);
        final Book book = venue.book(0);
        final TestSession buyer = session(venue, "session-a", 42, 3, gateway);
        final TestSession seller = session(venue, "session-b", 43, 4, gateway);
        gateway.quote(book, 100 * P, 10 * S, 102 * P, 40 * S);
        settle();

        buyer.order(book, 1, Side.Bid, 101 * P, 5 * S);
        seller.order(book, 1, Side.Ask, 101 * P, 5 * S);

        await(
                () -> buyer.has(1, ExecType.NEW) && seller.has(1, ExecType.NEW),
                () -> "both resting; heard " + buyer.describe() + " / " + seller.describe());
        Thread.sleep(200);
        assertEquals(1, buyer.reports().size(), "no self-trade check on paper, and no fill against the other");
        assertEquals(1, seller.reports().size(), "no self-trade check on paper, and no fill against the other");
        assertEquals(2, gateway.workingOrders());
    }

    @Test
    void aSessionPastItsBudgetIsRateLimitedWhileAnotherKeepsAWholeBudgetOfItsOwn() throws Exception {
        final PaperGatewayFixture.Venue venue = venue(EXCHANGE, 1, "venue");
        // Four requests a session, two a second after: with a quarter kept for cancels, three new orders at once.
        final VenueRateLimits limits = VenueRateLimits.of(
                "Paper",
                List.of(new VenueRateLimits.Bucket("all", 2, 4, 0.25)),
                List.of(
                        new VenueRateLimits.Rule(null, "", null, "all", 1, RequestClass.CANCEL),
                        new VenueRateLimits.Rule(null, "", null, "all", 1, RequestClass.NEW)),
                List.of());
        final PaperGatewayFixture gateway =
                start("paper-budget", this.ingressPort, venue, limits, AccountLane.Caps.NONE);
        final Book book = venue.book(0);
        final TestSession busy = session(venue, "session-a", 42, 3, gateway);
        final TestSession quiet = session(venue, "session-b", 43, 4, gateway);
        gateway.quote(book, 100 * P, 10 * S, 102 * P, 40 * S);
        settle();
        // A session's budget starts drained, as every account budget does, and fills in two seconds.
        Thread.sleep(2_200);

        for (long counter = 1; counter <= 6; counter++) {
            busy.order(book, counter, Side.Bid, 99 * P, S);
        }
        await(() -> busy.answered(6), () -> "every order answered; heard " + busy.describe());
        quiet.order(book, 1, Side.Bid, 99 * P, S);
        quiet.order(book, 2, Side.Bid, 98 * P, S);
        await(() -> quiet.answered(2), () -> "both answered; heard " + quiet.describe());

        assertEquals(3, busy.count(ExecType.NEW), busy.describe());
        assertEquals(3, busy.count(RejectReason.RATE_LIMITED), busy.describe());
        assertEquals(2, quiet.count(ExecType.NEW), "a budget of its own: " + quiet.describe());
    }

    @Test
    void aNewOrderPastTheSessionsOpenOrderCapIsRefused() throws Exception {
        final PaperGatewayFixture.Venue venue = venue(EXCHANGE, 1, "venue");
        final PaperGatewayFixture gateway =
                start("paper-cap", this.ingressPort, venue, null, new AccountLane.Caps(2, 0, 0));
        final Book book = venue.book(0);
        final TestSession session = session(venue, "session-a", 42, 3, gateway);
        gateway.quote(book, 100 * P, 10 * S, 102 * P, 40 * S);
        settle();

        session.order(book, 1, Side.Bid, 99 * P, S);
        session.order(book, 2, Side.Bid, 98 * P, S);
        session.order(book, 3, Side.Bid, 97 * P, S);

        await(() -> session.answered(3), () -> "all three answered; heard " + session.describe());
        assertEquals(2, session.count(ExecType.NEW));
        assertEquals(RejectReason.SESSION_CAP, session.last(3).decoder.rejectReason());
    }

    @Test
    void aRestartedPaperGatewayReportsTheOrdersItLostCanceled() throws Exception {
        final PaperGatewayFixture.Venue venue = venue(EXCHANGE, 1, "venue");
        PaperGatewayFixture gateway = PaperGatewayFixture.start("paper-restart", this.ingressPort, venue);
        final Book book = venue.book(0);
        final TestSession session = session(venue, "session-a", 42, 3, gateway);
        gateway.quote(book, 100 * P, 10 * S, 102 * P, 40 * S);
        settle();
        session.order(book, 1, Side.Bid, 99 * P, S);
        await(() -> session.has(1, ExecType.NEW), () -> "resting; heard " + session.describe());

        gateway.kill();
        gateway = start("paper-restart-2", this.ingressPort, venue);

        await(() -> session.has(1, ExecType.CANCEL), () -> "the lost order reported; heard " + session.describe());
        assertEquals(OrderStatus.CANCELED, session.last(1).decoder.orderStatus());
        assertTrue(
                !session.has(1, ExecType.FILL) && !session.has(1, ExecType.PARTIAL_FILL),
                "nothing filled: " + session.describe());
    }

    @Test
    void aSessionOnSeveralListingsTradesEachOnItsOwnBook() throws Exception {
        final PaperGatewayFixture.Venue venue = venue(EXCHANGE, 2, "venue");
        final PaperGatewayFixture gateway = start("paper-listings", this.ingressPort, venue);
        final Book first = venue.book(0);
        final Book second = venue.book(1);
        final TestSession session = session(venue, "session-a", 42, 3, gateway);
        gateway.quote(first, 100 * P, 10 * S, 102 * P, 40 * S);
        gateway.quote(second, 50 * P, 10 * S, 51 * P, 40 * S);
        settle();

        session.order(first, 1, Side.Bid, 102 * P, S);
        session.order(second, 2, Side.Ask, 50 * P, S);

        await(
                () -> session.has(1, ExecType.FILL) && session.has(2, ExecType.FILL),
                () -> "both filled; heard " + session.describe());
        assertEquals(102 * P, session.last(1).decoder.fillPrice());
        assertEquals(50 * P, session.last(2).decoder.fillPrice());
    }

    @Test
    void aSessionOnTwoVenuesTradesThroughEachVenuesPaperGateway() throws Exception {
        final PaperGatewayFixture.Venue venue = venue(EXCHANGE, 1, "venue");
        final PaperGatewayFixture.Venue otherVenue = venue(OTHER_EXCHANGE, 1, "other");
        final PaperGatewayFixture gateway = start("paper-venue-a", this.ingressPort, venue);
        final PaperGatewayFixture otherGateway = start("paper-venue-b", this.otherIngressPort, otherVenue);
        final Book book = venue.book(0);
        final Book otherBook = otherVenue.book(0);
        venue.register("session-a", 42, 3, Set.of(book.listing().listingId()));
        otherVenue.register("session-a", 42, 3, Set.of(otherBook.listing().listingId()));
        final TestSession session = new TestSession(
                "session-a",
                42,
                3,
                Map.of(
                        link(book), this.ingressPort,
                        link(otherBook), this.otherIngressPort));
        this.closing.add(session);
        await(session::ready, () -> "linked to both; " + session.describe());
        gateway.quote(book, 100 * P, 10 * S, 102 * P, 40 * S);
        otherGateway.quote(otherBook, 30 * P, 10 * S, 31 * P, 40 * S);
        settle();

        session.order(book, 1, Side.Bid, 102 * P, S);
        session.order(otherBook, 2, Side.Ask, 30 * P, S);

        await(
                () -> session.has(1, ExecType.FILL) && session.has(2, ExecType.FILL),
                () -> "both filled; heard " + session.describe());
        assertEquals(102 * P, session.last(1).decoder.fillPrice());
        assertEquals(30 * P, session.last(2).decoder.fillPrice());
    }

    // ----- harness -----

    private PaperGatewayFixture.Venue venue(final int exchangeId, final int listings, final String name) {
        return PaperGatewayFixture.Venue.of(exchangeId, exchangeId * 100, listings, this.state.resolve(name));
    }

    private PaperGatewayFixture start(final String name, final int port, final PaperGatewayFixture.Venue venue)
            throws Exception {
        return start(name, port, venue, null, AccountLane.Caps.NONE);
    }

    private PaperGatewayFixture start(
            final String name,
            final int port,
            final PaperGatewayFixture.Venue venue,
            final VenueRateLimits limits,
            final AccountLane.Caps caps)
            throws Exception {
        final PaperGatewayFixture gateway = PaperGatewayFixture.start(name, port, venue, limits, caps);
        this.closing.add(gateway);
        return gateway;
    }

    /** A paper session on every listing of {@code venue}, linked and welcomed. */
    private TestSession session(
            final PaperGatewayFixture.Venue venue,
            final String sessionId,
            final long slot,
            final int strategyId,
            final PaperGatewayFixture gateway)
            throws Exception {
        final Set<Integer> listingIds = new java.util.HashSet<>();
        final List<long[]> listings = new ArrayList<>();
        for (final Book book : venue.books()) {
            listingIds.add(book.listing().listingId());
            listings.add(
                    new long[] {venue.exchangeId(), book.listing().security().securityId()});
        }
        venue.register(sessionId, slot, strategyId, listingIds);
        final GatewayLink link =
                new GatewayLink("paper:paper" + venue.exchangeId() + ":main", venue.exchangeId(), 0, listings, 64);
        final TestSession session = new TestSession(sessionId, slot, strategyId, Map.of(link, this.ingressPort));
        this.closing.add(session);
        await(() -> session.ready() && gateway.linked(slot), () -> "welcomed; " + session.describe());
        return session;
    }

    private static GatewayLink link(final Book book) {
        final int exchangeId = book.listing().exchange().exchangeId();
        return new GatewayLink(
                "paper:paper" + exchangeId + ":main",
                exchangeId,
                0,
                List.<long[]>of(
                        new long[] {exchangeId, book.listing().security().securityId()}),
                64);
    }

    /** A paper session's order entry as {@code SessionOrders} wires it: one client, a link per venue's gateway. */
    private final class TestSession implements AutoCloseable {
        private final List<GatewayLink> links;
        private final GatewayLinks gatewayLinks;
        private final SequencedRingBuffer<Intent> omsOrders;
        private final List<OrderExecutionReport> received = new CopyOnWriteArrayList<>();
        private final SequencedPoller omsReader;
        private final int strategyId;
        private final GnomeAgentRunner runner;

        TestSession(
                final String sessionId, final long slot, final int strategyId, final Map<GatewayLink, Integer> ports) {
            this.strategyId = strategyId;
            this.links = List.copyOf(ports.keySet());
            final GlobalSequence sequence = new GlobalSequence();
            this.omsOrders = new SequencedRingBuffer<>(Intent::new, sequence);
            final SequencedRingBuffer<OrderExecutionReport> toOms =
                    new SequencedRingBuffer<>(OrderExecutionReport::new, sequence);
            this.omsReader = toOms.createPoller((seq, templateId, buffer, length) -> {
                final OrderExecutionReport report = new OrderExecutionReport();
                report.buffer.putBytes(0, buffer, 0, length);
                this.received.add(report);
            });
            final GatewayClient client = new GatewayClient(
                    this.links,
                    this.omsOrders,
                    toOms,
                    new GatewayClient.Identity(slot, sessionId, strategyId, 5_000, 50),
                    64,
                    System::nanoTime,
                    new SystemEpochNanoClock());
            this.gatewayLinks = new GatewayLinks(
                    new NullLogger(),
                    PaperGatewayInProcessTest.this.sessionAeron.aeron(),
                    "127.0.0.1",
                    this.links,
                    key -> {
                        for (final Map.Entry<GatewayLink, Integer> entry : ports.entrySet()) {
                            if (entry.getKey().gatewayKey.equals(key)) {
                                return new GatewayLinks.Endpoint("127.0.0.1", entry.getValue());
                            }
                        }
                        return null;
                    },
                    System::nanoTime);
            PaperGatewayInProcessTest.this.linkThread.scheduleWithFixedDelay(
                    this.gatewayLinks, 0, 50, TimeUnit.MILLISECONDS);
            this.runner = new GnomeAgentRunner(client, Throwable::printStackTrace);
            GnomeAgentRunner.startOnThread(this.runner);
        }

        boolean ready() {
            return this.links.stream().allMatch(link -> link.state == GatewayLink.READY);
        }

        void order(final Book book, final long counter, final Side side, final long price, final long size) {
            final Order order = new Order();
            order.encoder
                    .exchangeId(book.listing().exchange().exchangeId())
                    .securityId(book.listing().security().securityId())
                    .price(price)
                    .size(size)
                    .side(side)
                    .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
            order.encoder.flags().clear();
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

        boolean has(final long counter, final ExecType type) {
            return reports().stream().anyMatch(r -> r.getClientOidCounter() == counter && r.decoder.execType() == type);
        }

        /** Whether each of orders 1 to {@code orders} has been accepted or refused. */
        boolean answered(final long orders) {
            for (long counter = 1; counter <= orders; counter++) {
                if (!has(counter, ExecType.NEW) && !has(counter, ExecType.REJECT)) {
                    return false;
                }
            }
            return true;
        }

        long count(final ExecType type) {
            return reports().stream().filter(r -> r.decoder.execType() == type).count();
        }

        long count(final RejectReason reason) {
            return reports().stream()
                    .filter(r -> r.decoder.execType() == ExecType.REJECT && r.decoder.rejectReason() == reason)
                    .count();
        }

        OrderExecutionReport last(final long counter) {
            OrderExecutionReport last = null;
            for (final OrderExecutionReport report : reports()) {
                if (report.getClientOidCounter() == counter) {
                    last = report;
                }
            }
            return last;
        }

        String venueId(final long counter) {
            final OrderExecutionReport report = last(counter);
            final StringBuilder id = new StringBuilder();
            for (int i = 0; i < 64; i++) {
                final byte b = report.decoder.exchangeOrderId(i);
                if (b == 0) {
                    break;
                }
                id.append((char) b);
            }
            return id.toString();
        }

        String describe() {
            final StringBuilder out = new StringBuilder("links");
            for (final GatewayLink link : this.links) {
                out.append(' ')
                        .append(link.gatewayKey)
                        .append('=')
                        .append(link.state)
                        .append('/')
                        .append(link.refusal);
            }
            out.append(", reports");
            for (final OrderExecutionReport report : reports()) {
                out.append(' ')
                        .append(report.getClientOidCounter())
                        .append(':')
                        .append(report.decoder.execType())
                        .append('/')
                        .append(report.decoder.rejectReason());
            }
            return out.toString();
        }

        @Override
        public void close() throws Exception {
            this.runner.close();
            this.gatewayLinks.close();
        }
    }

    /**
     * Until the gateway's books have taken the quotes just written: they join the lane on its own thread and take the
     * next re-send of each book (every 20 ms).
     */
    private static void settle() throws InterruptedException {
        Thread.sleep(100);
    }

    private static void await(final BooleanSupplier condition, final Supplier<String> what)
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
