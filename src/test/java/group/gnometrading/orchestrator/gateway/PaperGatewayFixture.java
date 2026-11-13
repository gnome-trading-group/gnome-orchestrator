package group.gnometrading.orchestrator.gateway;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.AeronStreamIds;
import group.gnometrading.orchestrator.gateway.control.GatewayControl;
import group.gnometrading.orchestrator.gateway.control.GatewayKills;
import group.gnometrading.orchestrator.gateway.control.KillSource;
import group.gnometrading.orchestrator.gateway.control.SessionRegistry;
import group.gnometrading.orchestrator.gateway.core.AccountLink;
import group.gnometrading.orchestrator.gateway.core.CrossKeys;
import group.gnometrading.orchestrator.gateway.core.GatewayAlerts;
import group.gnometrading.orchestrator.gateway.core.GatewayMessage;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.GatewayStatus;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.orchestrator.gateway.core.Kills;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import group.gnometrading.orchestrator.gateway.journal.OwnershipLog;
import group.gnometrading.orchestrator.gateway.simulated.SimulatedAccount;
import group.gnometrading.orchestrator.gateway.transport.CrossIndex;
import group.gnometrading.orchestrator.gateway.transport.GatewayEgress;
import group.gnometrading.orchestrator.gateway.transport.GatewayIngress;
import group.gnometrading.orchestrator.gateway.transport.GatewayRejects;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.StreamHeader;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.Mbp10Decoder;
import group.gnometrading.schemas.book.Side;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
import group.gnometrading.simulation.queues.QueueModel;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.fee.FeeTimeline;
import group.gnometrading.utils.SampledEpochNanoClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.SystemEpochNanoClock;

/**
 * A whole paper gateway in this process, as a deployed one runs it: ingress, egress and control taking only paper
 * hellos, and one simulated account whose books read a market-data ring the test writes to, as the shards would. Each
 * listing simulates with no latency and fills a resting order's whole queue position at once, so what a test sends
 * and the records it writes decide every fill. What outlives the process (the state directory, the registry) is a
 * {@link Venue}, so a test can stop the gateway and start another on it.
 */
public final class PaperGatewayFixture implements AutoCloseable {

    public static final long P = 1_000_000_000L;
    public static final long S = 1_000_000L;
    private static final int MAX_ORDERS = 256;
    private static final int CROSS_CAPACITY = 4096;
    private static final int MARKET_DATA_RING = 1024;

    /** One listing a paper session may trade here, and the stream its book reads. */
    public record Book(Listing listing, int streamId) {}

    /**
     * What outlives a gateway process: the venue it simulates (its exchange and listings), the host's state directory
     * and the registry its hellos are checked against.
     */
    public record Venue(
            int exchangeId,
            List<Book> books,
            Path stateDirectory,
            Map<String, SessionRegistry.SessionRecord> registry) {

        /** A venue with {@code listings} listings, ids {@code firstListingId} on, each on its own stream. */
        public static Venue of(final int exchangeId, final int firstListingId, final int listings, final Path state) {
            final List<Book> books = new ArrayList<>();
            for (int i = 0; i < listings; i++) {
                final int listingId = firstListingId + i;
                books.add(new Book(
                        new Listing(
                                listingId,
                                new Exchange(exchangeId, "PAPER" + exchangeId, "Paper " + exchangeId, "global"),
                                new Security(
                                        100 + listingId,
                                        "S" + listingId,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        false,
                                        0L,
                                        0L,
                                        true,
                                        0),
                                "L" + listingId,
                                "L" + listingId),
                        9_000 + listingId));
            }
            return new Venue(exchangeId, List.copyOf(books), state, new ConcurrentHashMap<>());
        }

        /**
         * A running paper session as the registry has it: trading {@code listingIds}, bound to no account, with its
         * simulation pinned as its launch pins one (the fixture's books ignore what it says).
         */
        public void register(
                final String sessionId, final long slot, final int strategyId, final Set<Integer> listingIds) {
            this.registry.put(
                    sessionId,
                    new SessionRegistry.SessionRecord(
                            slot,
                            "RUNNING",
                            strategyId,
                            "paper",
                            listingIds,
                            Map.of(),
                            1,
                            "{\"as_of\": \"2026-10-16T00:00:00Z\"}"));
        }

        public Book book(final int index) {
            return this.books.get(index);
        }
    }

