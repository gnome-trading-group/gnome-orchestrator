package group.gnometrading.orchestrator.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.account.reader.AccountState;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.AeronStreamIds;
import group.gnometrading.orchestrator.gateway.FakeOrderQuery;
import group.gnometrading.orchestrator.gateway.core.AccountLink;
import group.gnometrading.orchestrator.gateway.core.GatewayMessage;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.GatewayStatus;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.schemas.gateway.GatewayHeartbeatDecoder;
import group.gnometrading.schemas.gateway.GatewayState;
import group.gnometrading.schemas.gateway.MessageHeaderEncoder;
import group.gnometrading.schemas.gateway.OrderStatusDecoder;
import group.gnometrading.schemas.gateway.SessionHeartbeatEncoder;
import group.gnometrading.schemas.gateway.SessionHelloEncoder;
import group.gnometrading.schemas.gateway.SessionWelcomeDecoder;
import group.gnometrading.schemas.gateway.SessionWelcomeEncoder;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Ingress and egress over a real media driver: what a session sends reaches its account's lane, and back. */
class GatewayLinkTest {

    private static final int INGRESS_PORT = 40970;
    private static final int REPLY_PORT = 40971;
    private static final long SLOT = 42;
    private static final int EXCHANGE_ID = 1;
    private static final long LEASED = 7;
    private static final long NOT_LEASED = 8;

    private final AeronRuntime gatewayAeron =
            AeronRuntime.launch("link-gateway", AeronProfile.GATEWAY, Throwable::printStackTrace);
    private final AeronRuntime sessionAeron =
            AeronRuntime.launch("link-session", AeronProfile.GATEWAY, Throwable::printStackTrace);
    private final Subscription ingressSubscription = this.gatewayAeron
            .aeron()
            .addSubscription(AeronChannels.gatewayIngress("localhost", INGRESS_PORT), AeronStreamIds.GATEWAY_INGRESS);
    private final Publication toGateway = this.sessionAeron
            .aeron()
            .addPublication(AeronChannels.gatewayIngress("localhost", INGRESS_PORT), AeronStreamIds.GATEWAY_INGRESS);
    private final Subscription replies = this.sessionAeron
            .aeron()
            .addSubscription(AeronChannels.gatewayReply("localhost", REPLY_PORT), AeronStreamIds.GATEWAY_REPLY);
    private final Publication replyPublication = this.gatewayAeron
            .aeron()
            .addPublication(AeronChannels.gatewayReply("localhost", REPLY_PORT), AeronStreamIds.GATEWAY_REPLY);

