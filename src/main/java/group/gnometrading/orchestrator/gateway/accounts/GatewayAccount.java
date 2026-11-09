package group.gnometrading.orchestrator.gateway.accounts;

import group.gnometrading.account.VenueAccount;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.AccountConnection;
import group.gnometrading.gateways.account.reader.AccountReader;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.account.reader.AccountState;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.gateways.ratelimit.AccountBudget;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.orchestrator.gateway.core.AccountLink;
import group.gnometrading.orchestrator.gateway.core.CrossKey;
import group.gnometrading.orchestrator.gateway.core.CrossKeys;
import group.gnometrading.orchestrator.runtime.GatewayErrorHandler;
import group.gnometrading.orchestrator.runtime.GatewayRunners;
import group.gnometrading.orchestrator.runtime.ProcessExit;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.fee.FeeTimeline;
import group.gnometrading.utils.CauseChain;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.agrona.ErrorHandler;

/**
 * One venue account an order gateway holds, and every thread it runs on: the lane sending every session's orders on its
 * own HTTP connections, the connector keeping those open, the account's event socket (reader, writer and supervisor),
 * and the venue's heartbeat keeper where it has one. Sessions' commands go in on {@link #commands()}; every report, tagged
 * with its session's slot, comes out on {@link #reports()}.
 *
 * <p>A lane or connector that fails stops the process: orders would sit unsent or unanswered with nothing to notice. The
 * event socket reconnects itself, and gives up the process only when it can't connect at all or fails too often.
 */
public final class GatewayAccount implements AccountLink, AutoCloseable {

    private final VenueAccount account;
    private final String name;
    private final Logger logger;
    private final ProcessExit exit;
    private final AccountLane<?> lane;
    private final AccountBudget budget;
    private final AccountReader reader;
    private final AccountConnection<?> connection;
    private final AsyncHTTPConnections connections;
    private final HTTPConnector connector;
    private final ManyToOneRingBuffer<LaneCommand> commands;
    private final ManyToOneRingBuffer<AccountReport> reports;
    private final Markets markets;
    private final CrossKeys crossKeys;
    private final VenueOrderQuery orderQuery;
    private final GnomeAgent heartbeats;
    private final AsyncHTTPConnections heartbeatConnections;
    private final List<GnomeAgentRunner> runners = new ArrayList<>();
    private volatile GatewayRunners socket;
    private volatile GnomeAgentRunner laneRunner;

    public GatewayAccount(final Parts parts) {
        this.account = parts.account;
        this.name = parts.name;
        this.logger = parts.logger;
        this.exit = parts.exit;
        this.lane = parts.lane;
        this.budget = parts.budget;
        this.reader = parts.reader;
        this.connection = parts.connection;
        this.connections = parts.connections;
        this.connector = parts.connector;
        this.commands = parts.commands;
        this.reports = parts.reports;
        this.markets = published(parts.markets, parts.crossKeys);
        this.crossKeys = parts.crossKeys;
        this.orderQuery = parts.orderQuery;
        this.heartbeats = parts.heartbeats;
        this.heartbeatConnections = parts.heartbeatConnections;
    }

    /** Everything an account is built from; filled in by {@link VenueAccounts}. */
    public static final class Parts {
        public VenueAccount account;
        public String name;
        public Logger logger;
        public ProcessExit exit;
        public AccountLane<?> lane;
        // The budget the lane and every client beside it draw on.
        public AccountBudget budget;
        public AccountReader reader;
        public AccountConnection<?> connection;
        public AsyncHTTPConnections connections;
        public HTTPConnector connector;
        public ManyToOneRingBuffer<LaneCommand> commands;
        public ManyToOneRingBuffer<AccountReport> reports;
        public Markets markets;
        public VenueOrderQuery orderQuery;
        public GnomeAgent heartbeats;
        public AsyncHTTPConnections heartbeatConnections;
        // Where each listing loaded on the account publishes its cross key; every account of the gateway shares one.
        public CrossKeys crossKeys;
    }

    /** The venue's markets, each listing's cross key published as it loads, for the self-trade check. */
    private static Markets published(final Markets venue, final CrossKeys crossKeys) {
        if (crossKeys == null) {
            return venue;
        }
        return new Markets() {
            @Override
            public CrossKey load(final Listing listing, final FeeTimeline fees) throws IOException {
                final CrossKey key = venue.load(listing, fees);
                crossKeys.put(
                        PublishedMap.listingKey(
                                listing.exchange().exchangeId(),
                                listing.security().securityId()),
                        key);
                return key;
            }

            @Override
            public void addSession(final long slot, final String sessionId) {
                venue.addSession(slot, sessionId);
            }

            @Override
            public void removeSession(final long slot) {
                venue.removeSession(slot);
            }
        };
    }

