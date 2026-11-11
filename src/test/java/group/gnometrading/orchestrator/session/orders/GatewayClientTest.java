package group.gnometrading.orchestrator.session.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.AeronStreamIds;
import group.gnometrading.orchestrator.aeron.HostAddress;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.gateway.GatewayHeartbeatEncoder;
import group.gnometrading.schemas.gateway.GatewayState;
import group.gnometrading.schemas.gateway.GoodbyeReason;
import group.gnometrading.schemas.gateway.HelloRefusal;
import group.gnometrading.schemas.gateway.MessageHeaderDecoder;
import group.gnometrading.schemas.gateway.MessageHeaderEncoder;
import group.gnometrading.schemas.gateway.OrderState;
import group.gnometrading.schemas.gateway.OrderStatusEncoder;
import group.gnometrading.schemas.gateway.ReHelloReason;
import group.gnometrading.schemas.gateway.ReHelloRequiredEncoder;
import group.gnometrading.schemas.gateway.SessionGoodbyeDecoder;
import group.gnometrading.schemas.gateway.SessionHeartbeatDecoder;
import group.gnometrading.schemas.gateway.SessionHelloDecoder;
import group.gnometrading.schemas.gateway.SessionHelloOrdersDecoder;
import group.gnometrading.schemas.gateway.SessionWelcomeEncoder;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import io.aeron.Publication;
import io.aeron.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The session's gateway client against a scripted gateway over a real media driver. */
class GatewayClientTest {

    private static final int INGRESS_PORT = 40976;
    private static final long SLOT = 42;
    private static final int EXCHANGE_ID = 1;
    private static final long SECURITY_ID = 7;
    private static final int STRATEGY_ID = 3;
    private static final long EPOCH = 99;

    // Where service-endpoints says the gateway is; a test points it elsewhere, as a crashed gateway's stale entry
    // would.
    private volatile int resolvedPort = INGRESS_PORT;

    private final AeronRuntime sessionAeron =
            AeronRuntime.launch("client-session", AeronProfile.GATEWAY, Throwable::printStackTrace);
    private final AeronRuntime gatewayAeron =
            AeronRuntime.launch("client-gateway", AeronProfile.GATEWAY, Throwable::printStackTrace);
    private final Subscription ingress = this.gatewayAeron
            .aeron()
            .addSubscription(AeronChannels.gatewayIngress("127.0.0.1", INGRESS_PORT), AeronStreamIds.GATEWAY_INGRESS);
    private Publication toSession;

    private final GlobalSequence sequence = new GlobalSequence();
    private final SequencedRingBuffer<Intent> omsOrders = new SequencedRingBuffer<>(Intent::new, this.sequence);
    private final SequencedRingBuffer<OrderExecutionReport> toOms =
            new SequencedRingBuffer<>(OrderExecutionReport::new, this.sequence);
    private final List<OrderExecutionReport> omsReports = new ArrayList<>();
    private final SequencedPoller omsReader = this.toOms.createPoller((seq, templateId, buffer, length) -> {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.buffer.putBytes(0, buffer, 0, length);
        this.omsReports.add(report);
    });
    private final GatewayLink link = new GatewayLink(
            "live:kalshi:main", EXCHANGE_ID, 5, List.<long[]>of(new long[] {EXCHANGE_ID, SECURITY_ID}), 64);
    private final GatewayClient client = new GatewayClient(
            List.of(this.link),
            this.omsOrders,
            this.toOms,
            new GatewayClient.Identity(SLOT, "0d3c3b4e-5c55-4a3f-9d1c-3a1f2b7c9e10", STRATEGY_ID, 5_000, 2_000),
            64,
            System::nanoTime,
            new SystemEpochNanoClock());
    private final GatewayLinks links = new GatewayLinks(
            new NullLogger(),
            this.sessionAeron.aeron(),
            "127.0.0.1",
            List.of(this.link),
            key -> new GatewayLinks.Endpoint("127.0.0.1", this.resolvedPort),
            System::nanoTime);
    private final List<UnsafeBuffer> atGateway = new ArrayList<>();
    private long gatewayHeartbeatSeq;

