package group.gnometrading.orchestrator.gateway.simulated;

import group.gnometrading.SecurityMaster;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.account.reader.AccountState;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.gateways.ratelimit.AccountBudget;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.latency.MinuteHistograms;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.md.Stream;
import group.gnometrading.metrics.MetricCounters;
import group.gnometrading.orchestrator.gateway.core.AccountLink;
import group.gnometrading.orchestrator.gateway.core.CrossKey;
import group.gnometrading.orchestrator.gateway.core.OrderTimings;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.simulation.config.ExchangeSimulationConfig;
import group.gnometrading.simulation.config.SessionSimulationConfig;
import group.gnometrading.simulation.config.SimulationResolver;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
import group.gnometrading.simulation.latency.LatencySeeds;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.fee.FeeTimeline;
import group.gnometrading.utils.CauseChain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

/**
 * The one account a paper gateway holds: every paper session on its venue trades on it, each against books of its own
 * (no paper order meets another session's), with its own budget as if alone. The venue is gnome-simulation's, built
 * for each session from its simulation as its launch pinned it, exactly as the session built it itself before; its
 * books are fed from the shards through the sessions' own leases.
 */
public final class SimulatedAccount implements AccountLink, AutoCloseable {

    /** A paper session holds no venue account: hellos name this one. */
    public static final int ACCOUNT_ID = 0;

    /** The seed a session's simulation uses when its config names none, as a session's own simulation did. */
    static final long DEFAULT_SEED = 0x9E3779B97F4A7C15L;

    // A budget starts drained as of when it is made, right for a live gateway that can't know what its account just
    // spent; a paper session joins as if onto a quiet account, so its budget is made as of long enough ago that every
    // bucket has refilled (venues refill theirs in seconds).
    private static final long FULL_BUDGET_NANOS = TimeUnit.HOURS.toNanos(1);
    private static final int COMMAND_QUEUE = 4096;
    private static final int REPORT_QUEUE = 8192;
    private static final int HANDOFFS = 1024;

    /** What one listing simulates as for a session, and the stream its book reads. */
    public record Simulated(MbpSimulatedExchange exchange, int streamId) {}

    /** Builds a session's simulated exchange for one of its listings, from its simulation as its launch pinned it. */
    @FunctionalInterface
    public interface Simulations {
        Simulated simulate(String sessionId, Listing listing, SessionSimulationConfig simulation) throws Exception;
    }

    /**
     * Each listing as the session's own simulation built it: the exchange's (and listing's) registry parameters as of
     * the simulation's {@code as_of}, under its layers, seeded per listing; the listing's book stream from the security
     * master.
     */
    public static Simulations fromRegistry(
            final SecurityMaster securityMaster, final Logger logger, final String name) {
        return (sessionId, listing, simulation) -> {
            final SimulationResolver resolver = new SimulationResolver(securityMaster, simulation.asOf);
            final long baseSeed = simulation.seed != null ? simulation.seed : DEFAULT_SEED;
            final ExchangeSimulationConfig config = resolver.resolve(listing, false, simulation.layersFor(listing));
            final MbpSimulatedExchange exchange = (MbpSimulatedExchange) config.toSimulatedExchange(
                    listing.listingId(), LatencySeeds.derive(baseSeed, listing.listingId()), resolver.registryFees());
            final Stream stream = securityMaster.streamForListing(listing.listingId());
            if (stream == null) {
                throw new IllegalStateException("Listing " + listing.listingId() + " has no book stream");
            }
            logger.logf(
                    LogMessage.DEBUG,
                    "Gateway %s: session %s's listing %d simulates as of %s with %s",
                    name,
                    sessionId,
                    listing.listingId(),
                    simulation.asOf,
                    config.toJson());
            return new Simulated(exchange, stream.streamId());
        };
    }

    /**
     * The most a paper gateway takes, from what one host was measured to carry: books (a session's listing each,
     * which hold the heap) and distinct streams (which its shard reader reads every pass). A session new to it that would
     * take either past its cap is refused before its books are built, so a full gateway never runs out of memory and
     * drops every session it holds.
     *
     * @param streamOf a listing's book stream, 0 if it has none
     */
    public record Capacity(int maxBooks, int maxStreams, ToIntFunction<Listing> streamOf) {
        public static final Capacity UNLIMITED = new Capacity(Integer.MAX_VALUE, Integer.MAX_VALUE, listing -> 0);