    private final TestAccount account = new TestAccount();
    private final SessionTable sessions = new SessionTable();
    private final GatewayStatus status = new GatewayStatus(99);
    private final ManyToOneRingBuffer<AccountReport> rejectRing =
            new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 64);
    private final ManyToOneRingBuffer<AccountReport> accountReports =
            new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 256);
    private final ManyToOneRingBuffer<GatewayMessage> toControl =
            new ManyToOneRingBuffer<>(GatewayMessage[]::new, GatewayMessage::new, 16);
    private final ManyToOneRingBuffer<GatewayMessage> fromControl =
            new ManyToOneRingBuffer<>(GatewayMessage[]::new, GatewayMessage::new, 16);
    private final GatewayIngress ingress = new GatewayIngress(
            this.ingressSubscription,
            this.sessions,
            this.status,
            new GatewayRejects(this.rejectRing, new SystemEpochNanoClock()),
            this.toControl,
            System::nanoTime,
            JournalRecorder.NONE);
    private final GatewayEgress egress = new GatewayEgress(
            this.sessions,
            this.status,
            List.of(this.accountReports, this.rejectRing),
            this.fromControl,
            System::nanoTime,
            new SystemEpochNanoClock(),
            JournalRecorder.NONE);
    private final List<Received> received = new ArrayList<>();
    private final FragmentHandler replyHandler = (buffer, offset, length, header) -> {
        final UnsafeBuffer copy = new UnsafeBuffer(new byte[length]);
        copy.putBytes(0, buffer, offset, length);
        this.received.add(new Received(SbeMessage.templateId(buffer, offset), header.reservedValue(), copy));
    };

    private record Received(int templateId, long reservedValue, UnsafeBuffer message) {}

    private static final class TestAccount implements AccountLink {
        final FakeOrderQuery query = new FakeOrderQuery();
        final ManyToOneRingBuffer<LaneCommand> commands =
                new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, 64);
        volatile AccountState state = AccountState.READY;

        @Override
        public int accountId() {
            return 5;
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
            return 0;
        }

        @Override
        public VenueOrderQuery orderQuery() {
            return this.query;
        }

        @Override
        public AccountLink.Markets markets() {
            return (listing, fees) -> {};
        }
    }

    @AfterEach
    void close() {
        this.sessionAeron.close();
        this.gatewayAeron.close();
    }

    @Test
    void anOrderFromALinkedSessionReachesItsAccountsLaneAndItsReportComesBackNumbered() throws Exception {
        final GatewaySession session = linkedSession();
        sendOrder(1, LEASED);
        final List<LaneCommand> commands = awaitCommands(1);
        final LaneCommand command = commands.get(0);
        assertEquals(LaneCommand.NEW, command.kind);
        assertEquals(SLOT, command.sessionSlot);
        assertEquals(1, command.clientOidCounter);
        assertEquals(3, command.clientOidStrategyId);
        assertEquals(LEASED, command.securityId);
        assertEquals(Side.Bid, command.side);
        assertEquals(400_000_000L, command.price);
        assertTrue(command.postOnly);

        report(this.accountReports, SLOT, 1, ExecType.NEW);
        report(this.accountReports, SLOT, 1, ExecType.FILL);
        final List<Received> reports = awaitReports(2);
        assertEquals(1, reports.get(0).reservedValue());
        assertEquals(2, reports.get(1).reservedValue());
        assertEquals(ExecType.FILL, execType(reports.get(1)));
        assertEquals(2, session.replay.newest());
    }

    @Test
    void anOrderFromAnImageNoSessionIsLinkedToIsDropped() throws Exception {
        session(GatewaySession.ACTIVE);
        await(this.toGateway::isConnected, "the session to join");
        sendOrder(1, LEASED);
        await(() -> this.ingress.unlinked() == 1, "the order to be dropped");
        assertEquals(0, drainCommands().size());
    }

    @Test
    void anOrderOnAListingTheSessionDoesntLeaseIsRefusedStraightBack() throws Exception {
        linkedSession();
        sendOrder(1, NOT_LEASED);
        assertEquals(
                RejectReason.LISTING_NOT_LEASED, rejectReason(awaitReports(1).get(0)));
        assertEquals(0, drainCommands().size());
    }

    @Test
    void aDegradedAccountRefusesNewOrdersButStillTakesCancels() throws Exception {
        linkedSession();
        this.account.state = AccountState.CATCHING_UP;
        sendOrder(1, LEASED);
        assertEquals(RejectReason.GATEWAY_DEGRADED, rejectReason(awaitReports(1).get(0)));
        sendCancel(1);
        assertEquals(LaneCommand.CANCEL, awaitCommands(1).get(0).kind);
    }

    @Test
    void aGatewayNotYetReadyRefusesOrders() throws Exception {
        linkedSession();
        this.status.state(GatewayState.STARTING);
        sendOrder(1, LEASED);
        assertEquals(
                RejectReason.GATEWAY_NOT_READY, rejectReason(awaitReports(1).get(0)));
    }

    @Test
    void aSessionNotTradingHasItsOrdersRefusedAndItsCancelsAnswered() throws Exception {
        final GatewaySession session = linkedSession();
        session.state = GatewaySession.UNLINKED;
        sendOrder(1, LEASED);
        sendCancel(1);
        final List<Received> reports = awaitReports(2);
        assertEquals(RejectReason.SESSION_NOT_REGISTERED, rejectReason(reports.get(0)));
        assertEquals(ExecType.CANCEL_REJECT, execType(reports.get(1)));
        assertEquals(0, drainCommands().size());
    }

    @Test
    void reportsWaitForTheLinkAndAWelcomeGoesOutFirstThenOnlyWhatTheSessionLacks() throws Exception {
        final GatewaySession session = session(GatewaySession.HELLO_PENDING);
        for (int i = 1; i <= 3; i++) {
            report(this.accountReports, SLOT, i, ExecType.NEW);
        }
        await(() -> session.replay.newest() == 3, "the reports to wait in the ring");

        session.reply = this.replyPublication;
        session.state = GatewaySession.ACTIVE;
        final GatewayMessage welcome = new GatewayMessage();
        final int length = welcome(welcome.buffer);
        controlMessage(GatewayMessage.UNNUMBERED, 1, welcome.buffer, length);

        final List<Received> got = awaitReceived(3, false);
        assertEquals(SessionWelcomeDecoder.TEMPLATE_ID, got.get(0).templateId());
        assertEquals(2, got.get(1).reservedValue(), "the session had report 1");
        assertEquals(3, got.get(2).reservedValue());
    }

    @Test
    void aSessionPastItsRingIsSentWhereEachOrderStandsWithEveryFillCounted() throws Exception {
        final GatewaySession session = session(GatewaySession.HELLO_PENDING);
        report(this.accountReports, SLOT, 1, ExecType.NEW);
        // Eighty fills of one unit at 40c: more than the ring holds.
        for (int i = 1; i <= 80; i++) {
            fill(1, i);
        }
        await(() -> session.replay.newest() == 81, "every report numbered");
        assertTrue(!session.replay.holds(1), "the first reports have left the ring");

        session.reply = this.replyPublication;
        session.state = GatewaySession.ACTIVE;
        final List<Received> got = awaitReceived(1, false);
        assertEquals(OrderStatusDecoder.TEMPLATE_ID, got.get(0).templateId(), "statuses, not what is left of the ring");
        final OrderStatusDecoder status = new OrderStatusDecoder();
        status.wrap(got.get(0).message(), SbeMessage.HEADER_LENGTH, OrderStatusDecoder.BLOCK_LENGTH, 0);
        assertEquals(80_000_000L, status.cumulativeQty());
        assertEquals(80L * 400_000_000L, status.notional(), "every fill, not only those still in the ring");
        assertEquals(egressGaps(), 1);
    }

    private long egressGaps() {
        return this.egress.replayGaps();
    }

    private void fill(final long counter, final long cumulativeUnits) {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(LEASED)
                .execType(ExecType.PARTIAL_FILL)
                .orderStatus(group.gnometrading.schemas.OrderStatus.PARTIALLY_FILLED)
                .filledQty(1_000_000)
                .fillPrice(400_000_000)
                .cumulativeQty(cumulativeUnits * 1_000_000)
                .leavesQty(1_000_000_000)
                .fee(0);
        report.encodeClientOid(counter, 3);
        final int index = this.accountReports.tryClaim();
        final AccountReport entry = this.accountReports.indexAt(index);
        entry.sessionSlot = SLOT;
        entry.report.buffer.putBytes(0, report.buffer, 0, report.totalMessageSize());
        this.accountReports.commit(index);
    }

    @Test
    void heartbeatsGoBothWays() throws Exception {
        final GatewaySession session = linkedSession();
        final List<Received> beats = awaitReceived(1, true);
        final GatewayHeartbeatDecoder decoder = new GatewayHeartbeatDecoder();
        decoder.wrap(
                beats.get(0).message(),
                SbeMessage.HEADER_LENGTH,
                GatewayHeartbeatDecoder.BLOCK_LENGTH,
                GatewayHeartbeatDecoder.SCHEMA_VERSION);
        assertEquals(99, decoder.gatewayEpoch());

        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        new SessionHeartbeatEncoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .sessionSeq(SLOT)
                .sentNanos(1)
                .lastGatewayHeartbeatSeq(17)
                .lastReportSeq(0);
        offer(buffer, MessageHeaderEncoder.ENCODED_LENGTH + SessionHeartbeatEncoder.BLOCK_LENGTH);
        await(() -> session.echoedHeartbeatSeq == 17, "the heartbeat to be noted");
        assertTrue(session.lastHeardNanos > 0);
    }

    @Test
    void aHelloGoesToTheControlThreadWithItsImageAndGarbageEvictsItsSender() throws Exception {
        await(this.toGateway::isConnected, "the session to join");
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        final SessionHelloEncoder hello = new SessionHelloEncoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .sessionSeq(SLOT);
        hello.listingsCount(0);
        offer(buffer, MessageHeaderEncoder.ENCODED_LENGTH + hello.encodedLength());
        offer(new UnsafeBuffer(new byte[] {1, 2, 3}), 3);
        final List<GatewayMessage> messages = new ArrayList<>();
        await(
                () -> {
                    this.toControl.read(message -> messages.add(copy(message)), Integer.MAX_VALUE);
                    return messages.size() == 2;
                },
                "two messages for the control thread");
        assertEquals(GatewayMessage.FROM_SESSION, messages.get(0).kind);
        assertEquals(this.toGateway.sessionId(), messages.get(0).image);
        assertEquals(GatewayMessage.UNREADABLE, messages.get(1).kind);
    }

    // ----- helpers -----

    private GatewaySession session(final int state) {
        final GatewaySession session = new GatewaySession(SLOT, "session-a", 3, this.account, 64);
        final LongHashSet leases = new LongHashSet();
        leases.add(PublishedMap.listingKey(EXCHANGE_ID, LEASED));
        session.leases = leases;
        session.state = state;
        this.sessions.add(session);
        this.status.state(GatewayState.READY);
        return session;
    }

    private GatewaySession linkedSession() throws InterruptedException {
        final GatewaySession session = session(GatewaySession.ACTIVE);
        await(this.toGateway::isConnected, "the session to join");
        this.sessions.link(session, this.toGateway.sessionId());
        session.reply = this.replyPublication;
        return session;
    }

    private void sendOrder(final long counter, final long securityId) throws InterruptedException {
        final Order order = new Order();
        order.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(securityId)
                .price(400_000_000L)
                .size(1_000_000L)
                .side(Side.Bid)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        order.encoder.flags().postOnly(true);
        order.encodeClientOid(counter, 3);
        offer(order.buffer, order.totalMessageSize());
    }

    private void sendCancel(final long counter) throws InterruptedException {
        final CancelOrder cancel = new CancelOrder();
        cancel.encoder.exchangeId(EXCHANGE_ID).securityId(LEASED);
        cancel.encodeClientOid(counter, 3);
        offer(cancel.buffer, cancel.totalMessageSize());
    }

    private void offer(final UnsafeBuffer buffer, final int length) throws InterruptedException {
        await(() -> this.toGateway.offer(buffer, 0, length) > 0, "the offer");
    }

    private static void report(
            final ManyToOneRingBuffer<AccountReport> ring, final long slot, final long counter, final ExecType type) {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.encoder.exchangeId(EXCHANGE_ID).securityId(LEASED).execType(type);
        report.encodeClientOid(counter, 3);
        final int index = ring.tryClaim();
        final AccountReport entry = ring.indexAt(index);
        entry.sessionSlot = slot;
        entry.report.buffer.putBytes(0, report.buffer, 0, report.totalMessageSize());
        ring.commit(index);
    }

    private void controlMessage(final int kind, final long resumeAfter, final UnsafeBuffer buffer, final int length) {
        final int index = this.fromControl.tryClaim();
        final GatewayMessage message = this.fromControl.indexAt(index);
        message.kind = kind;
        message.slot = SLOT;
        message.resumeAfter = resumeAfter;
        message.buffer.putBytes(0, buffer, 0, length);
        message.length = length;
        this.fromControl.commit(index);
    }

    private static int welcome(final UnsafeBuffer buffer) {
        final SessionWelcomeEncoder welcome = new SessionWelcomeEncoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .sessionSeq(SLOT)
                .accepted((short) 1)
                .gatewayEpoch(99);
        return MessageHeaderEncoder.ENCODED_LENGTH + welcome.encodedLength();
    }

    private static GatewayMessage copy(final GatewayMessage message) {
        final GatewayMessage copy = new GatewayMessage();
        copy.kind = message.kind;
        copy.image = message.image;
        copy.length = message.length;
        copy.buffer.putBytes(0, message.buffer, 0, message.length);
        return copy;
    }

    private List<LaneCommand> drainCommands() {
        final List<LaneCommand> commands = new ArrayList<>();
        this.account.commands.read(
                command -> {
                    final LaneCommand copy = new LaneCommand();
                    copy.copyFrom(command);
                    commands.add(copy);
                },
                Integer.MAX_VALUE);
        return commands;
    }

    private List<LaneCommand> awaitCommands(final int count) throws InterruptedException {
        final List<LaneCommand> commands = new ArrayList<>();
        await(
                () -> {
                    commands.addAll(drainCommands());
                    return commands.size() >= count;
                },
                count + " commands");
        return commands;
    }

    /** Execution reports only, heartbeats skipped. */
    private List<Received> awaitReports(final int count) throws InterruptedException {
        return awaitReceived(count, false);
    }

    /** {@code count} heartbeats, or {@code count} messages that aren't heartbeats. */
    private List<Received> awaitReceived(final int count, final boolean heartbeats) throws InterruptedException {
        final List<Received> matching = new ArrayList<>();
        await(
                () -> {
                    this.replies.poll(this.replyHandler, 16);
                    matching.clear();
                    for (final Received message : this.received) {
                        final boolean beat = message.templateId() == GatewayHeartbeatDecoder.TEMPLATE_ID;
                        if (beat == heartbeats) {
                            matching.add(message);
                        }
                    }
                    return matching.size() >= count;
                },
                count + " messages");
        return matching;
    }

    private static ExecType execType(final Received received) {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.buffer.putBytes(0, received.message(), 0, report.totalMessageSize());
        return report.decoder.execType();
    }

    private static RejectReason rejectReason(final Received received) {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.buffer.putBytes(0, received.message(), 0, report.totalMessageSize());
        return report.decoder.rejectReason();
    }

    private void await(final BooleanSupplier condition, final String what) throws InterruptedException {
        final long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            this.ingress.doWork();
            this.egress.doWork();
            Thread.sleep(1);
        }
    }
}
