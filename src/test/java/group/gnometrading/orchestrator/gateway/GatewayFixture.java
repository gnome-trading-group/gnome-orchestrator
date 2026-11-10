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
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.account.reader.TextAccountProtocol;
import group.gnometrading.gateways.orders.CrossMarket;
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
import group.gnometrading.orchestrator.gateway.control.GatewayKills;
import group.gnometrading.orchestrator.gateway.control.KillSource;
import group.gnometrading.orchestrator.gateway.control.SessionRegistry;
import group.gnometrading.orchestrator.gateway.core.AccountLink;
import group.gnometrading.orchestrator.gateway.core.CrossKey;
import group.gnometrading.orchestrator.gateway.core.CrossKeys;
import group.gnometrading.orchestrator.gateway.core.GatewayAlerts;
import group.gnometrading.orchestrator.gateway.core.GatewayMessage;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.GatewayStatus;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.orchestrator.gateway.core.Kills;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import group.gnometrading.orchestrator.gateway.journal.GatewayJournal;
import group.gnometrading.orchestrator.gateway.journal.OwnershipLog;
import group.gnometrading.orchestrator.gateway.transport.CrossIndex;
import group.gnometrading.orchestrator.gateway.transport.GatewayEgress;
import group.gnometrading.orchestrator.gateway.transport.GatewayIngress;
import group.gnometrading.orchestrator.gateway.transport.GatewayRejects;
import group.gnometrading.schemas.gateway.AdmissionAction;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.fee.FeeTimeline;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
    public static final int SECOND_ACCOUNT_ID = ACCOUNT_ID + 1;
    public static final int EXCHANGE_ID = TestLaneVenue.EXCHANGE_ID;
    public static final long SECURITY_ID = TestLaneVenue.SECURITY_ID;
    public static final int LISTING_ID = 70;
    private static final int MAX_ORDERS = 256;
    private static final int CROSS_CAPACITY = 4096;

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

        /** The session as the registry has it once it stopped. */
        public void end(final String sessionId) {
            final SessionRegistry.SessionRecord running = this.records.get(sessionId);
            this.records.put(
                    sessionId,
                    new SessionRegistry.SessionRecord(
                            running.sessionSeq(),
                            "STOPPED",
                            running.strategyId(),
                            running.mode(),
                            running.listingIds(),
                            running.accountByExchange(),
                            running.rateWeight()));
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
    // The first of its accounts, which the fixture's single-account tests use.
    private final GatewayAccount account;
    private final List<GatewayAccount> accounts;
    private final GatewayControl control;
    private final GatewayIngress ingress;
    private final GatewayEgress egress;
    private final SessionTable sessions = new SessionTable();
    private final List<OwnershipLog> logs;
    private final List<GnomeAgentRunner> runners = new ArrayList<>();
    private final GatewayJournal journal;
    private final List<LaneAdmissions> admissions;
    private final boolean journalPaused;
    private final CrossIndex cross;
    private final Kills kills = new Kills();
    private final GatewayKills gatewayKills;
    private final Path killsFile;
    private GnomeAgentRunner journalRunner;
    private boolean stopped;

    /**
     * What a gateway is built from: its one account, the listings it may trade, the registry it checks hellos against,
     * the directory that outlives it, and optionally a journal (its ring size; held back until {@link #startJournal()}
     * when {@code journalPaused}). {@code tap} hears every decision of the account's lane as well, on its thread.
     */
    public record Setup(
            List<GatewayAccount> accounts,
            List<Listing> listings,
            Registry registry,
            Path stateDirectory,
            Path journalDirectory,
            int journalRing,
            boolean journalPaused,
            AccountLane.Admissions tap) {}

    private GatewayFixture(final String name, final int ingressPort, final Setup setup) throws Exception {
        this.aeron = AeronRuntime.launch(name, AeronProfile.GATEWAY, Throwable::printStackTrace);
        this.accounts = setup.accounts();
        this.account = this.accounts.get(0);
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
                        setup.accounts().size(),
                        setup.journalRing());
        this.journalPaused = setup.journalPaused();
        this.admissions = new ArrayList<>();
        for (int i = 0; i < this.accounts.size(); i++) {
            final GatewayAccount account = this.accounts.get(i);
            final LaneAdmissions journaled = new LaneAdmissions(
                    account.accountId(),
                    this.journal == null ? JournalRecorder.NONE : this.journal.lane(i),
                    System::nanoTime);
            this.admissions.add(journaled);
            account.lane().admissions(tapped(journaled, setup.tap()));
        }
        final SessionRegistry registry = new SessionRegistry(setup.registry().records::get);
        this.cross = new CrossIndex(
                this.account.crossKeys() == null ? new CrossKeys() : this.account.crossKeys(),
                CrossIndex.Mode.ENFORCE,
                CROSS_CAPACITY,
                CROSS_CAPACITY);
        final GatewayRestart.Result restart = GatewayRestart.recover(
                new NullLogger(),
                setup.stateDirectory(),
                1 << 20,
                this.accounts,
                listings,
                registry,
                System.nanoTime(),
                System.currentTimeMillis(),
                this.cross);
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
                this.journal == null ? JournalRecorder.NONE : this.journal.ingress(),
                this.cross,
                this.kills);
        this.egress = new GatewayEgress(
                this.sessions,
                status,
                reportRings(this.accounts, rejects),
                toEgress,
                System::nanoTime,
                new SystemEpochNanoClock(),
                this.journal == null ? JournalRecorder.NONE : this.journal.egress(),
                this.cross);
        this.killsFile = setup.stateDirectory().resolve("kills.json");
        this.gatewayKills = new GatewayKills(
                new NullLogger(),
                GatewayAlerts.NONE,
                KillSource.file(this.killsFile),
                this.kills,
                this.sessions,
                listingId -> {
                    for (final Listing listing : setup.listings()) {
                        if (listing.listingId() == listingId) {
                            return PublishedMap.listingKey(
                                    listing.exchange().exchangeId(),
                                    listing.security().securityId());
                        }
                    }
                    return 0;
                },
                System::nanoTime);
        // The gateway takes no new order before its first read of the kills, as at startup.
        this.gatewayKills.run();
        this.control = new GatewayControl(
                new NullLogger(),
                GatewayAlerts.NONE,
                this.sessions,
                status,
                links(this.accounts),
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
                        List.of(account(venue)),
                        List.of(listing),
                        venue.registry(),
                        venue.stateDirectory(),
                        null,
                        0,
                        false,
                        AccountLane.Admissions.NONE));
    }

    /** The first account's lane's last full minute, as the gateway's status line gives it; null if none. */
    public String lastMinute() {
        return this.admissions.get(0).lastMinute(System.nanoTime());
    }

    private static List<ManyToOneRingBuffer<AccountReport>> reportRings(
            final List<GatewayAccount> accounts, final ManyToOneRingBuffer<AccountReport> rejects) {
        final List<ManyToOneRingBuffer<AccountReport>> rings = new ArrayList<>();
        for (final GatewayAccount account : accounts) {
            rings.add(account.reports());
        }
        rings.add(rejects);
        return rings;
    }

    private static Map<Integer, AccountLink> links(final List<GatewayAccount> accounts) {
        final Map<Integer, AccountLink> links = new HashMap<>();
        for (final GatewayAccount account : accounts) {
            links.put(account.accountId(), account);
        }
        return links;
    }

    /** What the lane decides, to the journal and to the test's own tap. */
    private static AccountLane.Admissions tapped(
            final AccountLane.Admissions journaled, final AccountLane.Admissions tap) {
        return new AccountLane.Admissions() {
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
        };
    }

    /** Starts a gateway: its account, and then ingress, egress, control and (unless held back) its journal. */
    public static GatewayFixture start(final String name, final int ingressPort, final Setup setup) throws Exception {
        final GatewayFixture gateway = new GatewayFixture(name, ingressPort, setup);
        for (final GatewayAccount account : gateway.accounts) {
            account.start();
        }
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
        return account(venue, ACCOUNT_ID, new CrossKeys());
    }

    /** An account on the fake venue under {@code accountId}, publishing into {@code crossKeys}. */
    private static GatewayAccount account(final Venue venue, final int accountId, final CrossKeys crossKeys)
            throws Exception {
        final GatewayAccount account = account(
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
                1,
                accountId,
                crossKeys);
        return account;
    }

    /**
     * Starts a gateway holding two accounts, each on its own fake venue, sharing one self-trade index: sessions of
     * either registered in {@code first}'s registry, the second's under {@link #SECOND_ACCOUNT_ID}.
     */
    public static GatewayFixture startOnTwoAccounts(
            final String name, final Venue first, final Venue second, final int ingressPort) throws Exception {
        final CrossKeys crossKeys = new CrossKeys();
        return start(
                name,
                ingressPort,
                new Setup(
                        List.of(account(first, ACCOUNT_ID, crossKeys), account(second, SECOND_ACCOUNT_ID, crossKeys)),
                        List.of(listing()),
                        first.registry(),
                        first.stateDirectory(),
                        null,
                        0,
                        false,
                        AccountLane.Admissions.NONE));
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
        return account(venue, limits, maxOrders, connections, maxBatch, ACCOUNT_ID, new CrossKeys());
    }

    private static GatewayAccount account(
            final Venue venue,
            final VenueRateLimits limits,
            final int maxOrders,
            final int connections,
            final int maxBatch,
            final int accountId,
            final CrossKeys crossKeys)
            throws Exception {
        final TestLaneVenue laneVenue = new TestLaneVenue();
        laneVenue.maxBatch = maxBatch;
        final ManyToOneRingBuffer<OrderContext> notices =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 4 * maxOrders);
        final ManyToOneRingBuffer<OrderContext> released =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 2 * maxOrders);
        final GatewayAccount.Parts parts = new GatewayAccount.Parts();
        parts.account = new VenueAccount(accountId, EXCHANGE_ID, "test-account-" + accountId, "unused", null);
        parts.name = "account-" + accountId;
        parts.logger = new NullLogger();
        parts.exit = (status, cause) -> {
            throw new IllegalStateException("The gateway gave up: " + cause, cause);
        };
        parts.markets = (listing, fees) ->
                CrossKey.of(listing.exchange().exchangeId(), new CrossMarket(listing.exchangeSecurityId(), false));
        parts.crossKeys = crossKeys;
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
    /**
     * Puts the kill switches in {@code json} (rows as the registry returns them) in force and runs one kill pass, as
     * the gateway's poller does each second: what they cover is refused from now on, and its working orders cancelled.
     */
    public void kills(final String json) throws IOException {
        Files.writeString(this.killsFile, json);
        this.gatewayKills.run();
    }

    public boolean linked(final long slot) {
        final GatewaySession session = this.sessions.bySlot(slot);
        return session != null && session.state == GatewaySession.ACTIVE;
    }

    /**
     * Stops as SIGTERM does: control's thread first, then every session's orders cancelled and each session told while
     * ingress and egress still run, then everything else.
     *
     * @return what the stop returned: whether the lanes emptied and every goodbye went out in time
     */
    public boolean plannedStop(final long timeoutNanos) throws Exception {
        final GnomeAgentRunner controlRunner = this.runners.stream()
                .filter(runner -> runner.getAgent() == this.control)
                .findFirst()
                .orElseThrow();
        controlRunner.close();
        this.runners.remove(controlRunner);
        final boolean stopped = this.control.stop(timeoutNanos);
        kill();
        return stopped;
    }

    /** Stops every thread as a crash would: nothing is cancelled and no session told. Once; again does nothing. */
    public void kill() {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        for (int i = this.runners.size() - 1; i >= 0; i--) {
            try {
                this.runners.get(i).close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        this.accounts.forEach(GatewayAccount::close);
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