    @AfterEach
    void close() throws Exception {
        this.links.close();
        if (this.toSession != null) {
            this.toSession.close();
        }
        this.sessionAeron.close();
        this.gatewayAeron.close();
    }

    @Test
    void anOrderWhileTheLinkIsDownIsRefusedButACancelWaitsForIt() throws Exception {
        sendOrder(1);
        sendCancel(9);
        await(() -> this.omsReports.size() == 1, "the refusal");
        assertEquals(ExecType.REJECT, this.omsReports.get(0).decoder.execType());
        assertEquals(
                RejectReason.GATEWAY_UNAVAILABLE, this.omsReports.get(0).decoder.rejectReason());

        connectAndWelcome();
        await(() -> received(CancelOrderDecoder.TEMPLATE_ID).size() == 1, "the held cancel");
        assertTrue(received(OrderDecoder.TEMPLATE_ID).isEmpty(), "the refused order never goes");
    }

    @Test
    void theHelloNamesTheSessionItsReplyPortListingsAndOpenOrders() throws Exception {
        connectAndWelcome();
        sendOrder(1);
        sendOrder(2);
        await(() -> received(OrderDecoder.TEMPLATE_ID).size() == 2, "both orders at the gateway");
        report(1, ExecType.NEW, OrderStatus.NEW, 0, 0, 0, 0, 1);

        // A re-hello request: the client says hello again, with what it has open.
        this.atGateway.clear();
        sendToSession(reHello(), 0);
        await(() -> received(SessionHelloOrdersDecoder.TEMPLATE_ID).size() == 1, "the hello's page");
        final SessionHelloDecoder hello =
                decode(received(SessionHelloDecoder.TEMPLATE_ID).get(0), new SessionHelloDecoder());
        assertEquals(SLOT, hello.sessionSeq());
        assertEquals(5, hello.venueAccountId());
        assertEquals(1, hello.lastReportSeq(), "the newest report it has");
        assertEquals(HostAddress.pack("127.0.0.1") & 0xFFFF_FFFFL, hello.replyIp());
        assertEquals(1, hello.openOrderPages());
        final SessionHelloDecoder.ListingsDecoder listings = hello.listings();
        assertEquals(1, listings.count());
        assertEquals(SECURITY_ID, listings.next().securityId());
        final SessionHelloOrdersDecoder page =
                decode(received(SessionHelloOrdersDecoder.TEMPLATE_ID).get(0), new SessionHelloOrdersDecoder());
        final List<String> orders = new ArrayList<>();
        for (final SessionHelloOrdersDecoder.OrdersDecoder order : page.orders()) {
            orders.add(order.clientOidCounter() + " " + order.state());
        }
        orders.sort(String::compareTo);
        assertEquals(List.of("1 NEW", "2 PENDING_NEW"), orders);
    }

    @Test
    void reportsReachTheOmsAndAnOrderStatusBecomesTheReportsItLacks() throws Exception {
        connectAndWelcome();
        sendOrder(1);
        await(() -> received(OrderDecoder.TEMPLATE_ID).size() == 1, "the order at the gateway");
        report(1, ExecType.NEW, OrderStatus.NEW, 0, 0, 0, 0, 1);
        await(() -> this.omsReports.size() == 1, "the acknowledgement");

        sendToSession(status(1, OrderState.PARTIALLY_FILLED, 2_000_000, 3_000_000, 800_000_000, 10, 2), 2);
        await(() -> this.omsReports.size() == 2, "the fill it lacked");
        final OrderExecutionReport fill = this.omsReports.get(1);
        assertEquals(ExecType.PARTIAL_FILL, fill.decoder.execType());
        assertEquals(2_000_000, fill.decoder.filledQty());
        assertEquals(400_000_000, fill.decoder.fillPrice(), "the fill's average price");
        assertEquals(10, fill.decoder.fee());
        assertEquals(2_000_000, fill.decoder.cumulativeQty());

        sendToSession(status(1, OrderState.PARTIALLY_FILLED, 2_000_000, 3_000_000, 800_000_000, 10, 3), 3);
        sendToSession(status(1, OrderState.CANCELED, 2_000_000, 0, 800_000_000, 10, 4), 4);
        await(() -> this.omsReports.size() == 3, "the cancel");
        assertEquals(
                ExecType.CANCEL, this.omsReports.get(2).decoder.execType(), "a status it agrees with adds nothing");
        assertEquals(0, this.client.openOrders());
        assertEquals(4, this.link.lastReportSeq);
    }