        /** A listing's book stream as the security master has it (cached there once read). */
        public static ToIntFunction<Listing> streamsFrom(final SecurityMaster securityMaster) {
            return listing -> {
                final Stream stream = securityMaster.streamForListing(listing.listingId());
                return stream == null ? 0 : stream.streamId();
            };
        }
    }

    /** What the gateway carries against its caps, for its endpoint item; read from any thread. */
    public record Load(int books, int streams, long recordsPerSecond, int maxBooks, int maxStreams) {}

    /** Where the gateway reads the streams a session's books need, through the session's own lease. */
    public interface Streams {
        void addSession(String sessionId, int[] streamIds);

        void removeSession(String sessionId);
    }

    private final Logger logger;
    private final int exchangeId;
    private final String name;
    private final VenueRateLimits limits;
    private final Simulations simulations;
    private final Capacity capacity;
    private final Streams streams;
    private final LongSupplier nanoClock;
    private final ManyToOneRingBuffer<LaneCommand> commands;
    private final ManyToOneRingBuffer<AccountReport> reports;
    private final ManyToOneConcurrentArrayQueue<Object> handoffs = new ManyToOneConcurrentArrayQueue<>(HANDOFFS);
    private final SimulatedLane lane;
    private final ConcurrentHashMap<Long, String> sessionIds = new ConcurrentHashMap<>();
    private final Markets markets = new SimulatedMarkets();
    // On the control thread, which adds and removes sessions: each session's book streams, and how many of its books
    // read each stream. Published for other threads as the two counts below.
    private final Map<Long, int[]> streamsBySlot = new HashMap<>();
    private final Map<Integer, Integer> booksByStream = new HashMap<>();
    private volatile int books;
    private volatile int distinctStreams;
    // New sessions refused for room since the gateway started; written on the control thread.
    private volatile long refusedForRoom;
    // On the thread taking each minute.
    private long recordsAtMinute;
    private long lateAtMinute;
    private long laggedAtMinute;
    private long minuteStartedNanos;
    private volatile long recordsPerSecond;
    private GnomeAgentRunner runner;

    /**
     * @param limits the venue's order-entry limits, each session getting them whole; null where the venue has no order
     *     entry of ours, so nothing is limited
     * @param simulations builds each session's books
     * @param capacity the most books and distinct streams it takes
     * @param marketData where the shard reader writes every stream the books read
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public SimulatedAccount(
            final Logger logger,
            final int exchangeId,
            final String name,
            final VenueRateLimits limits,
            final AccountLane.Caps caps,
            final long defaultMaxQueueNanos,
            final int maxOrders,
            final Simulations simulations,
            final Capacity capacity,
            final SequencedRingBuffer<?> marketData,
            final Streams streams,
            final LongSupplier nanoClock,
            final EpochNanoClock wallClock) {
        this.logger = logger;
        this.exchangeId = exchangeId;
        this.name = name;
        this.limits = limits;
        this.simulations = simulations;
        this.capacity = capacity;
        this.streams = streams;
        this.nanoClock = nanoClock;
        this.commands = new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, COMMAND_QUEUE);
        this.reports = new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, REPORT_QUEUE);
        this.lane = new SimulatedLane(
                this.commands,
                this.reports,
                this.handoffs,
                marketData,
                maxOrders,
                caps,
                defaultMaxQueueNanos,
                nanoClock,
                wallClock);
        this.minuteStartedNanos = nanoClock.getAsLong();
    }

    /** Where each paper request's timing goes, for its session; set before {@link #start}. */
    public void timings(final OrderTimings timings) {
        this.lane.timings(timings);
    }

    public void start() {
        this.runner = new GnomeAgentRunner(
                this.lane,
                error -> this.logger.logf(
                        LogMessage.FATAL_ERROR_EXITING,
                        "Gateway %s: simulated lane failed: %s",
                        this.name,
                        CauseChain.of(error)));
        GnomeAgentRunner.startOnThread(this.runner);
    }

    @Override
    public void close() throws Exception {
        if (this.runner != null) {
            this.runner.close();
        }
    }

