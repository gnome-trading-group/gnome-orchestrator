package group.gnometrading.orchestrator.gateway.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.account.reader.AccountState;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.orders.CrossMarket;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.AeronStreamIds;
import group.gnometrading.orchestrator.aeron.HostAddress;
import group.gnometrading.orchestrator.gateway.FakeOrderQuery;
import group.gnometrading.orchestrator.gateway.RecordingAlerts;
import group.gnometrading.orchestrator.gateway.core.AccountLink;
import group.gnometrading.orchestrator.gateway.core.CrossKey;
import group.gnometrading.orchestrator.gateway.core.CrossKeys;
import group.gnometrading.orchestrator.gateway.core.GatewayMessage;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.GatewayStatus;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.orchestrator.gateway.core.Kills;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import group.gnometrading.orchestrator.gateway.transport.CrossIndex;
import group.gnometrading.orchestrator.gateway.transport.GatewayEgress;
import group.gnometrading.orchestrator.gateway.transport.GatewayIngress;
import group.gnometrading.orchestrator.gateway.transport.GatewayRejects;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.gateway.GatewayHeartbeatDecoder;
import group.gnometrading.schemas.gateway.GatewayState;
import group.gnometrading.schemas.gateway.GoodbyeReason;
import group.gnometrading.schemas.gateway.HelloRefusal;
import group.gnometrading.schemas.gateway.MessageHeaderEncoder;
import group.gnometrading.schemas.gateway.Mode;
import group.gnometrading.schemas.gateway.OrderState;
import group.gnometrading.schemas.gateway.OrderStatusDecoder;
import group.gnometrading.schemas.gateway.ReHelloReason;
import group.gnometrading.schemas.gateway.ReHelloRequiredDecoder;
import group.gnometrading.schemas.gateway.SessionGoodbyeDecoder;
import group.gnometrading.schemas.gateway.SessionGoodbyeEncoder;
import group.gnometrading.schemas.gateway.SessionHeartbeatEncoder;
import group.gnometrading.schemas.gateway.SessionHelloEncoder;
import group.gnometrading.schemas.gateway.SessionHelloOrdersEncoder;
import group.gnometrading.schemas.gateway.SessionWelcomeDecoder;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.fee.FeeTimeline;
import io.aeron.Publication;
import io.aeron.Subscription;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The control thread with ingress and egress over a real media driver, a registry and an account held in memory. */
class GatewayControlTest {

    private static final int INGRESS_PORT = 40972;
    private static final int REPLY_PORT = 40973;
    private static final long SLOT = 42;
    private static final String SESSION_ID = "0d3c3b4e-5c55-4a3f-9d1c-3a1f2b7c9e10";
    private static final int STRATEGY_ID = 3;
    private static final int ACCOUNT_ID = 5;
    private static final int EXCHANGE_ID = 1;
    private static final long SECURITY_ID = 7;
    private static final int LISTING_ID = 70;
    private static final long COD_MILLIS = 300;

    private final AeronRuntime gatewayAeron =
            AeronRuntime.launch("control-gateway", AeronProfile.GATEWAY, Throwable::printStackTrace);
    private final AeronRuntime sessionAeron =
            AeronRuntime.launch("control-session", AeronProfile.GATEWAY, Throwable::printStackTrace);
    private final Subscription ingressSubscription = this.gatewayAeron
            .aeron()
            .addSubscription(AeronChannels.gatewayIngress("localhost", INGRESS_PORT), AeronStreamIds.GATEWAY_INGRESS);
    private Publication toGateway = this.sessionAeron
            .aeron()
            .addPublication(AeronChannels.gatewayIngress("localhost", INGRESS_PORT), AeronStreamIds.GATEWAY_INGRESS);
    private final Subscription replies = this.sessionAeron
            .aeron()
            .addSubscription(AeronChannels.gatewayReply("localhost", REPLY_PORT), AeronStreamIds.GATEWAY_REPLY);