    @Test
    void aReportSentTwiceAroundAReconnectReachesTheOmsOnce() throws Exception {
        connectAndWelcome();
        sendOrder(1);
        await(() -> received(OrderDecoder.TEMPLATE_ID).size() == 1, "the order at the gateway");
        report(1, ExecType.NEW, OrderStatus.NEW, 0, 0, 0, 0, 1);
        report(1, ExecType.NEW, OrderStatus.NEW, 0, 0, 0, 0, 1);
        report(1, ExecType.PARTIAL_FILL, OrderStatus.PARTIALLY_FILLED, 1_000_000, 400_000_000, 1_000_000, 0, 2);
        await(() -> this.omsReports.size() == 2, "the acknowledgement and the fill");
        Thread.sleep(100);
        this.client.doWork();
        this.omsReader.poll();
        assertEquals(2, this.omsReports.size(), "the repeat dropped");
    }

    @Test
    void aRefusedHelloTakesTheLinkDownWithTheReason() throws Exception {
        connect();
        sendToSession(welcome(false, HelloRefusal.SESSION_NOT_RUNNING), 0);
        await(() -> this.link.refusal == HelloRefusal.SESSION_NOT_RUNNING, "the refusal");
        assertEquals(GatewayLink.DOWN, this.link.state);
    }

    @Test
    void aGatewayGoneQuietTakesTheLinkDownAndOrdersAreRefusedUntilItIsBack() throws Exception {
        connectAndWelcome();
        final long until = System.nanoTime() + GatewayClient.GATEWAY_SILENCE_NANOS + TimeUnit.MILLISECONDS.toNanos(200);
        await(() -> this.link.state == GatewayLink.DOWN || System.nanoTime() > until, "the link to go down");
        assertEquals(GatewayLink.DOWN, this.link.state);
        sendOrder(1);
        await(() -> !this.omsReports.isEmpty(), "the refusal");
        assertEquals(
                RejectReason.GATEWAY_UNAVAILABLE, this.omsReports.get(0).decoder.rejectReason());
    }

    @Test
    void theClientHeartbeatsEchoingTheGatewaysLatest() throws Exception {
        connectAndWelcome();
        gatewayHeartbeat();
        gatewayHeartbeat();
        final long latest = this.gatewayHeartbeatSeq;
        await(
                () -> received(SessionHeartbeatDecoder.TEMPLATE_ID).stream()
                        .anyMatch(beat ->
                                decode(beat, new SessionHeartbeatDecoder()).lastGatewayHeartbeatSeq() == latest),
                "an echo of the gateway's latest heartbeat");
    }

    @Test
    void closingSaysGoodbyeAfterWhatIsStillHeld() throws Exception {
        connectAndWelcome();
        final CancelOrder held = new CancelOrder();
        held.encoder.exchangeId(EXCHANGE_ID).securityId(SECURITY_ID);
        held.encodeClientOid(5, STRATEGY_ID);
        this.link.pending.offer(held.buffer, 0, held.totalMessageSize());
        this.atGateway.clear();

        this.client.onClose();

        awaitAtGateway(() -> !received(SessionGoodbyeDecoder.TEMPLATE_ID).isEmpty(), "the goodbye");
        final List<Integer> order = this.atGateway.stream()
                .map(message -> SbeMessage.templateId(message, 0))
                .filter(id -> id == CancelOrderDecoder.TEMPLATE_ID || id == SessionGoodbyeDecoder.TEMPLATE_ID)
                .toList();
        assertEquals(List.of(CancelOrderDecoder.TEMPLATE_ID, SessionGoodbyeDecoder.TEMPLATE_ID), order);
        final SessionGoodbyeDecoder goodbye =
                decode(received(SessionGoodbyeDecoder.TEMPLATE_ID).get(0), new SessionGoodbyeDecoder());
        assertEquals(SLOT, goodbye.sessionSeq());
        assertEquals(GoodbyeReason.SESSION_EXIT, goodbye.reason());

        // The control thread runs on while the session closes; a link it reopened would close the publication the
        // goodbye went out on and could say hello again.
        final Publication saidGoodbyeOn = this.link.publication;
        final long pastRetry = System.nanoTime() + GatewayLinks.RETRY_NANOS + TimeUnit.MILLISECONDS.toNanos(50);
        while (System.nanoTime() - pastRetry < 0) {
            this.links.run();
            Thread.sleep(10);
        }
        assertEquals(GatewayLink.GONE, this.link.state);
        assertSame(saidGoodbyeOn, this.link.publication);
        assertFalse(saidGoodbyeOn.isClosed());
    }

