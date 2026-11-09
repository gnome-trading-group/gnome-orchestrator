package group.gnometrading.orchestrator.gateway;

import group.gnometrading.account.VenueAccount;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.FakeVenueServer;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.lane.TestLaneVenue;
import group.gnometrading.gateways.account.reader.AccountConnection;
import group.gnometrading.gateways.account.reader.AccountReader;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.account.reader.FakeAccountTransport;
import group.gnometrading.gateways.account.reader.TextAccountProtocol;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.ratelimit.AccountBudget;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.networking.http.HTTPMethod;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.AeronStreamIds;
import group.gnometrading.orchestrator.gateway.accounts.GatewayAccount;
import group.gnometrading.orchestrator.gateway.accounts.LaneAdmissions;
import group.gnometrading.orchestrator.gateway.control.GatewayControl;
import group.gnometrading.orchestrator.gateway.control.SessionRegistry;
import group.gnometrading.orchestrator.gateway.core.GatewayMessage;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.GatewayStatus;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import group.gnometrading.orchestrator.gateway.journal.GatewayJournal;
import group.gnometrading.orchestrator.gateway.journal.OwnershipLog;
import group.gnometrading.orchestrator.gateway.transport.GatewayEgress;
import group.gnometrading.orchestrator.gateway.transport.GatewayIngress;
import group.gnometrading.orchestrator.gateway.transport.GatewayRejects;
import group.gnometrading.schemas.gateway.AdmissionAction;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.fee.FeeTimeline;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;

/**
 * A whole order gateway in this process, for tests: one account on a fake venue (an HTTP server for the lane's
 * requests, a scripted event socket), taking sessions over Aeron on {@code ingressPort}, with an in-memory registry.
 * {@link #kill()} stops it the way a crash would (no planned stop); a new fixture over the same venue, socket and state
 * directory then restarts from what the first left on disk.
 */
public final class GatewayFixture implements AutoCloseable {

    public static final int ACCOUNT_ID = 5;
    public static final int EXCHANGE_ID = TestLaneVenue.EXCHANGE_ID;
    public static final long SECURITY_ID = TestLaneVenue.SECURITY_ID;
    public static final int LISTING_ID = 70;
    private static final int MAX_ORDERS = 256;

    /** The registry a gateway checks hellos against: running sessions, each on one listing and bound to one account. */
    public static final class Registry {
        final Map<String, SessionRegistry.SessionRecord> records = new ConcurrentHashMap<>();

        public void register(
                final String sessionId,
                final long slot,
                final int strategyId,
                final int exchangeId,
                final int listingId,
                final int accountId) {
            register(sessionId, slot, strategyId, exchangeId, listingId, accountId, 1);
        }

        /** @param rateWeight its strategy's weight in its share of the account's request budget */
        public void register(
                final String sessionId,
                final long slot,
                final int strategyId,
                final int exchangeId,
                final int listingId,
                final int accountId,
                final int rateWeight) {
            this.records.put(
                    sessionId,
                    new SessionRegistry.SessionRecord(
                            slot,
                            "RUNNING",
                            strategyId,
                            "live",
                            Set.of(listingId),
                            Map.of(exchangeId, accountId),
                            rateWeight));
        }
    }

    /** What outlives a gateway process: the venue, its account's socket, the host's state directory, the registry. */
    public record Venue(
            FakeVenueServer server,
            FakeAccountTransport transport,
            TextAccountProtocol protocol,
            Path stateDirectory,
            Registry registry,
            FakeOrderQuery orders) {

        public static Venue start(final Path stateDirectory) throws Exception {
            final FakeVenueServer server = new FakeVenueServer();
            // Every submit is taken, its venue id being its body; cancels and amends are taken.
            server.script(request -> request.method().equals("POST")
                    ? FakeVenueServer.Reply.ok("v-" + request.body())
                    : FakeVenueServer.Reply.ok(""));
            return new Venue(
                    server,
                    new FakeAccountTransport(),
                    new TextAccountProtocol(),
                    stateDirectory,
                    new Registry(),
                    new FakeOrderQuery());
        }

        /** A running session as the registry has it: on the fixture's listing, bound to its account. */
        public void register(final String sessionId, final long slot, final int strategyId) {
            this.registry.register(sessionId, slot, strategyId, EXCHANGE_ID, LISTING_ID, ACCOUNT_ID);
        }
    }

    private final AeronRuntime aeron;
    private final GatewayAccount account;
    private final GatewayControl control;
    private final GatewayIngress ingress;
    private final GatewayEgress egress;
    private final SessionTable sessions = new SessionTable();
    private final List<OwnershipLog> logs;
    private final List<GnomeAgentRunner> runners = new ArrayList<>();
    private final GatewayJournal journal;
    private final LaneAdmissions admissions;
    private final boolean journalPaused;
    private GnomeAgentRunner journalRunner;

    /**
     * What a gateway is built from: its one account, the listings it may trade, the registry it checks hellos against,
     * the directory that outlives it, and optionally a journal (its ring size; held back until {@link #startJournal()}
     * when {@code journalPaused}). {@code tap} hears every decision of the account's lane as well, on its thread.
     */
    public record Setup(
            GatewayAccount account,
            List<Listing> listings,
            Registry registry,
            Path stateDirectory,
            Path journalDirectory,
            int journalRing,
            boolean journalPaused,
            AccountLane.Admissions tap) {}