    /** Starts every thread: the socket first, so the account catches up while the lane's connections open. */
    public void start() {
        final ErrorHandler socketErrors =
                // The connection reconnects a socket its reader stopped on by itself; this only gives up on one that
                // can't connect or keeps failing.
                new GatewayErrorHandler(this.logger, this.name + " events", () -> {}, this.exit);
        this.socket = GatewayRunners.venue(
                        this.account.accountId(),
                        this.connection.pauseControl(),
                        new GnomeAgentRunner(this.connection.reader(), socketErrors),
                        new GnomeAgentRunner(this.connection.supervisor(), socketErrors))
                .start();
        start(this.connection.writer(), socketErrors);
        start(this.connector, fatal("connector"));
        this.laneRunner = start(this.lane, fatal("lane"));
        if (this.heartbeats != null) {
            start(this.heartbeats, fatal("heartbeats"));
        }
    }

    private GnomeAgentRunner start(final GnomeAgent agent, final ErrorHandler errors) {
        final GnomeAgentRunner runner = new GnomeAgentRunner(agent, errors);
        GnomeAgentRunner.startOnThread(runner);
        this.runners.add(runner);
        return runner;
    }

    private ErrorHandler fatal(final String what) {
        return error -> {
            this.logger.logf(LogMessage.FATAL_ERROR_EXITING, "%s %s failed: %s", this.name, what, CauseChain.of(error));
            this.exit.exit(1, error);
        };
    }

    public VenueAccount account() {
        return this.account;
    }

    @Override
    public int accountId() {
        return this.account.accountId();
    }

    @Override
    public int exchangeId() {
        return this.account.exchangeId();
    }

    public String name() {
        return this.name;
    }

    /** Where sessions' commands for the account go; any thread may write. */
    @Override
    public ManyToOneRingBuffer<LaneCommand> commands() {
        return this.commands;
    }

    /** Every report on the account, each tagged with its session's slot; one reader. */
    public ManyToOneRingBuffer<AccountReport> reports() {
        return this.reports;
    }

    @Override
    public VenueOrderQuery orderQuery() {
        return this.orderQuery;
    }

    @Override
    public int workingOrders() {
        return this.lane.workingOrders();
    }

    @Override
    public Markets markets() {
        return this.markets;
    }

    /** Where the account's listings publish their cross keys; null if it was built without. */
    public CrossKeys crossKeys() {
        return this.crossKeys;
    }

    public AccountLane<?> lane() {
        return this.lane;
    }

    /** Times the venue answered 429 to a request on the account's budget, which it never should. Any thread. */
    public long venueRefusals() {
        return this.budget.venueRefusals();
    }

    /** What the account's budget had sent before each of the venue's first 429s. Allocates; any thread. */
    public String describeRefusals() {
        return this.budget.describeRefusals();
    }

    public AccountReader reader() {
        return this.reader;
    }

    @Override
    public AccountState state() {
        return this.connection.state();
    }

    /** The event socket reader's passes so far; 0 before it starts. Any thread. */
    public long readerPasses() {
        final GatewayRunners socket = this.socket;
        return socket == null ? 0 : socket.runners().get(0).cycles();
    }

    /** Whether the reader is held on purpose while its socket reconnects, so its passes stop. */
    public boolean readerPaused() {
        return this.connection.pauseControl().isPauseRequested();
    }

    /** The lane's passes so far, idle or not; 0 before it starts. Any thread. */
    public long lanePasses() {
        final GnomeAgentRunner runner = this.laneRunner;
        return runner == null ? 0 : runner.cycles();
    }

    /**
     * Stops every thread: the heartbeat keeper and the lane first, so nothing new is sent and the venue cancels what is
     * left once the beats stop; then the connector and the socket, reader before supervisor.
     */
    @Override
    public void close() {
        for (int i = this.runners.size() - 1; i >= 0; i--) {
            closeQuietly(this.runners.get(i));
        }
        if (this.socket != null) {
            try {
                this.socket.close();
            } catch (Exception e) {
                this.logger.logf(LogMessage.UNKNOWN_ERROR, "%s: failed to stop its socket: %s", this.name, e);
            }
        }
        this.connections.close();
        if (this.heartbeatConnections != null) {
            this.heartbeatConnections.close();
        }
    }

    private void closeQuietly(final GnomeAgentRunner runner) {
        try {
            runner.close();
        } catch (Exception e) {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "%s: failed to stop %s: %s",
                    this.name,
                    runner.getAgent().roleName(),
                    e);
        }
    }
}