    @Test
    void aLinkNothingAnswersOnIsLookedUpAgain() throws Exception {
        this.resolvedPort = INGRESS_PORT + 1;
        this.links.run();
        assertEquals(GatewayLink.CONNECTING, this.link.state);

        await(() -> this.link.state == GatewayLink.DOWN, "the connect timeout");
        assertTrue(received(SessionHelloDecoder.TEMPLATE_ID).isEmpty());

        this.resolvedPort = INGRESS_PORT;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (this.link.state != GatewayLink.CONNECTING && System.nanoTime() < deadline) {
            this.links.run();
            Thread.sleep(10);
        }
        assertEquals(GatewayLink.CONNECTING, this.link.state);
        await(() -> !received(SessionHelloDecoder.TEMPLATE_ID).isEmpty(), "the hello at the gateway's real address");
    }

    @Test
    void aLinkNotReadyGetsNoGoodbye() throws Exception {
        connect();
        sendToSession(welcome(false, HelloRefusal.SESSION_NOT_RUNNING), 0);
        await(() -> this.link.state == GatewayLink.DOWN, "the refusal");
        this.atGateway.clear();

        this.client.onClose();

        final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
        awaitAtGateway(() -> System.nanoTime() > until, "a quiet while");
        assertTrue(received(SessionGoodbyeDecoder.TEMPLATE_ID).isEmpty());
    }

    // ----- the gateway's side -----

    private void connect() throws InterruptedException {
        this.links.run();
        assertEquals(GatewayLink.CONNECTING, this.link.state);
        await(() -> !received(SessionHelloDecoder.TEMPLATE_ID).isEmpty(), "the hello");
        this.toSession = this.gatewayAeron
                .aeron()
                .addPublication(
                        AeronChannels.gatewayReply("127.0.0.1", this.link.replyPort), AeronStreamIds.GATEWAY_REPLY);
        await(this.toSession::isConnected, "the reply publication");
    }

    private void connectAndWelcome() throws InterruptedException {
        connect();
        sendToSession(welcome(true, HelloRefusal.NONE), 0);
        gatewayHeartbeat();
        await(() -> this.link.state == GatewayLink.READY, "the link to be ready");
    }

    private void gatewayHeartbeat() throws InterruptedException {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        final GatewayHeartbeatEncoder beat = new GatewayHeartbeatEncoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .gatewayEpoch(EPOCH)
                .heartbeatSeq(++this.gatewayHeartbeatSeq)
                .sentNanos(1)
                .state(GatewayState.READY);
        beat.accountsCount(0);
        sendToSession(new Message(buffer, MessageHeaderEncoder.ENCODED_LENGTH + beat.encodedLength()), 0);
    }

    private record Message(UnsafeBuffer buffer, int length) {}

    private static Message welcome(final boolean accepted, final HelloRefusal refusal) {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        final SessionWelcomeEncoder welcome = new SessionWelcomeEncoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .sessionSeq(SLOT)
                .accepted((short) (accepted ? 1 : 0))
                .refusal(refusal)
                .gatewayEpoch(EPOCH)
                .budgetShareBps(0);
        return new Message(buffer, MessageHeaderEncoder.ENCODED_LENGTH + welcome.encodedLength());
    }