    public String name() {
        return this.name;
    }

    public ManyToOneRingBuffer<AccountReport> reports() {
        return this.reports;
    }

    /** The lane's loop counts, for the gateway's samples. */
    public MetricCounters laneCounters() {
        return this.lane.counters();
    }

    /** The lane's latency histograms, sample kind {@link #LATENCY_KIND}. */
    public MinuteHistograms laneLatency() {
        return this.lane.latency();
    }

    public static final String LATENCY_KIND = SimulatedLane.LATENCY_KIND;

    /** The lane's runner once started, for the gateway's thread samples; null before. */
    public GnomeAgentRunner runner() {
        return this.runner;
    }

    /** Passes the lane has made, for the watchdog: still counting means it isn't stuck. */
    public long lanePasses() {
        return this.lane.passes();
    }

    /**
     * The simulated venue's last minute.
     *
     * @param lagMaxMillis the oldest new record any book took, from the venue's receipt
     * @param refusedNotReady new orders refused for a book without data, since the gateway started
     * @param refusedForRoom new sessions refused because they'd take it past its caps, since the gateway started
     * @param lateShare the share of records new to their stream that reached the books later than
     *     {@link SimulatedLane#LATE_NANOS} after the venue's receipt: over 1%, the books' lag p99 is past it
     */
    public record Minute(
            int workingOrders,
            int sessions,
            long lagMaxMillis,
            long refusedNotReady,
            int books,
            int streams,
            long recordsPerSecond,
            double lateShare,
            long refusedForRoom) {
        /** As the gateway's minute line gives it. */
        public String describe() {
            return String.format(
                    "%d orders working, %d sessions, %d books on %d streams, %d records/s, books' worst lag %d ms"
                            + " (%.2f%% late), %d new orders refused for a book without data, %d sessions refused for"
                            + " room",
                    this.workingOrders,
                    this.sessions,
                    this.books,
                    this.streams,
                    this.recordsPerSecond,
                    this.lagMaxMillis,
                    100 * this.lateShare,
                    this.refusedNotReady,
                    this.refusedForRoom);
        }
    }

    /** What the minute since the last call looked like; once a minute, from one thread. */
    public Minute takeMinute() {
        final long now = this.nanoClock.getAsLong();
        final long records = this.lane.recordsTaken();
        final long late = this.lane.late();
        final long lagged = this.lane.lagged();
        final long elapsedNanos = Math.max(1, now - this.minuteStartedNanos);
        this.recordsPerSecond = (records - this.recordsAtMinute) * TimeUnit.SECONDS.toNanos(1) / elapsedNanos;
        final long laggedInMinute = lagged - this.laggedAtMinute;
        final double lateShare = laggedInMinute == 0 ? 0 : (double) (late - this.lateAtMinute) / laggedInMinute;
        this.recordsAtMinute = records;
        this.lateAtMinute = late;
        this.laggedAtMinute = lagged;
        this.minuteStartedNanos = now;
        return new Minute(
                this.lane.workingOrders(),
                this.sessionIds.size(),
                this.lane.takeLagMaxNanos() / 1_000_000,
                this.lane.refusedNotReady(),
                this.books,
                this.distinctStreams,
                this.recordsPerSecond,
                lateShare,
                this.refusedForRoom);
    }

    /** Its load against its caps, records/s as of the last minute taken. */
    public Load load() {
        return new Load(
                this.books,
                this.distinctStreams,
                this.recordsPerSecond,
                this.capacity.maxBooks(),
                this.capacity.maxStreams());
    }

    @Override
    public int accountId() {
        return ACCOUNT_ID;
    }

    @Override
    public int exchangeId() {
        return this.exchangeId;
    }

    @Override
    public ManyToOneRingBuffer<LaneCommand> commands() {
        return this.commands;
    }

    @Override
    public AccountState state() {
        return AccountState.READY;
    }

    @Override
    public int workingOrders() {
        return this.lane.workingOrders();
    }

    @Override
    public Markets markets() {
        return this.markets;
    }

    /** Paper sessions recover from their ledger alone: the simulated venue never outlives a process. */
    @Override
    public VenueOrderQuery orderQuery() {
        return null;
    }