    private GatewayFixture(final String name, final int ingressPort, final Setup setup) throws Exception {
        this.aeron = AeronRuntime.launch(name, AeronProfile.GATEWAY, Throwable::printStackTrace);
        this.account = setup.account();
        final GatewayControl.Listings listings = new GatewayControl.Listings() {
            @Override
            public Listing listing(final int exchangeId, final long securityId) {
                for (final Listing listing : setup.listings()) {
                    if (listing.exchange().exchangeId() == exchangeId
                            && listing.security().securityId() == securityId) {
                        return listing;
                    }
                }
                return null;
            }

            @Override
            public FeeTimeline fees(final Listing listed) {
                return FeeTimeline.EMPTY;
            }
        };
        this.journal = setup.journalDirectory() == null
                ? null
                : new GatewayJournal(
                        new NullLogger(),
                        setup.journalDirectory(),
                        16L << 20,
                        Long.MAX_VALUE,
                        System::nanoTime,
                        1,
                        setup.journalRing());
        this.journalPaused = setup.journalPaused();
        this.admissions = new LaneAdmissions(
                this.account.accountId(),
                this.journal == null ? JournalRecorder.NONE : this.journal.lane(0),
                System::nanoTime);
        final AccountLane.Admissions journaled = this.admissions;
        final AccountLane.Admissions tap = setup.tap();
        this.account.lane().admissions(new AccountLane.Admissions() {
            @Override
            public void decided(
                    final long sessionSlot,
                    final long clientOidCounter,
                    final RequestClass type,
                    final AdmissionAction action,
                    final long queuedNanos,
                    final long tokensLeft) {
                journaled.decided(sessionSlot, clientOidCounter, type, action, queuedNanos, tokensLeft);
                tap.decided(sessionSlot, clientOidCounter, type, action, queuedNanos, tokensLeft);
            }

            @Override
            public void answered(final RequestClass type, final long roundTripNanos, final boolean whole) {
                journaled.answered(type, roundTripNanos, whole);
                tap.answered(type, roundTripNanos, whole);
            }

            @Override
            public void connectionsBusy() {
                journaled.connectionsBusy();
                tap.connectionsBusy();
            }
        });
        final SessionRegistry registry = new SessionRegistry(setup.registry().records::get);
        final GatewayRestart.Result restart = GatewayRestart.recover(
                new NullLogger(),
                setup.stateDirectory(),
                1 << 20,
                List.of(this.account),
                listings,
                registry,
                System.nanoTime(),
                System.currentTimeMillis());
        this.logs = restart.logs();
        final GatewayStatus status = new GatewayStatus(System.nanoTime());
        final ManyToOneRingBuffer<GatewayMessage> toControl =
                new ManyToOneRingBuffer<>(GatewayMessage[]::new, GatewayMessage::new, 256);
        final ManyToOneRingBuffer<GatewayMessage> toEgress =
                new ManyToOneRingBuffer<>(GatewayMessage[]::new, GatewayMessage::new, 256);
        final ManyToOneRingBuffer<AccountReport> rejects =
                new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 256);
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
                this.journal == null ? JournalRecorder.NONE : this.journal.ingress());
        this.egress = new GatewayEgress(
                this.sessions,
                status,
                List.of(this.account.reports(), rejects),
                toEgress,
                System::nanoTime,
                new SystemEpochNanoClock(),
                this.journal == null ? JournalRecorder.NONE : this.journal.egress());
        this.control = new GatewayControl(
                new NullLogger(),
                this.sessions,
                status,
                Map.of(this.account.accountId(), this.account),
                toControl,
                toEgress,
                registry,
                listings,
                GatewayControl.Replies.over(this.aeron.aeron()),
                state -> {},
                System::nanoTime,
                1024,
                GatewayControl.Idle.NEVER,
                new GatewayControl.Restart(
                        restart.sessionsFile(), restart.sessions(), TimeUnit.SECONDS.toNanos(10), restart.loaded()));
    }

    /** The fixture's listing, which every session on the fake venue trades. */
    public static Listing listing() {
        return new Listing(
                LISTING_ID,
                new Exchange(EXCHANGE_ID, "TEST", "TEST", "global"),
                new Security((int) SECURITY_ID, "T", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "T",
                "T");
    }

    /** Starts a gateway on the fake venue. */
    public static GatewayFixture start(final String name, final Venue venue, final int ingressPort) throws Exception {
        final Listing listing = listing();
        return start(
                name,
                ingressPort,
                new Setup(
                        account(venue),
                        List.of(listing),
                        venue.registry(),
                        venue.stateDirectory(),
                        null,
                        0,
                        false,
                        AccountLane.Admissions.NONE));
    }

    /** The lane's last full minute, as the gateway's status line gives it; null if none. */
    public String lastMinute() {
        return this.admissions.lastMinute(System.nanoTime());
    }

    /** Starts a gateway: its account, and then ingress, egress, control and (unless held back) its journal. */
    public static GatewayFixture start(final String name, final int ingressPort, final Setup setup) throws Exception {
        final GatewayFixture gateway = new GatewayFixture(name, ingressPort, setup);
        gateway.account.start();
        gateway.run(gateway.egress);
        gateway.run(gateway.ingress);
        gateway.run(gateway.control);
        if (gateway.journal != null && !gateway.journalPaused) {
            gateway.startJournal();
        }
        return gateway;
    }

    /** Starts reading the journal's rings, if held back: what they lapped meanwhile is lost, and written as a gap. */
    public void startJournal() {
        if (this.journalRunner == null) {
            this.journalRunner = new GnomeAgentRunner(this.journal, Throwable::printStackTrace);
            GnomeAgentRunner.startOnThread(this.journalRunner);
        }
    }

    /** Records the journal has lost to laps; 0 without one. */
    public long journalLapped() {
        return this.journal == null ? 0 : this.journal.lapped();
    }

    private void run(final GnomeAgent agent) {
        final GnomeAgentRunner runner = new GnomeAgentRunner(agent, Throwable::printStackTrace);
        GnomeAgentRunner.startOnThread(runner);
        this.runners.add(runner);
    }

    private static GatewayAccount account(final Venue venue) throws Exception {
        return account(
                venue,
                VenueRateLimits.of(
                        "Test",
                        List.of(new VenueRateLimits.Bucket("all", 100_000, 100, 0.2)),
                        List.of(
                                new VenueRateLimits.Rule(HTTPMethod.DELETE, "/", null, "all", 1, RequestClass.CANCEL),
                                new VenueRateLimits.Rule(null, "", null, "all", 1, RequestClass.NEW)),
                        List.of()),
                MAX_ORDERS,
                4,
                1);
    }

    /**
     * The account on the fake venue, held to {@code limits} (its budget starts empty), with room for {@code maxOrders}
     * working orders and {@code connections} requests on the wire at once, sending submits and cancels up to
     * {@code maxBatch} to a request.
     */
    public static GatewayAccount account(
            final Venue venue,
            final VenueRateLimits limits,
            final int maxOrders,
            final int connections,
            final int maxBatch)
            throws Exception {
        final TestLaneVenue laneVenue = new TestLaneVenue();
        laneVenue.maxBatch = maxBatch;
        final ManyToOneRingBuffer<OrderContext> notices =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 4 * maxOrders);
        final ManyToOneRingBuffer<OrderContext> released =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 2 * maxOrders);
        final GatewayAccount.Parts parts = new GatewayAccount.Parts();
        parts.account = new VenueAccount(ACCOUNT_ID, EXCHANGE_ID, "test-account", "unused", null);
        parts.name = "account-" + ACCOUNT_ID;
        parts.logger = new NullLogger();
        parts.exit = (status, cause) -> {
            throw new IllegalStateException("The gateway gave up: " + cause, cause);
        };
        parts.markets = (listing, fees) -> {};
        parts.orderQuery = venue.orders();
        parts.commands = new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, 4 * maxOrders);
        parts.reports = new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 4 * maxOrders);
        parts.connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTP, "127.0.0.1")
                .withPort(venue.server().port())
                .withConnections(connections)
                .build();
        parts.connector = new HTTPConnector(List.of(parts.connections), HTTPConnector.Listener.NONE);
        parts.budget = new AccountBudget(limits, System.nanoTime());
        parts.lane = new AccountLane<>(
                parts.name,
                laneVenue,
                parts.connections,
                parts.budget,
                AccountLane.Timing.defaults(),
                System::nanoTime,
                SystemEpochClock.INSTANCE,
                maxOrders,
                2048,
                512,
                parts.commands,
                notices,
                released);
        parts.reader = new AccountReader(
                venue.protocol(), new SystemEpochNanoClock(), maxOrders, notices, released, parts.reports);
        parts.connection = new AccountConnection<>(
                parts.name,
                new NullLogger(),
                venue.transport(),
                venue.protocol(),
                parts.reader,
                new GatewayConfig.Builder().build(),
                SystemEpochClock.INSTANCE,
                new SystemEpochNanoClock(),
                ConnectLimiter.UNLIMITED,
                TimeUnit.MILLISECONDS.toNanos(100));
        return new GatewayAccount(parts);
    }

    public GatewayAccount account() {
        return this.account;
    }

    /** The session holding {@code slot}, or null. */
    public boolean linked(final long slot) {
        final GatewaySession session = this.sessions.bySlot(slot);
        return session != null && session.state == GatewaySession.ACTIVE;
    }

    /** Stops every thread as a crash would: nothing is cancelled and no session told. */
    public void kill() {
        for (int i = this.runners.size() - 1; i >= 0; i--) {
            try {
                this.runners.get(i).close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        this.account.close();
        this.logs.forEach(OwnershipLog::close);
        if (this.journal != null) {
            try {
                if (this.journalRunner != null) {
                    this.journalRunner.close();
                }
                this.journal.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        this.aeron.close();
    }

    @Override
    public void close() {
        kill();
    }
}