    private final RecordingAlerts alerts = new RecordingAlerts();
    private final TestAccount account = new TestAccount();
    private final SessionTable sessions = new SessionTable();
    private final List<LaneCommand> sessionCommands = new ArrayList<>();
    private final GatewayStatus status = new GatewayStatus(99);
    private final ManyToOneRingBuffer<AccountReport> rejectRing =
            new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 64);
    private final ManyToOneRingBuffer<GatewayMessage> toControl =
            new ManyToOneRingBuffer<>(GatewayMessage[]::new, GatewayMessage::new, 64);
    private final ManyToOneRingBuffer<GatewayMessage> toEgress =
            new ManyToOneRingBuffer<>(GatewayMessage[]::new, GatewayMessage::new, 64);
    private final AtomicReference<SessionRegistry.SessionRecord> registryRecord =
            new AtomicReference<>(record("RUNNING"));
    private boolean registryDown;
    private final List<GatewayState> advertised = new ArrayList<>();
    // Per advertisement: sessions linked, orders working.
    private final List<long[]> advertisedActivity = new ArrayList<>();
    private final List<Received> received = new ArrayList<>();
    private GatewayIngress ingress;
    private CrossIndex cross;
    private GatewayEgress egress;
    private GatewayControl control;
    private Listing listing;
    private SessionsFile sessionsFile;

    @TempDir
    Path stateDirectory;

    private record Received(int templateId, long reservedValue, UnsafeBuffer message) {}

    private static final class TestAccount implements AccountLink {
        final FakeOrderQuery query = new FakeOrderQuery();
        final ManyToOneRingBuffer<LaneCommand> commands =
                new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, 64);
        final ManyToOneRingBuffer<AccountReport> reports =
                new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 64);
        final List<Integer> loads = new ArrayList<>();
        final List<String> sessionsAdded = new ArrayList<>();
        volatile AccountState state = AccountState.READY;
        volatile int working;

        @Override
        public int accountId() {
            return ACCOUNT_ID;
        }

        @Override
        public int exchangeId() {
            return 7;
        }

        @Override
        public ManyToOneRingBuffer<LaneCommand> commands() {
            return this.commands;
        }

        @Override
        public AccountState state() {
            return this.state;
        }

        @Override
        public int workingOrders() {
            return this.working;
        }

        @Override
        public VenueOrderQuery orderQuery() {
            return this.query;
        }

        @Override
        public AccountLink.Markets markets() {
            return new AccountLink.Markets() {
                @Override
                public CrossKey load(final Listing listing, final FeeTimeline fees) {
                    TestAccount.this.loads.add(listing.listingId());
                    return CrossKey.of(
                            listing.exchange().exchangeId(), new CrossMarket(listing.exchangeSecurityId(), false));
                }

                @Override
                public void addSession(final long slot, final String sessionId) {
                    TestAccount.this.sessionsAdded.add(sessionId);
                }
            };
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        this.sessionsFile = SessionsFile.open(
                this.stateDirectory.resolve("sessions.jsonl"), System.currentTimeMillis(), new ArrayList<>());
        this.cross = new CrossIndex(new CrossKeys(), CrossIndex.Mode.ENFORCE, 1024, 1024);
        this.ingress = new GatewayIngress(
                this.ingressSubscription,
                this.sessions,
                this.status,
                new GatewayRejects(this.rejectRing, new SystemEpochNanoClock()),
                this.toControl,
                System::nanoTime,
                JournalRecorder.NONE,
                this.cross,
                Kills.none());
        this.egress = new GatewayEgress(
                this.sessions,
                this.status,
                List.of(this.account.reports, this.rejectRing),
                this.toEgress,
                System::nanoTime,
                new SystemEpochNanoClock(),
                JournalRecorder.NONE,
                this.cross);
        this.listing = new Listing(
                LISTING_ID,
                new Exchange(EXCHANGE_ID, "KALSHI", "KALSHI", "global"),
                new Security((int) SECURITY_ID, "T", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "T",
                "T");
        this.control = control(List.of(), GatewayControl.Restart.HELLO_GRACE_NANOS);
    }

    private GatewayControl control(final List<GatewayControl.Recovered> recovered, final long helloGraceNanos) {
        return control(recovered, helloGraceNanos, Map.of());
    }

    private GatewayControl control(
            final List<GatewayControl.Recovered> recovered,
            final long helloGraceNanos,
            final Map<Integer, Set<Long>> loaded) {
        final Listing listing = this.listing;
        return new GatewayControl(
                new NullLogger(),
                this.alerts,
                this.sessions,
                this.status,
                Map.of(ACCOUNT_ID, this.account),
                this.toControl,
                this.toEgress,
                new SessionRegistry(id -> {
                    if (this.registryDown) {
                        throw new IllegalStateException("registry down");
                    }
                    return id.equals(SESSION_ID) ? this.registryRecord.get() : null;
                }),
                new GatewayControl.Listings() {
                    @Override
                    public Listing listing(final int exchangeId, final long securityId) {
                        return exchangeId == EXCHANGE_ID && securityId == SECURITY_ID ? listing : null;
                    }

                    @Override
                    public FeeTimeline fees(final Listing listed) {
                        return FeeTimeline.EMPTY;
                    }
                },
                GatewayControl.Replies.over(this.gatewayAeron.aeron()),
                (state, linkedSessions, workingOrders) -> {
                    this.advertised.add(state);
                    this.advertisedActivity.add(new long[] {linkedSessions, workingOrders});
                },
                System::nanoTime,
                64,
                System::currentTimeMillis,
                new GatewayControl.Restart(this.sessionsFile, recovered, helloGraceNanos, loaded));
    }

    @AfterEach
    void close() {
        this.sessionAeron.close();
        this.gatewayAeron.close();
    }

    @Test
    void anAcceptedHelloLinksTheSessionLoadsItsListingOnceAndItsOrdersReachTheLane() throws Exception {
        awaitReady();
        hello(0, 0);
        assertAccepted(awaitWelcome());
        final GatewaySession session = this.sessions.bySlot(SLOT);
        assertEquals(GatewaySession.ACTIVE, session.state);
        assertEquals(List.of(LISTING_ID), this.account.loads);
        assertEquals(List.of(SESSION_ID), this.account.sessionsAdded);

        sendOrder();
        assertEquals(LaneCommand.NEW, awaitCommand().kind);

        this.received.clear();
        hello(0, 0);
        assertAccepted(awaitWelcome());
        assertEquals(List.of(LISTING_ID), this.account.loads, "a listing is made tradeable once");
    }

    @Test
    void aListingTheRestartMadeTradeableIsntLoadedAgainAtAHello() throws Exception {
        this.control = control(
                List.of(),
                GatewayControl.Restart.HELLO_GRACE_NANOS,
                Map.of(ACCOUNT_ID, Set.of(PublishedMap.listingKey(EXCHANGE_ID, SECURITY_ID))));
        awaitReady();

        hello(0, 0);

        assertAccepted(awaitWelcome());
        assertTrue(this.account.loads.isEmpty(), "loaded once, by the restart");
    }

    @Test
    void anAcceptedSessionIsIntroducedToItsLaneWithItsWeightWaitAndCapsBeforeAnyOrder() throws Exception {
        awaitReady();
        this.registryRecord.set(new SessionRegistry.SessionRecord(
                SLOT, "RUNNING", STRATEGY_ID, "live", Set.of(LISTING_ID), Map.of(EXCHANGE_ID, ACCOUNT_ID), 3));
        hello(0, 0);
        assertAccepted(awaitWelcome());
        sendOrder();
        assertEquals(LaneCommand.NEW, awaitCommand().kind);

        assertEquals(1, this.sessionCommands.size());
        final LaneCommand introduction = this.sessionCommands.get(0);
        assertEquals(LaneCommand.SESSION, introduction.kind);
        assertEquals(SLOT, introduction.sessionSlot);
        assertEquals(3, introduction.weight, "the strategy's weight from the registry");
        assertEquals(TimeUnit.MILLISECONDS.toNanos(2_000), introduction.maxQueueNanos, "the hello's wait");
        assertEquals(30, introduction.maxOpenOrders, "the caps it asked for, which the lane bounds");
        assertEquals(5, introduction.maxNewPerSecond);
    }

    @Test
    void aNewSessionIsRefusedOnceItsAccountServesAsManyAsALaneHolds() throws Exception {
        awaitReady();
        for (int i = 0; i < AccountLane.MAX_SESSIONS; i++) {
            this.sessions.add(new GatewaySession(10_000 + i, "other-" + i, STRATEGY_ID, this.account, 4));
        }
        hello(0, 0);
        assertRefused(awaitWelcome(), HelloRefusal.SESSION_CAP);
    }

    @Test
    void aSessionTheRegistryDoesntShowRunningIsRefused() throws Exception {
        awaitReady();
        this.registryRecord.set(record("STOPPED"));
        hello(0, 0);
        assertRefused(awaitWelcome(), HelloRefusal.SESSION_NOT_RUNNING);
        assertEquals(null, this.sessions.bySlot(SLOT));
    }

    @Test
    void aHelloNamingAListingTheSessionDoesntLeaseIsRefused() throws Exception {
        awaitReady();
        this.registryRecord.set(new SessionRegistry.SessionRecord(
                SLOT, "RUNNING", STRATEGY_ID, "live", Set.of(), Map.of(EXCHANGE_ID, ACCOUNT_ID), 1));
        hello(0, 0);
        assertRefused(awaitWelcome(), HelloRefusal.LISTING_NOT_LEASED);
    }

    @Test
    void aRegistryOutageRefusesAnUnknownSessionButNotOneSeenRecently() throws Exception {
        awaitReady();
        this.registryDown = true;
        hello(0, 0);
        assertRefused(awaitWelcome(), HelloRefusal.REGISTRY_UNAVAILABLE);
        this.received.clear();

        this.registryDown = false;
        hello(0, 0);
        assertAccepted(awaitWelcome());
        this.received.clear();
        this.registryDown = true;
        hello(0, 0);
        assertAccepted(awaitWelcome());
    }

    @Test
    void aGatewayWhoseAccountsArentReadyRefusesHellos() throws Exception {
        this.account.state = AccountState.CONNECTING;
        runFor(50);
        assertEquals(GatewayState.STARTING, this.status.state());
        hello(0, 0);
        assertRefused(awaitWelcome(), HelloRefusal.NOT_READY);

        this.account.state = AccountState.READY;
        awaitReady();
        this.account.state = AccountState.DOWN;
        await(() -> this.status.state() == GatewayState.DEGRADED, "degraded");
    }

    @Test
    void aChangeOfStateIsAdvertisedAtOnceNotAtTheNextInterval() throws Exception {
        this.account.state = AccountState.CONNECTING;
        runFor(50);
        assertEquals(List.of(GatewayState.STARTING), this.advertised);

        // Sessions link only to a gateway its endpoint calls READY; the interval is 15 s, this test far shorter.
        this.account.state = AccountState.READY;
        await(() -> this.advertised.contains(GatewayState.READY), "READY advertised");
        this.account.state = AccountState.DOWN;
        await(() -> this.advertised.contains(GatewayState.DEGRADED), "DEGRADED advertised");
        assertEquals(List.of(GatewayState.STARTING, GatewayState.READY, GatewayState.DEGRADED), this.advertised);
    }

    @Test
    void aHelloWaitsForItsLastPageOfOpenOrders() throws Exception {
        awaitReady();
        hello(0, 2);
        helloOrders(0, false);
        runFor(100);
        assertTrue(welcomes().isEmpty(), "not before the last page");
        helloOrders(1, true);
        assertAccepted(awaitWelcome());
    }

    @Test
    void aReconnectingSessionIsSentOnlyTheReportsItLacks() throws Exception {
        awaitReady();
        hello(0, 0);
        awaitWelcome();
        for (int i = 1; i <= 3; i++) {
            report(i);
        }
        await(() -> this.sessions.bySlot(SLOT).replay.newest() == 3, "three reports");
        await(() -> reports().size() == 3, "the reports to arrive");
        this.received.clear();

        reconnect();
        hello(1, 0);
        await(() -> reports().size() == 2, "the two reports it lacks");
        assertEquals(SessionWelcomeDecoder.TEMPLATE_ID, nonHeartbeats().get(0).templateId(), "the welcome first");
        assertEquals(
                List.of(2L, 3L), reports().stream().map(Received::reservedValue).toList());
    }

    @Test
    void aSilentSessionHasItsOrdersCancelledAndIsToldToSayHelloAgain() throws Exception {
        awaitReady();
        hello(0, 0);
        awaitWelcome();
        final LaneCommand cancel = awaitCommand();
        assertEquals(LaneCommand.CANCEL_SESSION, cancel.kind);
        assertEquals(SLOT, cancel.sessionSlot);
        assertEquals(ReHelloReason.COD_FIRED, awaitReHello());
        assertEquals(GatewaySession.UNLINKED, this.sessions.bySlot(SLOT).state);
    }

    @Test
    void aSessionThatStopsHearingTheGatewayIsCutLikeASilentOne() throws Exception {
        awaitReady();
        hello(0, 0);
        awaitWelcome();
        // Heartbeats keep arriving, but always echo the same gateway heartbeat: ours aren't reaching it.
        final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COD_MILLIS * 3);
        while (System.nanoTime() < until && this.sessions.bySlot(SLOT).state == GatewaySession.ACTIVE) {
            heartbeat(1);
            runFor(20);
        }
        assertEquals(GatewaySession.UNLINKED, this.sessions.bySlot(SLOT).state);
        assertEquals(LaneCommand.CANCEL_SESSION, awaitCommand().kind);
    }

    @Test
    void aSessionThatCantHearTheGatewayIsCutHoweverOftenItSaysHelloAgain() throws Exception {
        awaitReady();
        hello(0, 0);
        awaitWelcome();
        // As a session whose replies never arrive behaves: it drops its link, reconnects and says hello again, well
        // inside its timeout each time, heartbeating meanwhile with an echo that never moves.
        final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COD_MILLIS * 3);
        while (System.nanoTime() < until && this.sessions.bySlot(SLOT).state == GatewaySession.ACTIVE) {
            reconnect();
            hello(0, 0);
            final long nextHello = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COD_MILLIS / 3);
            while (System.nanoTime() < nextHello && this.sessions.bySlot(SLOT).state == GatewaySession.ACTIVE) {
                heartbeat(1);
                runFor(20);
            }
        }
        assertEquals(GatewaySession.UNLINKED, this.sessions.bySlot(SLOT).state);
        assertTrue(
                drainCommands().stream().anyMatch(command -> command.kind == LaneCommand.CANCEL_SESSION),
                "its orders cancelled");
    }

    @Test
    void aSessionThatKeepsHeartbeatingAndHearingStaysLinked() throws Exception {
        awaitReady();
        hello(0, 0);
        awaitWelcome();
        final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COD_MILLIS * 3);
        while (System.nanoTime() < until) {
            heartbeat(this.egress.heartbeatSeq());
            runFor(20);
        }
        assertEquals(GatewaySession.ACTIVE, this.sessions.bySlot(SLOT).state);
        assertTrue(drainCommands().isEmpty());
    }

    @Test
    void anUnreadableMessageEvictsItsSessionAndItsHellosAreRefusedForAWhile() throws Exception {
        awaitReady();
        hello(0, 0);
        awaitWelcome();
        offer(new UnsafeBuffer(new byte[] {1, 2, 3}), 3);
        assertEquals(ReHelloReason.EVICTED, awaitReHello());
        assertEquals(LaneCommand.CANCEL_SESSION, awaitCommand().kind);
        this.received.clear();
        hello(0, 0);
        assertRefused(awaitWelcome(), HelloRefusal.EVICTED);
    }

    @Test
    void aGoodbyeCancelsTheSessionsOrdersAndUnlinksIt() throws Exception {
        awaitReady();
        hello(0, 0);
        awaitWelcome();
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[64]);
        final SessionGoodbyeEncoder goodbye = new SessionGoodbyeEncoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .sessionSeq(SLOT)
                .reason(GoodbyeReason.SESSION_EXIT);
        offer(buffer, MessageHeaderEncoder.ENCODED_LENGTH + goodbye.encodedLength());
        assertEquals(LaneCommand.CANCEL_SESSION, awaitCommand().kind);
        await(() -> this.sessions.bySlot(SLOT).state == GatewaySession.UNLINKED, "unlinked");
    }

    @Test
    void aPlannedStopCancelsEverySessionAndSaysGoodbyeOnlyAfterTheCancelsReports() throws Exception {
        awaitReady();
        hello(0, 0);
        awaitWelcome();
        this.received.clear();
        this.account.working = 2;
        // As in the gateway: control's thread stopped, egress still running while the stop waits.
        final AtomicBoolean stopped = new AtomicBoolean();
        final Thread egressThread = new Thread(() -> {
            while (!stopped.get()) {
                this.egress.doWork();
                Thread.onSpinWait();
            }
        });
        final Thread venue = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            report(1, ExecType.CANCEL, group.gnometrading.schemas.OrderStatus.CANCELED, 0, 0, 0, 0);
            report(2, ExecType.CANCEL, group.gnometrading.schemas.OrderStatus.CANCELED, 0, 0, 0, 0);
            this.account.working = 0;
        });
        egressThread.start();
        venue.start();
        try {
            assertTrue(this.control.stop(TimeUnit.SECONDS.toNanos(5)));
        } finally {
            venue.join();
            stopped.set(true);
            egressThread.join();
        }
        assertEquals(GatewayState.STOPPING, this.status.state());
        assertEquals(LaneCommand.CANCEL_SESSION, drainCommands().get(0).kind);
        await(
                () -> nonHeartbeats().stream()
                        .anyMatch(message -> message.templateId() == SessionGoodbyeDecoder.TEMPLATE_ID),
                "the goodbye");
        assertEquals(
                List.of(
                        OrderExecutionReportDecoder.TEMPLATE_ID,
                        OrderExecutionReportDecoder.TEMPLATE_ID,
                        SessionGoodbyeDecoder.TEMPLATE_ID),
                nonHeartbeats().stream().map(Received::templateId).toList(),
                "both cancels heard before the goodbye, which comes last");
        assertEquals(GatewaySession.UNLINKED, this.sessions.bySlot(SLOT).state);
        assertTrue(this.advertised.contains(GatewayState.STOPPING));
        final int stopping = this.advertised.indexOf(GatewayState.STOPPING);
        assertArrayEquals(
                new long[] {1, 2},
                this.advertisedActivity.get(stopping),
                "in use as it stopped: one session linked, two orders working");
    }

    @Test
    void aRecoveredSessionsFirstHelloGetsAStatusPerOrderNotTheReportsFromBeforeIt() throws Exception {
        this.control = control(List.of(recovered(Set.of(1L, 2L), 60_000)), GatewayControl.Restart.HELLO_GRACE_NANOS);
        awaitReady();
        report(1, ExecType.NEW, group.gnometrading.schemas.OrderStatus.NEW, 0, 0, 0, 0);
        report(
                1,
                ExecType.PARTIAL_FILL,
                group.gnometrading.schemas.OrderStatus.PARTIALLY_FILLED,
                2_000_000,
                400_000_000,
                2_000_000,
                10);
        report(1, ExecType.CANCEL_REJECT, group.gnometrading.schemas.OrderStatus.CANCELED, 0, 0, 0, 0);
        report(2, ExecType.NEW, group.gnometrading.schemas.OrderStatus.NEW, 0, 0, 0, 0);
        await(() -> this.sessions.bySlot(SLOT).replay.newest() == 4, "the reports to wait for the session");

        hello(0, 1);
        helloOrders(
                0, true, new long[] {1, OrderState.PARTIALLY_FILLED.value()}, new long[] {2, OrderState.NEW.value()});
        await(() -> statuses().size() == 2, "a status per order");
        assertEquals(SessionWelcomeDecoder.TEMPLATE_ID, nonHeartbeats().get(0).templateId(), "the welcome first");
        assertTrue(reports().isEmpty(), "no report from before the restart is replayed");
        final OrderStatusDecoder first = statuses().stream()
                .map(GatewayControlTest::status)
                .filter(status -> status.clientOidCounter() == 1)
                .findFirst()
                .orElseThrow();
        assertEquals(OrderState.PARTIALLY_FILLED, first.state(), "a refused cancel changes nothing");
        assertEquals(2_000_000, first.cumulativeQty());
        assertEquals(800_000_000, first.notional(), "two at 40c");
        assertEquals(10, first.fees());
        assertTrue(drainCommands().isEmpty(), "both orders are the session's, as it says");
    }

    @Test
    void aRecoveredSessionsOrdersAreReconciledWithWhatItHolds() throws Exception {
        this.control = control(List.of(recovered(Set.of(1L, 2L), 60_000)), GatewayControl.Restart.HELLO_GRACE_NANOS);
        awaitReady();
        hello(0, 1);
        helloOrders(0, true, new long[] {1, OrderState.PENDING_CANCEL.value()}, new long[] {3, OrderState.NEW.value()});
        await(() -> statuses().size() == 1, "the never-sent order reported");
        final OrderStatusDecoder never = status(statuses().get(0));
        assertEquals(3, never.clientOidCounter());
        assertEquals(OrderState.REJECTED, never.state());
        final List<LaneCommand> commands = new ArrayList<>();
        await(
                () -> {
                    commands.addAll(drainCommands());
                    return commands.size() == 2;
                },
                "two cancels");
        assertEquals(
                List.of(1L, 2L),
                commands.stream()
                        .map(command -> command.clientOidCounter)
                        .sorted()
                        .toList(),
                "its cancel sent again, and the order it doesn't know cancelled");
        assertTrue(commands.stream().allMatch(command -> command.kind == LaneCommand.CANCEL));
        assertEquals(
                List.of("FIRING GATEWAY_UNATTRIBUTED_ORDERS " + SESSION_ID + ":2"),
                this.alerts.said,
                "the order nobody knew raised, as its fills may be missing from the ledger");
    }

    @Test
    void aRecoveredSessionThatNeverComesBackHasItsOrdersCancelled() throws Exception {
        this.control = control(List.of(recovered(Set.of(1L), 100)), TimeUnit.MILLISECONDS.toNanos(100));
        awaitReady();
        final LaneCommand cancel = awaitCommand();
        assertEquals(LaneCommand.CANCEL_SESSION, cancel.kind);
        assertEquals(SLOT, cancel.sessionSlot);
        assertEquals(GatewaySession.UNLINKED, this.sessions.bySlot(SLOT).state);
    }

    @Test
    void anAcceptedSessionIsRecordedForARestart() throws Exception {
        awaitReady();
        hello(0, 0);
        assertAccepted(awaitWelcome());
        final List<SessionsFile.Entry> entries = new ArrayList<>();
        SessionsFile.open(this.stateDirectory.resolve("sessions.jsonl"), System.currentTimeMillis(), entries);
        assertEquals(1, entries.size());
        assertEquals(SESSION_ID, entries.get(0).sessionId());
        assertEquals(SLOT, entries.get(0).slot());
        assertEquals(COD_MILLIS, entries.get(0).codTimeoutMs());
        assertEquals(Set.of(LISTING_ID), entries.get(0).listingIds());
    }

    @Test
    void aSessionWhoseRecoveredOrdersHadNoKnownOwnerIsTakenAtItsHello() throws Exception {
        this.control = control(
                List.of(new GatewayControl.Recovered(SLOT, null, STRATEGY_ID, ACCOUNT_ID, 0, Set.of(1L))),
                GatewayControl.Restart.HELLO_GRACE_NANOS);
        awaitReady();
        hello(0, 1);
        helloOrders(0, true, new long[] {1, OrderState.NEW.value()});
        assertAccepted(awaitWelcome());
        assertEquals(SESSION_ID, this.sessions.bySlot(SLOT).sessionId);
    }

    @Test
    void anOrderARestartLostTrackOfIsReportedAsTheVenueHasIt() throws Exception {
        this.control = control(List.of(recovered(Set.of(1L), 60_000)), GatewayControl.Restart.HELLO_GRACE_NANOS);
        // Ended before the restart, its last report lost with the process.
        this.account.query.orders.put("s42-2", new VenueOrder("s42-2", "v-2", 3_000_000, 1_200_000_000, 30, true));
        // Still resting, though nothing here holds it.
        this.account.query.orders.put("s42-3", new VenueOrder("s42-3", "v-3", 1_000_000, 400_000_000, 10, false));
        awaitReady();
        hello(0, 1);
        helloOrders(
                0,
                true,
                new long[] {1, OrderState.NEW.value()},
                new long[] {2, OrderState.NEW.value(), 1},
                new long[] {3, OrderState.PARTIALLY_FILLED.value(), 1},
                new long[] {4, OrderState.NEW.value(), 1});
        await(() -> statuses().size() == 3, "a status for each order the gateway lost");
        final Map<Long, OrderStatusDecoder> byCounter = new java.util.HashMap<>();
        for (final Received received : statuses()) {
            final OrderStatusDecoder status = status(received);
            byCounter.put(status.clientOidCounter(), status);
        }
        assertEquals(OrderState.FILLED, byCounter.get(2L).state(), "filled while the gateway was down");
        assertEquals(3_000_000, byCounter.get(2L).cumulativeQty());
        assertEquals(1_200_000_000, byCounter.get(2L).notional());
        assertEquals(30, byCounter.get(2L).fees());
        assertEquals(OrderState.CANCELED, byCounter.get(3L).state(), "resting unheld: cancelled");
        assertEquals(1_000_000, byCounter.get(3L).cumulativeQty());
        assertEquals(List.of("s42-3"), this.account.query.cancelled);
        assertEquals(OrderState.REJECTED, byCounter.get(4L).state(), "the venue never had it");
    }

    @Test
    void aSessionNewToTheGatewayHasTheOrdersItHoldsOpenSettledAsTheVenueHasThem() throws Exception {
        // As after a stop whose cancel reports never reached it: the gateway that took them is gone, this one holds
        // nothing of the session's.
        this.account.query.orders.put("s42-1", new VenueOrder("s42-1", "v-1", 0, 0, 0, true));
        this.account.query.orders.put("s42-2", new VenueOrder("s42-2", "v-2", 0, 0, 0, false));
        awaitReady();
        hello(7, 1);
        helloOrders(0, true, new long[] {1, OrderState.NEW.value(), 1}, new long[] {2, OrderState.NEW.value(), 1});
        await(() -> statuses().size() == 2, "a status for each order it holds open");
        for (final Received received : statuses()) {
            assertEquals(OrderState.CANCELED, status(received).state(), "ended at the venue, or cancelled there now");
        }
        assertEquals(List.of("s42-2"), this.account.query.cancelled, "only the one still resting is cancelled");
    }

    @Test
    void aSessionTheGatewayHoldsIsntLookedUpAtTheVenueWhenItSaysHelloAgain() throws Exception {
        awaitReady();
        hello(0, 0);
        awaitWelcome();
        report(1);
        await(() -> reports().size() == 1, "the report");

        reconnect();
        hello(1, 1);
        helloOrders(0, true, new long[] {1, OrderState.NEW.value(), 1});
        runFor(200);
        assertTrue(this.account.query.cancelled.isEmpty());
        assertEquals(0, this.account.query.lookups.get(), "its orders are this gateway's own");
    }

    @Test
    void aCutSessionsOrdersAreRefusedWithAReportNotDropped() throws Exception {
        awaitReady();
        hello(0, 0);
        awaitWelcome();
        assertEquals(LaneCommand.CANCEL_SESSION, awaitCommand().kind, "cut for silence");
        sendOrder();
        await(() -> !reports().isEmpty(), "the refusal");
        final OrderExecutionReport refusal = new OrderExecutionReport();
        refusal.buffer.putBytes(0, reports().get(0).message(), 0, refusal.totalMessageSize());
        assertEquals(group.gnometrading.schemas.RejectReason.SESSION_NOT_REGISTERED, refusal.decoder.rejectReason());
    }

    @Test
    void aSessionTheGatewayForgotCarriesOnItsNumberingFromWhereItWas() throws Exception {
        awaitReady();
        // As after its hellos were refused for longer than the gateway keeps a session that isn't linked.
        hello(500, 0);
        assertAccepted(awaitWelcome());

        report(1);
        await(() -> reports().size() == 1, "the report");

        assertEquals(501, reports().get(0).reservedValue(), "after the 500 it has passed on already");
        assertFalse(this.sessions.bySlot(SLOT).replay.holds(500), "none of those is held to replay");
    }

    @Test
    void aHelloNumberedByAnotherGatewayIsSentStatusesNotAReplay() throws Exception {
        awaitReady();
        hello(0, 0);
        awaitWelcome();
        report(1, ExecType.NEW, group.gnometrading.schemas.OrderStatus.NEW, 0, 0, 0, 0);
        await(() -> reports().size() == 1, "the report");
        this.received.clear();

        reconnect();
        helloWithEpoch(1, 12345);
        await(() -> statuses().size() == 1, "the order's status");
        assertTrue(reports().isEmpty(), "nothing resumed from a number another gateway gave");
    }

    private void helloWithEpoch(final long lastReportSeq, final long epoch) throws InterruptedException {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[512]);
        final SessionHelloEncoder hello = new SessionHelloEncoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .sessionSeq(SLOT)
                .sessionId(SESSION_ID)
                .strategyId(STRATEGY_ID)
                .mode(Mode.LIVE)
                .venueAccountId(ACCOUNT_ID)
                .protocolVersion(SessionHelloEncoder.SCHEMA_VERSION)
                .replyIp(HostAddress.pack("127.0.0.1") & 0xFFFF_FFFFL)
                .replyPort(REPLY_PORT)
                .lastReportSeq(lastReportSeq)
                .lastReportEpoch(epoch)
                .codTimeoutMs(60_000)
                .maxQueueMs(2_000)
                .openOrderPages(0);
        hello.listingsCount(1).next().exchangeId(EXCHANGE_ID).securityId(SECURITY_ID);
        offer(buffer, MessageHeaderEncoder.ENCODED_LENGTH + hello.encodedLength());
    }

    private static GatewayControl.Recovered recovered(final Set<Long> counters, final long codMillis) {
        return new GatewayControl.Recovered(
                SLOT, SESSION_ID, STRATEGY_ID, ACCOUNT_ID, TimeUnit.MILLISECONDS.toNanos(codMillis), counters);
    }

    private List<Received> statuses() {
        return nonHeartbeats().stream()
                .filter(message -> message.templateId() == OrderStatusDecoder.TEMPLATE_ID)
                .toList();
    }

    private static OrderStatusDecoder status(final Received received) {
        final OrderStatusDecoder decoder = new OrderStatusDecoder();
        decoder.wrap(
                received.message(),
                SbeMessage.HEADER_LENGTH,
                OrderStatusDecoder.BLOCK_LENGTH,
                OrderStatusDecoder.SCHEMA_VERSION);
        return decoder;
    }

    // ----- session side -----

    private static SessionRegistry.SessionRecord record(final String status) {
        return new SessionRegistry.SessionRecord(
                SLOT, status, STRATEGY_ID, "live", Set.of(LISTING_ID), Map.of(EXCHANGE_ID, ACCOUNT_ID), 1);
    }

    private void hello(final long lastReportSeq, final int pages) throws InterruptedException {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[512]);
        final SessionHelloEncoder hello = new SessionHelloEncoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .sessionSeq(SLOT)
                .sessionId(SESSION_ID)
                .strategyId(STRATEGY_ID)
                .mode(Mode.LIVE)
                .venueAccountId(ACCOUNT_ID)
                .protocolVersion(SessionHelloEncoder.SCHEMA_VERSION)
                .replyIp(HostAddress.pack("127.0.0.1") & 0xFFFF_FFFFL)
                .replyPort(REPLY_PORT)
                .lastReportSeq(lastReportSeq)
                .lastReportEpoch(lastReportSeq > 0 ? 99 : 0)
                .codTimeoutMs((int) COD_MILLIS)
                .maxQueueMs(2_000)
                .openOrderPages(pages)
                .maxOpenOrders(30)
                .maxNewPerSecond(5);
        hello.listingsCount(1).next().exchangeId(EXCHANGE_ID).securityId(SECURITY_ID);
        offer(buffer, MessageHeaderEncoder.ENCODED_LENGTH + hello.encodedLength());
    }

    private void helloOrders(final int page, final boolean last, final long[]... held) throws InterruptedException {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[1024]);
        final SessionHelloOrdersEncoder orders = new SessionHelloOrdersEncoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .sessionSeq(SLOT)
                .page(page)
                .last((short) (last ? 1 : 0));
        final SessionHelloOrdersEncoder.OrdersEncoder entries = orders.ordersCount(held.length);
        for (final long[] order : held) {
            entries.next()
                    .clientOidCounter(order[0])
                    .exchangeId(EXCHANGE_ID)
                    .securityId(SECURITY_ID)
                    .side(group.gnometrading.schemas.gateway.Side.Bid)
                    .state(OrderState.get((short) order[1]))
                    .price(1)
                    .size(3_000_000)
                    // A third element names the order at the venue, as its reports did.
                    .exchangeOrderId(order.length > 2 ? "s42-" + order[0] : "")
                    .venueOrderId("")
                    .cumulativeQty(0)
                    .notional(0)
                    .fees(0);
        }
        offer(buffer, MessageHeaderEncoder.ENCODED_LENGTH + orders.encodedLength());
    }

    private void heartbeat(final long echoed) throws InterruptedException {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        new SessionHeartbeatEncoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .sessionSeq(SLOT)
                .sentNanos(System.nanoTime())
                .lastGatewayHeartbeatSeq(echoed)
                .lastReportSeq(0);
        offer(buffer, MessageHeaderEncoder.ENCODED_LENGTH + SessionHeartbeatEncoder.BLOCK_LENGTH);
    }

    private void sendOrder() throws InterruptedException {
        final Order order = new Order();
        order.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(1)
                .size(1)
                .side(Side.Bid);
        order.encodeClientOid(1, STRATEGY_ID);
        offer(order.buffer, order.totalMessageSize());
    }

    /** A new publication, as a session's process that restarted its link would have: a new image. */
    private void reconnect() throws InterruptedException {
        this.toGateway.close();
        this.toGateway = this.sessionAeron
                .aeron()
                .addPublication(
                        AeronChannels.gatewayIngress("localhost", INGRESS_PORT), AeronStreamIds.GATEWAY_INGRESS);
    }

    private void offer(final UnsafeBuffer buffer, final int length) throws InterruptedException {
        await(() -> this.toGateway.offer(buffer, 0, length) > 0, "the offer");
    }

    private void report(final long counter) {
        report(counter, ExecType.NEW, group.gnometrading.schemas.OrderStatus.NEW, 0, 0, 0, 0);
    }

    private void report(
            final long counter,
            final ExecType type,
            final group.gnometrading.schemas.OrderStatus status,
            final long filled,
            final long price,
            final long cumulative,
            final long fee) {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .execType(type)
                .orderStatus(status)
                .filledQty(filled)
                .fillPrice(price)
                .cumulativeQty(cumulative)
                .leavesQty(5_000_000 - cumulative)
                .fee(fee);
        report.encodeClientOid(counter, STRATEGY_ID);
        final int index = this.account.reports.tryClaim();
        final AccountReport entry = this.account.reports.indexAt(index);
        entry.sessionSlot = SLOT;
        entry.report.buffer.putBytes(0, report.buffer, 0, report.totalMessageSize());
        this.account.reports.commit(index);
    }

    // ----- waiting -----

    private void awaitReady() throws InterruptedException {
        await(() -> this.status.state() == GatewayState.READY, "the gateway to be ready");
    }

    private SessionWelcomeDecoder awaitWelcome() throws InterruptedException {
        await(() -> !welcomes().isEmpty(), "a welcome");
        final Received welcome = welcomes().get(welcomes().size() - 1);
        final SessionWelcomeDecoder decoder = new SessionWelcomeDecoder();
        decoder.wrap(
                welcome.message(),
                SbeMessage.HEADER_LENGTH,
                SessionWelcomeDecoder.BLOCK_LENGTH,
                SessionWelcomeDecoder.SCHEMA_VERSION);
        return decoder;
    }

    private ReHelloReason awaitReHello() throws InterruptedException {
        await(
                () -> nonHeartbeats().stream()
                        .anyMatch(message -> message.templateId() == ReHelloRequiredDecoder.TEMPLATE_ID),
                "a re-hello request");
        final Received reHello = nonHeartbeats().stream()
                .filter(message -> message.templateId() == ReHelloRequiredDecoder.TEMPLATE_ID)
                .findFirst()
                .orElseThrow();
        final ReHelloRequiredDecoder decoder = new ReHelloRequiredDecoder();
        decoder.wrap(
                reHello.message(),
                SbeMessage.HEADER_LENGTH,
                ReHelloRequiredDecoder.BLOCK_LENGTH,
                ReHelloRequiredDecoder.SCHEMA_VERSION);
        return decoder.reason();
    }

    private static void assertAccepted(final SessionWelcomeDecoder welcome) {
        assertEquals(1, welcome.accepted(), "refused: " + welcome.refusal());
        assertEquals(99, welcome.gatewayEpoch());
    }

    private static void assertRefused(final SessionWelcomeDecoder welcome, final HelloRefusal refusal) {
        assertEquals(0, welcome.accepted());
        assertEquals(refusal, welcome.refusal());
    }

    private List<Received> welcomes() {
        return nonHeartbeats().stream()
                .filter(message -> message.templateId() == SessionWelcomeDecoder.TEMPLATE_ID)
                .toList();
    }

    private List<Received> reports() {
        return nonHeartbeats().stream()
                .filter(message -> message.templateId() == OrderExecutionReportDecoder.TEMPLATE_ID)
                .toList();
    }

    private List<Received> nonHeartbeats() {
        this.replies.poll(
                (buffer, offset, length, header) -> {
                    final UnsafeBuffer copy = new UnsafeBuffer(new byte[length]);
                    copy.putBytes(0, buffer, offset, length);
                    this.received.add(
                            new Received(SbeMessage.templateId(buffer, offset), header.reservedValue(), copy));
                },
                64);
        return this.received.stream()
                .filter(message -> message.templateId() != GatewayHeartbeatDecoder.TEMPLATE_ID)
                .toList();
    }

    /** The lane's commands about orders; those introducing or ending a session are kept in {@link #sessionCommands}. */
    private List<LaneCommand> drainCommands() {
        final List<LaneCommand> commands = new ArrayList<>();
        this.account.commands.read(
                command -> {
                    final LaneCommand copy = new LaneCommand();
                    copy.copyFrom(command);
                    final boolean aboutSession =
                            command.kind == LaneCommand.SESSION || command.kind == LaneCommand.SESSION_ENDED;
                    (aboutSession ? this.sessionCommands : commands).add(copy);
                },
                Integer.MAX_VALUE);
        return commands;
    }

    private LaneCommand awaitCommand() throws InterruptedException {
        final List<LaneCommand> commands = new ArrayList<>();
        await(
                () -> {
                    commands.addAll(drainCommands());
                    return !commands.isEmpty();
                },
                "a lane command");
        return commands.get(0);
    }

    private void runFor(final long millis) throws InterruptedException {
        final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < until) {
            pass();
        }
    }

    private void pass() throws InterruptedException {
        this.ingress.doWork();
        this.control.doWork();
        this.egress.doWork();
        Thread.sleep(1);
    }

    private void await(final BooleanSupplier condition, final String what) throws InterruptedException {
        final long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            pass();
        }
    }
}