    static long listingKey(final int exchangeId, final long securityId) {
        return ((long) exchangeId << 32) | (securityId & 0xFFFF_FFFFL);
    }

    private final class SimulatedMarkets implements Markets {
        @Override
        public CrossKey load(final Listing listing, final FeeTimeline fees) {
            // Nothing to load at a venue, and nothing to check across sessions: each trades against its own books.
            return null;
        }

        @Override
        public boolean roomFor(final List<Listing> listings) {
            int newBooks = 0;
            final Set<Integer> newStreams = new HashSet<>();
            for (final Listing listing : listings) {
                if (listing.exchange().exchangeId() != exchangeId) {
                    continue;
                }
                newBooks++;
                final int streamId = capacity.streamOf().applyAsInt(listing);
                if (streamId != 0 && !booksByStream.containsKey(streamId)) {
                    newStreams.add(streamId);
                }
            }
            final boolean room = books + newBooks <= capacity.maxBooks()
                    && distinctStreams + newStreams.size() <= capacity.maxStreams();
            if (!room) {
                refusedForRoom = refusedForRoom + 1;
            }
            return room;
        }

        @Override
        public String describeLoad() {
            return String.format(
                    "%d of %d books, %d of %d streams",
                    books, capacity.maxBooks(), distinctStreams, capacity.maxStreams());
        }

        @Override
        public void addPaperSession(
                final long slot, final String sessionId, final List<Listing> listings, final String simulationJson) {
            if (sessionIds.containsKey(slot)) {
                // Back after a dropped link: its books, and the orders resting on them, carry on as they were.
                return;
            }
            final List<SimulatedBook> books = new ArrayList<>();
            final List<Integer> streamIds = new ArrayList<>();
            try {
                final SessionSimulationConfig simulation = SessionSimulationConfig.fromJson(simulationJson);
                for (final Listing listing : listings) {
                    if (listing.exchange().exchangeId() != exchangeId) {
                        continue;
                    }
                    final Simulated simulated = simulations.simulate(sessionId, listing, simulation);
                    books.add(new SimulatedBook(
                            slot,
                            listingKey(exchangeId, listing.security().securityId()),
                            listing.listingId(),
                            simulated.streamId(),
                            simulated.exchange(),
                            sessionId));
                    streamIds.add(simulated.streamId());
                }
            } catch (Exception e) {
                // Its new orders are refused for want of a book: nothing it sends can trade on a venue never built.
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Gateway %s: can't simulate session %s's venue: %s",
                        name,
                        sessionId,
                        CauseChain.of(e));
                return;
            }
            final AccountBudget budget =
                    limits == null ? null : new AccountBudget(limits, nanoClock.getAsLong() - FULL_BUDGET_NANOS);
            hand(new SimulatedLane.AddSession(new SimulatedSession(slot, sessionId, budget, books)));
            sessionIds.put(slot, sessionId);
            final int[] ids = streamIds.stream().mapToInt(Integer::intValue).toArray();
            count(slot, ids);
            streams.addSession(sessionId, ids);
        }

        /** A session's books, one per stream id it reads, now counted against the caps. */
        private void count(final long slot, final int[] ids) {
            streamsBySlot.put(slot, ids);
            for (final int id : ids) {
                booksByStream.merge(id, 1, Integer::sum);
            }
            SimulatedAccount.this.books = SimulatedAccount.this.books + ids.length;
            distinctStreams = booksByStream.size();
        }

        private void uncount(final long slot) {
            final int[] ids = streamsBySlot.remove(slot);
            if (ids == null) {
                return;
            }
            for (final int id : ids) {
                booksByStream.computeIfPresent(id, (stream, held) -> held == 1 ? null : held - 1);
            }
            SimulatedAccount.this.books = SimulatedAccount.this.books - ids.length;
            distinctStreams = booksByStream.size();
        }

        @Override
        public void removeSession(final long slot) {
            final String sessionId = sessionIds.remove(slot);
            uncount(slot);
            hand(new SimulatedLane.RemoveSession(slot));
            if (sessionId != null) {
                streams.removeSession(sessionId);
            }
        }

        private void hand(final Object handoff) {
            if (!handoffs.offer(handoff)) {
                throw new IllegalStateException("Too many paper sessions waiting for the simulated lane");
            }
        }
    }
}
