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
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.md.Stream;
import group.gnometrading.orchestrator.gateway.core.AccountLink;
import group.gnometrading.orchestrator.gateway.core.CrossKey;
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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
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

    private static final int COMMAND_QUEUE = 4096;
    private static final int REPORT_QUEUE = 8192;
    private static final int HANDOFFS = 1024;

    /** Where the gateway reads the streams a session's books need, through the session's own lease. */
    public interface Streams {
        void addSession(String sessionId, int[] streamIds);

        void removeSession(String sessionId);
    }

    private final Logger logger;
    private final int exchangeId;
    private final String name;
    private final VenueRateLimits limits;
    private final SecurityMaster securityMaster;
    private final Streams streams;
    private final LongSupplier nanoClock;
    private final ManyToOneRingBuffer<LaneCommand> commands;
    private final ManyToOneRingBuffer<AccountReport> reports;
    private final ManyToOneConcurrentArrayQueue<Object> handoffs = new ManyToOneConcurrentArrayQueue<>(HANDOFFS);
    private final SimulatedLane lane;
    private final ConcurrentHashMap<Long, String> sessionIds = new ConcurrentHashMap<>();
    private final Markets markets = new SimulatedMarkets();
    private GnomeAgentRunner runner;

    /**
     * @param limits the venue's order-entry limits, each session getting them whole; null where the venue has no order
     *     entry of ours, so nothing is limited
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
            final SecurityMaster securityMaster,
            final SequencedRingBuffer<?> marketData,
            final Streams streams,
            final LongSupplier nanoClock,
            final EpochNanoClock wallClock) {
        this.logger = logger;
        this.exchangeId = exchangeId;
        this.name = name;
        this.limits = limits;
        this.securityMaster = securityMaster;
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

    /** Passes the lane has made, for the watchdog: still counting means it isn't stuck. */
    public long lanePasses() {
        return this.lane.passes();
    }

    /** What the last minute looked like, for the gateway's minute line. */
    public String describeMinute() {
        return String.format(
                "%d orders working, %d sessions, books' worst lag %d ms, %d new orders refused for a book without data",
                this.lane.workingOrders(),
                this.sessionIds.size(),
                this.lane.takeLagMaxNanos() / 1_000_000,
                this.lane.refusedNotReady());
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
                final SimulationResolver resolver = new SimulationResolver(securityMaster, simulation.asOf);
                final long baseSeed = simulation.seed != null ? simulation.seed : DEFAULT_SEED;
                for (final Listing listing : listings) {
                    if (listing.exchange().exchangeId() != exchangeId) {
                        continue;
                    }
                    final ExchangeSimulationConfig config =
                            resolver.resolve(listing, false, simulation.layersFor(listing));
                    final MbpSimulatedExchange exchange = (MbpSimulatedExchange) config.toSimulatedExchange(
                            listing.listingId(),
                            LatencySeeds.derive(baseSeed, listing.listingId()),
                            resolver.registryFees());
                    final Stream stream = securityMaster.streamForListing(listing.listingId());
                    if (stream == null) {
                        throw new IllegalStateException("Listing " + listing.listingId() + " has no book stream");
                    }
                    books.add(new SimulatedBook(
                            slot,
                            listingKey(exchangeId, listing.security().securityId()),
                            listing.listingId(),
                            stream.streamId(),
                            exchange,
                            sessionId));
                    streamIds.add(stream.streamId());
                    logger.logf(
                            LogMessage.DEBUG,
                            "Gateway %s: session %s's listing %d simulates as of %s with %s",
                            name,
                            sessionId,
                            listing.listingId(),
                            simulation.asOf,
                            config.toJson());
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
            final AccountBudget budget = limits == null ? null : new AccountBudget(limits, nanoClock.getAsLong());
            hand(new SimulatedLane.AddSession(new SimulatedSession(slot, sessionId, budget, books)));
            sessionIds.put(slot, sessionId);
            streams.addSession(
                    sessionId, streamIds.stream().mapToInt(Integer::intValue).toArray());
        }

        @Override
        public void removeSession(final long slot) {
            final String sessionId = sessionIds.remove(slot);
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