    private static Message reHello() {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[64]);
        final ReHelloRequiredEncoder reHello = new ReHelloRequiredEncoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .sessionSeq(SLOT)
                .reason(ReHelloReason.COD_FIRED);
        return new Message(buffer, MessageHeaderEncoder.ENCODED_LENGTH + reHello.encodedLength());
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static Message status(
            final long counter,
            final OrderState state,
            final long cumulative,
            final long leaves,
            final long notional,
            final long fees,
            final long reportSeq) {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        final OrderStatusEncoder status = new OrderStatusEncoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .sessionSeq(SLOT)
                .reportSeq(reportSeq)
                .clientOidCounter(counter)
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .state(state)
                .venueOrderId("")
                .cumulativeQty(cumulative)
                .leavesQty(leaves)
                .notional(notional)
                .fees(fees);
        return new Message(buffer, MessageHeaderEncoder.ENCODED_LENGTH + status.encodedLength());
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void report(
            final long counter,
            final ExecType type,
            final OrderStatus status,
            final long filled,
            final long price,
            final long cumulative,
            final long fee,
            final long reportSeq)
            throws InterruptedException {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .execType(type)
                .orderStatus(status)
                .filledQty(filled)
                .fillPrice(price)
                .cumulativeQty(cumulative)
                .leavesQty(3_000_000 - cumulative)
                .fee(fee);
        report.encodeClientOid(counter, STRATEGY_ID);
        sendToSession(new Message(report.buffer, report.totalMessageSize()), reportSeq);
    }

    private void sendToSession(final Message message, final long reportSeq) throws InterruptedException {
        await(
                () -> this.toSession.offer(message.buffer(), 0, message.length(), (term, offset, frame) -> reportSeq)
                        > 0,
                "the reply");
    }

    // ----- the OMS's side -----

    private void sendOrder(final long counter) {
        final Order order = new Order();
        order.encoder
                .exchangeId(EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(400_000_000)
                .size(3_000_000)
                .side(Side.Bid);
        order.encodeClientOid(counter, STRATEGY_ID);
        this.omsOrders.publishRaw(order.buffer, OrderDecoder.TEMPLATE_ID, order.totalMessageSize());
    }

    private void sendCancel(final long counter) {
        final CancelOrder cancel = new CancelOrder();
        cancel.encoder.exchangeId(EXCHANGE_ID).securityId(SECURITY_ID);
        cancel.encodeClientOid(counter, STRATEGY_ID);
        this.omsOrders.publishRaw(cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, cancel.totalMessageSize());
    }

    // ----- plumbing -----

    private List<UnsafeBuffer> received(final int templateId) {
        return this.atGateway.stream()
                .filter(message -> SbeMessage.templateId(message, 0) == templateId)
                .toList();
    }

    private static <T> T decode(final UnsafeBuffer message, final T decoder) {
        final MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(message, 0);
        try {
            decoder.getClass()
                    .getMethod("wrap", org.agrona.DirectBuffer.class, int.class, int.class, int.class)
                    .invoke(
                            decoder,
                            message,
                            MessageHeaderDecoder.ENCODED_LENGTH,
                            header.blockLength(),
                            header.version());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return decoder;
    }

    /** Reads what reaches the gateway without running the client, as after its close. */
    private void awaitAtGateway(final BooleanSupplier condition, final String what) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            this.ingress.poll(
                    (buffer, offset, length, header) -> {
                        final UnsafeBuffer copy = new UnsafeBuffer(new byte[length]);
                        copy.putBytes(0, buffer, offset, length);
                        this.atGateway.add(copy);
                    },
                    64);
            Thread.sleep(1);
        }
    }

    private void await(final BooleanSupplier condition, final String what) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                this.client.doWork();
                this.omsReader.poll();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            this.ingress.poll(
                    (buffer, offset, length, header) -> {
                        final UnsafeBuffer copy = new UnsafeBuffer(new byte[length]);
                        copy.putBytes(0, buffer, offset, length);
                        this.atGateway.add(copy);
                    },
                    64);
            Thread.sleep(1);
        }
    }
}