    private final Venue venue;
    private final AeronRuntime aeron;
    private final SimulatedAccount account;
    private final SequencedRingBuffer<Mbp10Schema> marketData =
            new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence(), MARKET_DATA_RING);
    private final GatewayControl control;
    private final GatewayIngress ingress;
    private final GatewayEgress egress;
    private final SessionTable sessions = new SessionTable();
    private final List<OwnershipLog> logs;
    private final List<GnomeAgentRunner> runners = new ArrayList<>();
    private final Mbp10Schema record = new Mbp10Schema();
    private final Map<Book, long[]> quotes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService resender = Executors.newSingleThreadScheduledExecutor();
    private boolean stopped;

    /**
     * @param limits the venue's order-entry limits, each session getting them whole; null for none
     * @param caps what one session may hold and send
     */
    private PaperGatewayFixture(
            final String name,
            final int ingressPort,
            final Venue venue,
            final VenueRateLimits limits,
            final AccountLane.Caps caps)
            throws Exception {
        this.venue = venue;
        this.aeron = AeronRuntime.launch(name, AeronProfile.GATEWAY, Throwable::printStackTrace);
        final GatewayControl.Listings listings = new GatewayControl.Listings() {
            @Override
            public Listing listing(final int exchangeId, final long securityId) {
                for (final Book book : venue.books()) {
                    if (book.listing().exchange().exchangeId() == exchangeId
                            && book.listing().security().securityId() == securityId) {
                        return book.listing();
                    }
                }
                return null;
            }

            @Override
            public FeeTimeline fees(final Listing listed) {
                return FeeTimeline.EMPTY;
            }
        };
        this.account = new SimulatedAccount(
                new NullLogger(),
                venue.exchangeId(),
                name,
                limits,
                caps,
                AccountLane.Timing.defaults().maxQueueDelayNanos(),
                MAX_ORDERS,
                (sessionId, listing, simulation) -> new SimulatedAccount.Simulated(exchange(), streamOf(listing)),
                this.marketData,
                new SimulatedAccount.Streams() {
                    @Override
                    public void addSession(final String sessionId, final int[] streamIds) {}

                    @Override
                    public void removeSession(final String sessionId) {}
                },
                System::nanoTime,
                new SystemEpochNanoClock());
        final SessionRegistry registry = new SessionRegistry(venue.registry()::get);
        final CrossIndex cross = new CrossIndex(new CrossKeys(), CrossIndex.Mode.OFF, CROSS_CAPACITY, CROSS_CAPACITY);
        final GatewayRestart.Result restart = GatewayRestart.recover(
                new NullLogger(),
                venue.stateDirectory(),
                1 << 20,
                List.of(),
                listings,
                registry,
                System.nanoTime(),
                System.currentTimeMillis(),
                cross);
        this.logs = restart.logs();
        final GatewayStatus status = new GatewayStatus(System.nanoTime());
        final ManyToOneRingBuffer<GatewayMessage> toControl =
                new ManyToOneRingBuffer<>(GatewayMessage[]::new, GatewayMessage::new, 256);
        final ManyToOneRingBuffer<GatewayMessage> toEgress =
                new ManyToOneRingBuffer<>(GatewayMessage[]::new, GatewayMessage::new, 256);
        final ManyToOneRingBuffer<AccountReport> rejects =
                new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 256);
        final Kills kills = new Kills();
        this.ingress = new GatewayIngress(
                this.aeron
                        .aeron()
                        .addSubscription(
                                AeronChannels.gatewayIngress("127.0.0.1", ingressPort), AeronStreamIds.GATEWAY_INGRESS),
                this.sessions,
                status,
                new GatewayRejects(rejects, new SystemEpochNanoClock()),
                toControl,
                System::nanoTime,
                SampledEpochNanoClock.INSTANCE,
                JournalRecorder.NONE,
                cross,
                kills);
        this.egress = new GatewayEgress(
                this.sessions,
                status,
                List.of(this.account.reports(), rejects),
                toEgress,
                System::nanoTime,
                new SystemEpochNanoClock(),
                JournalRecorder.NONE,
                cross);
        final Path killsFile = venue.stateDirectory().resolve("kills.json");
        Files.createDirectories(venue.stateDirectory());
        Files.writeString(killsFile, "[]");
        final GatewayKills gatewayKills = new GatewayKills(
                new NullLogger(),
                GatewayAlerts.NONE,
                KillSource.file(killsFile),
                kills,
                this.sessions,
                listingId -> {
                    for (final Book book : venue.books()) {
                        if (book.listing().listingId() == listingId) {
                            return PublishedMap.listingKey(
                                    book.listing().exchange().exchangeId(),
                                    book.listing().security().securityId());
                        }
                    }
                    return 0;
                },
                System::nanoTime);
        // The gateway takes no new order before its first read of the kills, as at startup.
        gatewayKills.run();
        this.control = new GatewayControl(
                new NullLogger(),
                GatewayAlerts.NONE,
                this.sessions,
                status,
                Map.<Integer, AccountLink>of(SimulatedAccount.ACCOUNT_ID, this.account),
                toControl,
                toEgress,
                registry,
                listings,
                GatewayControl.Replies.over(this.aeron.aeron()),
                (state, linkedSessions, workingOrders) -> {},
                System::nanoTime,
                1024,
                System::currentTimeMillis,
                new GatewayControl.Restart(
                        restart.sessionsFile(), restart.sessions(), TimeUnit.SECONDS.toNanos(10), restart.loaded()));
        this.control.servePaper();
    }

    /** Starts a paper gateway for {@code venue}, unlimited. */
    public static PaperGatewayFixture start(final String name, final int ingressPort, final Venue venue)
            throws Exception {
        return start(name, ingressPort, venue, null, AccountLane.Caps.NONE);
    }

    /** Starts a paper gateway for {@code venue}: the simulated account, then egress, ingress and control. */
    public static PaperGatewayFixture start(
            final String name,
            final int ingressPort,
            final Venue venue,
            final VenueRateLimits limits,
            final AccountLane.Caps caps)
            throws Exception {
        final PaperGatewayFixture gateway = new PaperGatewayFixture(name, ingressPort, venue, limits, caps);
        gateway.resender.scheduleWithFixedDelay(gateway::resend, 20, 20, TimeUnit.MILLISECONDS);
        gateway.account.start();
        gateway.run(gateway.egress);
        gateway.run(gateway.ingress);
        gateway.run(gateway.control);
        return gateway;
    }

    /** A simulated exchange with no latency, whose queue model fills a resting order's place at once. */
    private static MbpSimulatedExchange exchange() {
        final QueueModel queue = (prev, next, orders) -> {};
        return new MbpSimulatedExchange(
                (nowNanos, buy, price, quantity, maker, state) -> 0L, () -> 0L, () -> 0L, queue);
    }

    private void resend() {
        for (final Map.Entry<Book, long[]> quote : this.quotes.entrySet()) {
            final long[] top = quote.getValue();
            writeQuote(quote.getKey(), top[0], top[1], top[2], top[3]);
        }
    }

    private int streamOf(final Listing listing) {
        for (final Book book : this.venue.books()) {
            if (book.listing().listingId() == listing.listingId()) {
                return book.streamId();
            }
        }
        throw new IllegalArgumentException("No book for listing " + listing.listingId());
    }

    private void run(final GnomeAgent agent) {
        final GnomeAgentRunner runner = new GnomeAgentRunner(agent, Throwable::printStackTrace);
        GnomeAgentRunner.startOnThread(runner);
        this.runners.add(runner);
    }

    /**
     * Writes a book's top of book, as a shard's record of it, and keeps writing it again (every 20 ms) as a shard
     * re-sends a quiet stream's last record: a session's books join the lane on its own thread, so a record written
     * before they did never reached them.
     */
    public void quote(final Book book, final long bid, final long bidSize, final long ask, final long askSize) {
        this.quotes.put(book, new long[] {bid, bidSize, ask, askSize});
        writeQuote(book, bid, bidSize, ask, askSize);
    }

    private synchronized void writeQuote(
            final Book book, final long bid, final long bidSize, final long ask, final long askSize) {
        this.record.encoder.action(Action.Add);
        this.record.encoder.side(Side.None);
        this.record.encoder.price(Mbp10Decoder.priceNullValue());
        this.record.encoder.size(Mbp10Decoder.sizeNullValue());
        this.record.encoder.bidPrice0(bid).bidSize0(bidSize).askPrice0(ask).askSize0(askSize);
        emptyBelowTop();
        publish(book);
    }

    /** Writes a trade on a book: {@code aggressor} took {@code size} at {@code price}. */
    public synchronized void trade(final Book book, final Side aggressor, final long price, final long size) {
        this.record.encoder.action(Action.Trade);
        this.record.encoder.side(aggressor);
        this.record.encoder.price(price);
        this.record.encoder.size(size);
        this.record.encoder.bidPrice0(Mbp10Decoder.priceNullValue()).bidSize0(Mbp10Decoder.sizeNullValue());
        this.record.encoder.askPrice0(Mbp10Decoder.priceNullValue()).askSize0(Mbp10Decoder.sizeNullValue());
        emptyBelowTop();
        publish(book);
    }

    private void publish(final Book book) {
        final int header = SbeMessage.HEADER_LENGTH;
        this.record.buffer.putInt(header + StreamHeader.STREAM_ID_OFFSET, book.streamId());
        this.record.buffer.putLong(
                header + StreamHeader.TIMESTAMP_RECV_OFFSET, System.currentTimeMillis() * 1_000_000L);
        this.record.encoder.flags().clear();
        this.marketData.publishRaw(this.record.buffer, Mbp10Decoder.TEMPLATE_ID, this.record.totalMessageSize());
    }

    private void emptyBelowTop() {
        final long px = Mbp10Decoder.priceNullValue();
        final long sz = Mbp10Decoder.sizeNullValue();
        this.record.encoder.bidPrice1(px).bidSize1(sz).askPrice1(px).askSize1(sz);
        this.record.encoder.bidPrice2(px).bidSize2(sz).askPrice2(px).askSize2(sz);
        this.record.encoder.bidPrice3(px).bidSize3(sz).askPrice3(px).askSize3(sz);
        this.record.encoder.bidPrice4(px).bidSize4(sz).askPrice4(px).askSize4(sz);
        this.record.encoder.bidPrice5(px).bidSize5(sz).askPrice5(px).askSize5(sz);
        this.record.encoder.bidPrice6(px).bidSize6(sz).askPrice6(px).askSize6(sz);
        this.record.encoder.bidPrice7(px).bidSize7(sz).askPrice7(px).askSize7(sz);
        this.record.encoder.bidPrice8(px).bidSize8(sz).askPrice8(px).askSize8(sz);
        this.record.encoder.bidPrice9(px).bidSize9(sz).askPrice9(px).askSize9(sz);
    }

    public boolean linked(final long slot) {
        final GatewaySession session = this.sessions.bySlot(slot);
        return session != null && session.state == GatewaySession.ACTIVE;
    }

    /** Orders working on the simulated venue, across every session. */
    public int workingOrders() {
        return this.account.workingOrders();
    }

    /** Stops every thread as a crash would: nothing is cancelled and no session told. Once; again does nothing. */
    public void kill() throws Exception {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        this.resender.shutdownNow();
        for (int i = this.runners.size() - 1; i >= 0; i--) {
            this.runners.get(i).close();
        }
        this.account.close();
        this.logs.forEach(OwnershipLog::close);
        this.aeron.close();
    }

    @Override
    public void close() throws Exception {
        kill();
    }
}
