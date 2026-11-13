package group.gnometrading.orchestrator.session.orders;

import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.latency.LatencyGrid;
import group.gnometrading.metrics.MetricCounters;
import group.gnometrading.orchestrator.aeron.AeronPathAgent;
import group.gnometrading.orchestrator.aeron.HostAddress;
import group.gnometrading.orchestrator.aeron.PendingMessages;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.MessageSchema;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TriggerKind;
import group.gnometrading.schemas.gateway.CancelEndedEncoder;
import group.gnometrading.schemas.gateway.GatewayHeartbeatDecoder;
import group.gnometrading.schemas.gateway.GatewayMessageLimits;
import group.gnometrading.schemas.gateway.GoodbyeReason;
import group.gnometrading.schemas.gateway.MessageHeaderDecoder;
import group.gnometrading.schemas.gateway.MessageHeaderEncoder;
import group.gnometrading.schemas.gateway.OrderState;
import group.gnometrading.schemas.gateway.OrderStatusDecoder;
import group.gnometrading.schemas.gateway.QueryStatus;
import group.gnometrading.schemas.gateway.ReHelloRequiredDecoder;
import group.gnometrading.schemas.gateway.SessionGoodbyeDecoder;
import group.gnometrading.schemas.gateway.SessionGoodbyeEncoder;
import group.gnometrading.schemas.gateway.SessionHeartbeatEncoder;
import group.gnometrading.schemas.gateway.SessionHelloEncoder;
import group.gnometrading.schemas.gateway.SessionHelloOrdersEncoder;
import group.gnometrading.schemas.gateway.SessionWelcomeDecoder;
import group.gnometrading.schemas.gateway.VenueQueryEncoder;
import group.gnometrading.schemas.gateway.VenueQueryResultDecoder;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.Header;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.agrona.DirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The session's order path through order gateways: one thread that reads the OMS's orders and sends each over its
 * venue's {@link GatewayLink}, and reads the gateways' replies into the OMS's execution reports.
 *
 * <ul>
 *   <li>A NEW goes out only on a link that is ready; otherwise it is rejected straight back with
 *       {@code GATEWAY_UNAVAILABLE}, on the session's sequence like any report. A cancel is held until the link is
 *       ready again, so the OMS never sits in a pending cancel the gateway will never see; an amend is refused.
 *   <li>A reply that the OMS's ring has no room for stays in Aeron and is read again; each report's number (the frame's
 *       reserved value) is kept, so a reconnect asks only for what is missing. A gateway's cumulative
 *       {@code OrderStatus} (after it restarts, or past its replay) becomes the ordinary reports that take the OMS from
 *       what it has to what the status says.
 *   <li>Each link says hello with the session's open orders on it once its publication connects, is ready once
 *       welcomed, heartbeats every 100 ms echoing the gateway's latest heartbeat, and goes down when the gateway is
 *       quiet for a second, refuses the session, or says goodbye; asked for a new hello, it says one.
 * </ul>
 *
 * <p>Given latency grids ({@link #measureLatency}), it records, on the wall clock: {@code session.tick_to_order}, from
 * the arrival of the event behind each order, cancel and amend to its hand-off to the link (offered, or queued behind
 * what waits), by exchange, action ({@link #LATENCY_ACTIONS}) and trigger kind; and {@code order_to_ack} and
 * {@code cancel_to_ack}, from that hand-off to the order's first report and to the report answering the cancel.
 *
 * <p>Allocates nothing on the order path; turning an order status into reports may, when the numbers are too large for
 * a long.
 */
public final class GatewayClient implements AeronPathAgent {

    static final long HEARTBEAT_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    static final long GATEWAY_SILENCE_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final long WELCOME_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    // A gateway that crashed leaves its address in service-endpoints until its replacement writes one, so a link opened
    // to it never connects; past this the link goes down and the control thread looks the address up again.
    static final long CONNECT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    // Past this the goodbye is left to the gateway's silence timeout, which cancels and unlinks the same way.
    static final long GOODBYE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final int FRAGMENT_LIMIT = 64;
    private static final long SIZE_SCALE = 1_000_000L;
    private static final int MAX_MESSAGE = 1024;

    /** The action labels {@code session.tick_to_order} is recorded under, by index. */
    public static final String[] LATENCY_ACTIONS = {"NEW", "CANCEL", "MODIFY"};

    private static final int NEW = 0;
    private static final int CANCEL = 1;
    private static final int MODIFY = 2;

    /** Who the session is, as its hello says. */
    /**
     * Who the session is, and what it asks of the gateway: its cancel-on-disconnect timeout, how long its orders may
     * wait for budget, and caps on what it may hold and send (0 for the gateway's own, which also bound what it asks).
     */
    record Identity(
            long sessionSeq,
            String sessionId,
            int strategyId,
            int codTimeoutMs,
            int maxQueueMs,
            int maxOpenOrders,
            int maxNewPerSecond) {

        /** Asking for no caps of its own: the gateway's apply. */
        Identity(
                final long sessionSeq,
                final String sessionId,
                final int strategyId,
                final int codTimeoutMs,
                final int maxQueueMs) {
            this(sessionSeq, sessionId, strategyId, codTimeoutMs, maxQueueMs, 0, 0);
        }
    }

    private final GatewayLink[] links;
    private final Int2ObjectHashMap<GatewayLink> byExchange = new Int2ObjectHashMap<>();
    private final ReplyHandler[] replyHandlers;
    private final GatewayOrders orders;
    // Orders open when a cancel for them was held while their link was down.
    private final LongHashSet heldForOpen = new LongHashSet();
    private final SequencedPoller omsOrders;
    private final SequencedRingBuffer<OrderExecutionReport> toOms;
    private final Identity identity;
    private final LongSupplier nanoClock;
    private final EpochNanoClock wallClock;

    private final Order order = new Order();
    private final CancelOrder cancel = new CancelOrder();
    private final ModifyOrder modify = new ModifyOrder();
    private final OrderExecutionReport inbound = new OrderExecutionReport();
    private final OrderExecutionReport outbound = new OrderExecutionReport();
    private final MessageHeaderDecoder header = new MessageHeaderDecoder();
    private final SessionWelcomeDecoder welcome = new SessionWelcomeDecoder();
    private final GatewayHeartbeatDecoder gatewayHeartbeat = new GatewayHeartbeatDecoder();
    private final OrderStatusDecoder status = new OrderStatusDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final SessionHelloEncoder hello = new SessionHelloEncoder();
    private final SessionHelloOrdersEncoder helloOrders = new SessionHelloOrdersEncoder();
    private final SessionHeartbeatEncoder heartbeat = new SessionHeartbeatEncoder();
    private final SessionGoodbyeEncoder goodbye = new SessionGoodbyeEncoder();
    private final UnsafeBuffer scratch = new UnsafeBuffer(new byte[MAX_MESSAGE]);
    private final VenueQueryEncoder venueQuery = new VenueQueryEncoder();
    private final CancelEndedEncoder cancelEnded = new CancelEndedEncoder();
    private final VenueQueryResultDecoder queryResult = new VenueQueryResultDecoder();
    // A starting session's question for the venue, set by the starting thread before trading; null otherwise.
    private volatile GatewayQuery query;
    private final GatewayOrders.Entry[] helloEntries;
    private final Consumer<GatewayOrders.Entry> helloCollector = this::collect;
    private int helloCount;
    private int helloExchange;
    private long now;
    // Set before the client starts, or never; all three share one MinuteHistograms this thread writes.
    private LatencyGrid tickToOrder;
    private LatencyGrid orderToAck;
    private LatencyGrid cancelToAck;
    // What the client did, counted on its thread for the session's samples.
    private final MetricCounters activity = new MetricCounters(10);
    private final int newOrdersSent = activity.counter("newOrders");
    private final int cancelsSent = activity.counter("cancels");
    private final int modifiesSent = activity.counter("modifies");
    private final int newOrdersRefused = activity.counter("newOrdersRefused");
    private final int cancelsHeld = activity.counter("cancelsHeld");
    private final int cancelsRefused = activity.counter("cancelsRefused");
    private final int modifiesRefused = activity.counter("modifiesRefused");
    private final int reportsIn = activity.counter("reports");
    private final int reportsRetried = activity.counter("reportsRetried");
    private final int pendingPeak = activity.peak("pendingPeak");

    /** @param omsOrders the OMS's outbound ring; the client must be its only reader that writes {@code toOms} */
    public GatewayClient(
            final List<GatewayLink> links,
            final SequencedRingBuffer<?> omsOrders,
            final SequencedRingBuffer<OrderExecutionReport> toOms,
            final Identity identity,
            final int maxOrders,
            final LongSupplier nanoClock,
            final EpochNanoClock wallClock) {
        this.links = links.toArray(new GatewayLink[0]);
        this.replyHandlers = new ReplyHandler[this.links.length];
        for (int i = 0; i < this.links.length; i++) {
            this.byExchange.put(this.links[i].exchangeId, this.links[i]);
            this.replyHandlers[i] = new ReplyHandler(this.links[i]);
        }
        this.orders = new GatewayOrders(maxOrders);
        this.helloEntries = new GatewayOrders.Entry[maxOrders];
        this.omsOrders = omsOrders.createPoller(this::onOmsOrder);
        this.toOms = toOms;
        this.identity = identity;
        this.nanoClock = nanoClock;
        this.wallClock = wallClock;
    }

    /** What the client has sent, held, refused and passed on, counted on its thread for another to read. */
    public MetricCounters activity() {
        return activity;
    }

    /** Records the order path's latencies into these grids; before the client starts. */
    public void measureLatency(
            final LatencyGrid tickToOrder, final LatencyGrid orderToAck, final LatencyGrid cancelToAck) {
        this.tickToOrder = tickToOrder;
        this.orderToAck = orderToAck;
        this.cancelToAck = cancelToAck;
    }

    @Override
    public int doWork() throws Exception {
        this.now = this.nanoClock.getAsLong();
        if (this.tickToOrder != null) {
            this.tickToOrder.histograms().roll(this.wallClock.nanoTime());
        }
        int work = 0;
        for (int i = 0; i < this.links.length; i++) {
            final GatewayLink link = this.links[i];
            final Subscription replies = link.replies;
            if (replies != null) {
                work += replies.controlledPoll(this.replyHandlers[i], FRAGMENT_LIMIT);
            }
            work += step(link);
        }
        final GatewayQuery pending = this.query;
        if (pending != null && !pending.sent) {
            work += sendQuery(pending);
        }
        work += this.omsOrders.poll();
        for (final GatewayLink link : this.links) {
            final int working = this.orders.openOn(link.exchangeId);
            if (link.workingOrders != working) {
                link.workingOrders = working;
            }
        }
        return work;
    }

    /**
     * Tells each gateway the session is leaving, after whatever is still held for it, so the gateway unlinks it at
     * once (cancelling anything still working) instead of after the session's silence. Only a ready link is told: a
     * gateway the session isn't linked to has nothing to unlink.
     */
    @Override
    public void onClose() {
        final long deadline = this.nanoClock.getAsLong() + GOODBYE_TIMEOUT_NANOS;
        for (final GatewayLink link : this.links) {
            if (link.state == GatewayLink.READY && link.publication != null) {
                sayGoodbye(link, deadline);
            }
        }
    }

    private void sayGoodbye(final GatewayLink link, final long deadline) {
        final int length = MessageHeaderEncoder.ENCODED_LENGTH
                + this.goodbye
                        .wrapAndApplyHeader(this.scratch, 0, this.headerEncoder)
                        .sessionSeq(this.identity.sessionSeq())
                        .reason(GoodbyeReason.SESSION_EXIT)
                        .encodedLength();
        while (this.nanoClock.getAsLong() - deadline <= 0) {
            flush(link);
            if (link.pending.isEmpty() && link.publication.offer(this.scratch, 0, length) > 0) {
                link.state = GatewayLink.GONE;
                return;
            }
            Thread.onSpinWait();
        }
    }

    /**
     * Asks the venue, through the gateway, once the query's link is ready; startup recovery's, one at a time, before
     * the OMS sends anything.
     *
     * @return false if another query is still waiting for its answer
     */
    boolean ask(final GatewayQuery question) {
        if (this.query != null) {
            return false;
        }
        this.query = question;
        return true;
    }

    /** Gives up on a query whose answer didn't come: a late one is ignored. */
    void forget(final GatewayQuery question) {
        if (this.query == question) {
            this.query = null;
        }
    }

    private int sendQuery(final GatewayQuery question) {
        final GatewayLink link = this.byExchange.get(question.exchangeId);
        if (link == null) {
            this.query = null;
            question.answered(QueryStatus.FAILED);
            return 1;
        }
        final Publication publication = link.publication;
        if (link.state != GatewayLink.READY || publication == null) {
            return 0;
        }
        final int length;
        if (question.isCancel()) {
            this.cancelEnded
                    .wrapAndApplyHeader(this.scratch, 0, this.headerEncoder)
                    .sessionSeq(this.identity.sessionSeq())
                    .queryId(question.queryId)
                    .exchangeId(question.exchangeId)
                    .securityId(question.securityId)
                    .exchangeOrderId(question.exchangeOrderId);
            length = MessageHeaderEncoder.ENCODED_LENGTH + this.cancelEnded.encodedLength();
        } else {
            this.venueQuery
                    .wrapAndApplyHeader(this.scratch, 0, this.headerEncoder)
                    .sessionSeq(this.identity.sessionSeq())
                    .queryId(question.queryId)
                    .kind(question.kind)
                    .exchangeId(question.exchangeId)
                    .securityId(question.securityId)
                    .exchangeOrderId(question.exchangeOrderId);
            length = MessageHeaderEncoder.ENCODED_LENGTH + this.venueQuery.encodedLength();
        }
        if (publication.offer(this.scratch, 0, length) > 0) {
            question.sent = true;
            return 1;
        }
        return 0;
    }

    /**
     * A query sent on a link that then went down, or whose gateway forgot the session, goes again once the link is
     * ready: the gateway that had it may never answer.
     */
    private void askAgain(final GatewayLink link) {
        final GatewayQuery question = this.query;
        if (question != null && question.exchangeId == link.exchangeId && question.sent) {
            question.sent = false;
            question.orders.clear();
        }
    }

    private static boolean ended(final OrderState state) {
        return state == OrderState.FILLED
                || state == OrderState.CANCELED
                || state == OrderState.REJECTED
                || state == OrderState.EXPIRED;
    }

    /** One page of the answer to the query waiting for it; the last page hands it back. */
    private void onQueryResult() {
        final GatewayQuery question = this.query;
        if (question == null || question.queryId != this.queryResult.queryId()) {
            return;
        }
        final QueryStatus status = this.queryResult.status();
        final short last = this.queryResult.last();
        for (final VenueQueryResultDecoder.OrdersDecoder row : this.queryResult.orders()) {
            final long notional = row.cumulativeNotional();
            final long fees = row.fees();
            final OrderState state = row.state();
            final long filled = row.cumulativeQty();
            final String exchangeOrderId = row.exchangeOrderId();
            final String venueOrderId = row.venueOrderId();
            question.orders.add(new VenueOrder(
                    exchangeOrderId,
                    venueOrderId.isEmpty() ? exchangeOrderId : venueOrderId,
                    filled,
                    notional,
                    fees,
                    ended(state)));
        }
        if (last == 1) {
            this.query = null;
            question.answered(status);
        }
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.HOT_PATH;
    }

    @Override
    public String roleName() {
        return "gateway-client";
    }

    /** Open orders the client is tracking, for status and tests. */
    int openOrders() {
        return this.orders.size();
    }

    // ----- the OMS's orders -----

    private void onOmsOrder(
            final long globalSequence, final int templateId, final UnsafeBuffer buffer, final int length) {
        if (templateId == OrderDecoder.TEMPLATE_ID) {
            this.order.buffer.putBytes(0, buffer, 0, length);
            onNew(buffer, length);
        } else if (templateId == CancelOrderDecoder.TEMPLATE_ID) {
            this.cancel.buffer.putBytes(0, buffer, 0, length);
            onCancel(buffer, length);
        } else if (templateId == ModifyOrderDecoder.TEMPLATE_ID) {
            this.modify.buffer.putBytes(0, buffer, 0, length);
            onAmend(buffer, length);
        }
    }

    private void onNew(final UnsafeBuffer buffer, final int length) {
        final OrderDecoder decoder = this.order.decoder;
        final long counter = this.order.getClientOidCounter();
        final int strategyId = this.order.getClientOidStrategyId();
        final GatewayLink link = this.byExchange.get(decoder.exchangeId());
        if (link == null || link.state != GatewayLink.READY) {
            activity.increment(newOrdersRefused);
            reject(counter, strategyId, decoder.exchangeId(), decoder.securityId(), RejectReason.GATEWAY_UNAVAILABLE);
            return;
        }
        if (!this.orders.sent(
                counter,
                strategyId,
                decoder.exchangeId(),
                decoder.securityId(),
                decoder.side(),
                decoder.price(),
                decoder.size())) {
            activity.increment(newOrdersRefused);
            reject(counter, strategyId, decoder.exchangeId(), decoder.securityId(), RejectReason.GATEWAY_REJECTED);
            return;
        }
        if (!send(link, buffer, length)) {
            final GatewayOrders.Entry entry = this.orders.get(counter);
            this.orders.release(entry);
            activity.increment(newOrdersRefused);
            reject(counter, strategyId, decoder.exchangeId(), decoder.securityId(), RejectReason.GATEWAY_UNAVAILABLE);
            return;
        }
        activity.increment(newOrdersSent);
        if (this.tickToOrder != null) {
            final long sentAt = handedOff(decoder.exchangeId(), NEW, decoder.triggerKind(), decoder.triggerTimestamp());
            this.orders.get(counter).sentAt = sentAt;
        }
    }

    /** Records the hand-off's tick-to-order; @return when it was handed off */
    private long handedOff(final int exchangeId, final int action, final TriggerKind kind, final long triggerAt) {
        final long sentAt = this.wallClock.nanoTime();
        if (triggerAt != 0) {
            this.tickToOrder.record(exchangeId, action, kind.value(), sentAt - triggerAt, sentAt);
        }
        return sentAt;
    }

    private void onCancel(final UnsafeBuffer buffer, final int length) {
        final CancelOrderDecoder decoder = this.cancel.decoder;
        final long counter = this.cancel.getClientOidCounter();
        final GatewayLink link = this.byExchange.get(decoder.exchangeId());
        this.orders.cancelling(counter);
        if (link != null && link.state != GatewayLink.READY && this.orders.get(counter) != null) {
            // Its order may end while the cancel waits; the order's last report then answers it.
            this.heldForOpen.add(counter);
        }
        // Held until the link is ready again, so a cancel the session sent is never lost to an outage.
        if (link == null
                || !(link.state == GatewayLink.READY ? send(link, buffer, length) : hold(link, buffer, length))) {
            activity.increment(cancelsRefused);
            cancelReject(
                    counter,
                    this.cancel.getClientOidStrategyId(),
                    decoder.exchangeId(),
                    decoder.securityId(),
                    RejectReason.GATEWAY_UNAVAILABLE);
            return;
        }
        activity.increment(link.state == GatewayLink.READY ? cancelsSent : cancelsHeld);
        if (this.tickToOrder != null) {
            final long sentAt =
                    handedOff(decoder.exchangeId(), CANCEL, decoder.triggerKind(), decoder.triggerTimestamp());
            final GatewayOrders.Entry entry = this.orders.get(counter);
            if (entry != null) {
                entry.cancelSentAt = sentAt;
            }
        }
    }

    private void onAmend(final UnsafeBuffer buffer, final int length) {
        final ModifyOrderDecoder decoder = this.modify.decoder;
        final GatewayLink link = this.byExchange.get(decoder.exchangeId());
        if (link == null || link.state != GatewayLink.READY || !send(link, buffer, length)) {
            activity.increment(modifiesRefused);
            cancelReject(
                    this.modify.getClientOidCounter(),
                    this.modify.getClientOidStrategyId(),
                    decoder.exchangeId(),
                    decoder.securityId(),
                    RejectReason.GATEWAY_UNAVAILABLE);
            return;
        }
        activity.increment(modifiesSent);
        if (this.tickToOrder != null) {
            handedOff(decoder.exchangeId(), MODIFY, decoder.triggerKind(), decoder.triggerTimestamp());
        }
    }

    /** Offers the message, or keeps it behind any already waiting. @return false if it couldn't be kept either */
    private boolean send(final GatewayLink link, final DirectBuffer buffer, final int length) {
        final Publication publication = link.publication;
        if (link.pending.isEmpty() && publication != null && publication.offer(buffer, 0, length) > 0) {
            return true;
        }
        return hold(link, buffer, length);
    }

    private boolean hold(final GatewayLink link, final DirectBuffer buffer, final int length) {
        final boolean held = link.pending.offer(buffer, 0, length);
        activity.max(pendingPeak, link.pending.size());
        return held;
    }

    /** Sends what waited for the link, in order, while the publication takes it. */
    private int flush(final GatewayLink link) {
        final Publication publication = link.publication;
        final PendingMessages pending = link.pending;
        int sent = 0;
        while (!pending.isEmpty() && publication != null) {
            if (endedCancel(pending)) {
                // Its order ended while the cancel waited (a gateway's restart may have cancelled it): the OMS has
                // had the order's last report, which answers the cancel too.
                pending.pop();
                continue;
            }
            if (publication.offer(pending.buffer(), pending.headOffset(), pending.headLength()) < 0) {
                break;
            }
            pending.pop();
            sent++;
        }
        return sent;
    }

    /** A held cancel whose order was open when it was held and has ended since; the held mark is cleared either way. */
    private boolean endedCancel(final PendingMessages pending) {
        if (SbeMessage.templateId(pending.buffer(), pending.headOffset()) != CancelOrderDecoder.TEMPLATE_ID) {
            return false;
        }
        this.cancel.buffer.putBytes(0, pending.buffer(), pending.headOffset(), pending.headLength());
        final long counter = this.cancel.getClientOidCounter();
        return this.heldForOpen.remove(counter) && this.orders.get(counter) == null;
    }

    // ----- each link -----

    private int step(final GatewayLink link) {
        switch (link.state) {
            case GatewayLink.CONNECTING -> {
                if (this.now - link.connectingSinceNanos > CONNECT_TIMEOUT_NANOS && !isConnected(link)) {
                    link.connectTimedOut = true;
                    down(link);
                    return 1;
                }
                return sayHello(link);
            }
            case GatewayLink.HELLO_SENT -> {
                if (this.now - link.welcomeDeadlineNanos > 0) {
                    down(link);
                }
                return 0;
            }
            case GatewayLink.READY -> {
                return tendReady(link);
            }
            default -> {
                return 0;
            }
        }
    }

    private int tendReady(final GatewayLink link) {
        final Publication publication = link.publication;
        if (publication == null
                || !publication.isConnected()
                || this.now - link.gatewayHeardNanos > GATEWAY_SILENCE_NANOS) {
            down(link);
            return 1;
        }
        int work = flush(link);
        if (this.now - link.nextHeartbeatNanos >= 0) {
            link.nextHeartbeatNanos = this.now + HEARTBEAT_INTERVAL_NANOS;
            this.heartbeat
                    .wrapAndApplyHeader(this.scratch, 0, this.headerEncoder)
                    .sessionSeq(this.identity.sessionSeq())
                    .sentNanos(this.wallClock.nanoTime())
                    .lastGatewayHeartbeatSeq(link.gatewayHeartbeatSeq)
                    .lastReportSeq(link.lastReportSeq);
            publication.offer(
                    this.scratch, 0, MessageHeaderEncoder.ENCODED_LENGTH + SessionHeartbeatEncoder.BLOCK_LENGTH);
            work++;
        }
        return work;
    }

    /**
     * The hello, then its pages of open orders, as far as the publication takes them; carries on from there on the
     * next pass. Done, it waits for the welcome.
     */
    private static boolean isConnected(final GatewayLink link) {
        final Publication publication = link.publication;
        return publication != null && publication.isConnected();
    }

    private int sayHello(final GatewayLink link) {
        final Publication publication = link.publication;
        if (publication == null || !publication.isConnected()) {
            return 0;
        }
        if (link.helloPage == 0) {
            collectOrders(link);
        }
        final int pages = pages();
        int sent = 0;
        while (link.helloPage <= pages) {
            final int length = link.helloPage == 0 ? encodeHello(link, pages) : encodePage(link.helloPage - 1, pages);
            if (publication.offer(this.scratch, 0, length) < 0) {
                return sent;
            }
            link.helloPage++;
            sent++;
        }
        link.state = GatewayLink.HELLO_SENT;
        link.welcomeDeadlineNanos = this.now + WELCOME_TIMEOUT_NANOS;
        return sent;
    }

    private void collectOrders(final GatewayLink link) {
        this.helloCount = 0;
        this.helloExchange = link.exchangeId;
        this.orders.forEach(this.helloCollector);
    }

    private void collect(final GatewayOrders.Entry entry) {
        if (entry.exchangeId == this.helloExchange && this.helloCount < this.helloEntries.length) {
            this.helloEntries[this.helloCount++] = entry;
        }
    }

    private int pages() {
        return (this.helloCount + GatewayMessageLimits.HELLO_ORDERS_PER_PAGE - 1)
                / GatewayMessageLimits.HELLO_ORDERS_PER_PAGE;
    }

    private int encodeHello(final GatewayLink link, final int pages) {
        this.hello
                .wrapAndApplyHeader(this.scratch, 0, this.headerEncoder)
                .sessionSeq(this.identity.sessionSeq())
                .sessionId(this.identity.sessionId())
                .strategyId(this.identity.strategyId())
                .mode(link.mode)
                .venueAccountId(link.venueAccountId)
                .protocolVersion(SessionHelloEncoder.SCHEMA_VERSION)
                .replyIp(HostAddress.pack(link.replyIp) & 0xFFFF_FFFFL)
                .replyPort(link.replyPort)
                .lastReportSeq(link.lastReportSeq)
                .lastReportEpoch(link.epoch)
                .codTimeoutMs(this.identity.codTimeoutMs())
                .maxQueueMs(this.identity.maxQueueMs())
                .openOrderPages(pages)
                .maxOpenOrders(this.identity.maxOpenOrders())
                .maxNewPerSecond(this.identity.maxNewPerSecond());
        final SessionHelloEncoder.ListingsEncoder listings = this.hello.listingsCount(link.listings.size());
        for (int i = 0; i < link.listings.size(); i++) {
            final long[] listing = link.listings.get(i);
            listings.next().exchangeId((int) listing[0]).securityId(listing[1]);
        }
        return MessageHeaderEncoder.ENCODED_LENGTH + this.hello.encodedLength();
    }

    private int encodePage(final int page, final int pages) {
        this.helloOrders
                .wrapAndApplyHeader(this.scratch, 0, this.headerEncoder)
                .sessionSeq(this.identity.sessionSeq())
                .page(page)
                .last((short) (page == pages - 1 ? 1 : 0));
        final int first = page * GatewayMessageLimits.HELLO_ORDERS_PER_PAGE;
        final int count = Math.min(GatewayMessageLimits.HELLO_ORDERS_PER_PAGE, this.helloCount - first);
        final SessionHelloOrdersEncoder.OrdersEncoder entries = this.helloOrders.ordersCount(count);
        for (int i = first; i < first + count; i++) {
            final GatewayOrders.Entry entry = this.helloEntries[i];
            entries.next()
                    .clientOidCounter(entry.counter)
                    .exchangeId(entry.exchangeId)
                    .securityId(entry.securityId)
                    .side(
                            entry.side == Side.Ask
                                    ? group.gnometrading.schemas.gateway.Side.Ask
                                    : group.gnometrading.schemas.gateway.Side.Bid)
                    .state(entry.state)
                    .price(entry.price)
                    .size(entry.size)
                    .putExchangeOrderId(entry.exchangeOrderId, 0)
                    .venueOrderId("")
                    .cumulativeQty(entry.cumulativeQty)
                    .notional(entry.notional)
                    .fees(entry.fees);
        }
        return MessageHeaderEncoder.ENCODED_LENGTH + this.helloOrders.encodedLength();
    }

    /**
     * The link is down: the control thread opens it again. What waited for it is answered now, except cancels, which
     * wait on: a NEW or amend sent late would surprise a strategy that has moved on.
     */
    private void down(final GatewayLink link) {
        link.state = GatewayLink.DOWN;
        askAgain(link);
        final PendingMessages pending = link.pending;
        for (int i = pending.size(); i > 0; i--) {
            final UnsafeBuffer buffer = pending.buffer();
            final int offset = pending.headOffset();
            final int length = pending.headLength();
            final int templateId = SbeMessage.templateId(buffer, offset);
            if (templateId == CancelOrderDecoder.TEMPLATE_ID) {
                this.scratch.putBytes(0, buffer, offset, length);
                pending.pop();
                pending.offer(this.scratch, 0, length);
            } else if (templateId == OrderDecoder.TEMPLATE_ID) {
                this.order.buffer.putBytes(0, buffer, offset, length);
                pending.pop();
                final GatewayOrders.Entry entry = this.orders.get(this.order.getClientOidCounter());
                if (entry != null) {
                    this.orders.release(entry);
                }
                reject(
                        this.order.getClientOidCounter(),
                        this.order.getClientOidStrategyId(),
                        this.order.decoder.exchangeId(),
                        this.order.decoder.securityId(),
                        RejectReason.GATEWAY_UNAVAILABLE);
            } else {
                this.modify.buffer.putBytes(0, buffer, offset, length);
                pending.pop();
                cancelReject(
                        this.modify.getClientOidCounter(),
                        this.modify.getClientOidStrategyId(),
                        this.modify.decoder.exchangeId(),
                        this.modify.decoder.securityId(),
                        RejectReason.GATEWAY_UNAVAILABLE);
            }
        }
    }

    // ----- replies -----

    /** One link's replies; ABORT leaves a report the OMS's ring has no room for in Aeron, to be read again. */
    private final class ReplyHandler implements ControlledFragmentHandler {
        private final GatewayLink link;

        ReplyHandler(final GatewayLink link) {
            this.link = link;
        }

        @Override
        public Action onFragment(final DirectBuffer buffer, final int offset, final int length, final Header frame) {
            if ((frame.flags() & FrameDescriptor.UNFRAGMENTED) != FrameDescriptor.UNFRAGMENTED
                    || length < SbeMessage.HEADER_LENGTH
                    || length > MAX_MESSAGE) {
                return Action.CONTINUE;
            }
            final int templateId = SbeMessage.templateId(buffer, offset);
            if (SbeMessage.schemaId(buffer, offset) == MessageSchema.CORE.schemaId()) {
                return onReport(this.link, buffer, offset, length, frame.reservedValue());
            }
            onGatewayMessage(this.link, templateId, buffer, offset, frame.reservedValue());
            return Action.CONTINUE;
        }
    }

    private ControlledFragmentHandler.Action onReport(
            final GatewayLink link,
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final long sequence) {
        if (SbeMessage.templateId(buffer, offset) != OrderExecutionReportDecoder.TEMPLATE_ID
                || length > this.inbound.buffer.capacity()) {
            return ControlledFragmentHandler.Action.CONTINUE;
        }
        if (sequence > 0 && sequence <= link.lastReportSeq) {
            // Already passed on: sent again around a reconnect.
            return ControlledFragmentHandler.Action.CONTINUE;
        }
        if (!this.toOms.tryPublishRaw(buffer, offset, OrderExecutionReportDecoder.TEMPLATE_ID, length)) {
            activity.increment(reportsRetried);
            return ControlledFragmentHandler.Action.ABORT;
        }
        activity.increment(reportsIn);
        link.lastReportSeq = Math.max(link.lastReportSeq, sequence);
        this.inbound.buffer.putBytes(0, buffer, offset, length);
        if (this.orderToAck != null) {
            answered(this.orders.get(this.inbound.getClientOidCounter()), this.inbound.decoder);
        }
        this.orders.reported(this.inbound.getClientOidCounter(), this.inbound.decoder);
        return ControlledFragmentHandler.Action.CONTINUE;
    }

    private void answered(final GatewayOrders.Entry entry, final OrderExecutionReportDecoder report) {
        if (entry == null || (entry.sentAt == 0 && entry.cancelSentAt == 0)) {
            return;
        }
        final long at = this.wallClock.nanoTime();
        if (entry.sentAt != 0) {
            this.orderToAck.record(entry.exchangeId, 0, 0, at - entry.sentAt, at);
            entry.sentAt = 0;
        }
        if (entry.cancelSentAt != 0 && answersCancel(report.execType())) {
            this.cancelToAck.record(entry.exchangeId, 0, 0, at - entry.cancelSentAt, at);
            entry.cancelSentAt = 0;
        }
    }

    /** The cancel's own answer, or the order's end, which answers it too. */
    private static boolean answersCancel(final ExecType execType) {
        return execType == ExecType.CANCEL
                || execType == ExecType.CANCEL_REJECT
                || execType == ExecType.FILL
                || execType == ExecType.EXPIRE
                || execType == ExecType.REJECT;
    }

    private void onGatewayMessage(
            final GatewayLink link,
            final int templateId,
            final DirectBuffer buffer,
            final int offset,
            final long sequence) {
        this.header.wrap(buffer, offset);
        final int body = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        switch (templateId) {
            case GatewayHeartbeatDecoder.TEMPLATE_ID -> {
                this.gatewayHeartbeat.wrap(buffer, body, this.header.blockLength(), this.header.version());
                link.gatewayHeartbeatSeq = this.gatewayHeartbeat.heartbeatSeq();
                link.gatewayHeardNanos = this.now;
            }
            case SessionWelcomeDecoder.TEMPLATE_ID -> {
                this.welcome.wrap(buffer, body, this.header.blockLength(), this.header.version());
                onWelcome(link);
            }
            case OrderStatusDecoder.TEMPLATE_ID -> {
                if (sequence <= 0 || sequence > link.lastReportSeq) {
                    this.status.wrap(buffer, body, this.header.blockLength(), this.header.version());
                    link.lastReportSeq = Math.max(link.lastReportSeq, sequence);
                    applyStatus();
                }
            }
            case ReHelloRequiredDecoder.TEMPLATE_ID -> {
                if (link.state == GatewayLink.READY || link.state == GatewayLink.HELLO_SENT) {
                    link.helloPage = 0;
                    link.state = GatewayLink.CONNECTING;
                    askAgain(link);
                }
            }
            case SessionGoodbyeDecoder.TEMPLATE_ID -> down(link);
            case VenueQueryResultDecoder.TEMPLATE_ID -> {
                this.queryResult.wrap(buffer, body, this.header.blockLength(), this.header.version());
                onQueryResult();
            }
            default -> {}
        }
    }

    private void onWelcome(final GatewayLink link) {
        if (link.state != GatewayLink.HELLO_SENT || this.welcome.sessionSeq() != this.identity.sessionSeq()) {
            return;
        }
        if (this.welcome.accepted() != 1) {
            link.refusal = this.welcome.refusal();
            down(link);
            return;
        }
        final long epoch = this.welcome.gatewayEpoch();
        if (epoch != link.epoch) {
            // A gateway that restarted numbers its reports afresh.
            link.epoch = epoch;
            link.lastReportSeq = 0;
        }
        link.gatewayHeardNanos = this.now;
        link.nextHeartbeatNanos = this.now;
        link.state = GatewayLink.READY;
    }

    /**
     * Turns the gateway's cumulative state of one order into the reports that take the OMS there from what it has:
     * a fill for any quantity it hasn't seen, an acknowledgement it hasn't had, or the order's end.
     */
    private void applyStatus() {
        final long counter = this.status.clientOidCounter();
        final GatewayOrders.Entry entry = this.orders.get(counter);
        if (entry == null) {
            return;
        }
        final OrderState state = this.status.state();
        final long filled = this.status.cumulativeQty() - entry.cumulativeQty;
        if (filled > 0) {
            final boolean done = state == OrderState.FILLED;
            synthesize(
                    entry,
                    done ? ExecType.FILL : ExecType.PARTIAL_FILL,
                    done ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                    filled,
                    fillPrice(this.status.notional() - entry.notional, filled),
                    this.status.fees() - entry.fees);
        }
        if (this.orders.get(counter) == null) {
            return;
        }
        switch (state) {
            case CANCELED -> synthesize(entry, ExecType.CANCEL, OrderStatus.CANCELED, 0, 0, 0);
            case EXPIRED -> synthesize(entry, ExecType.EXPIRE, OrderStatus.EXPIRED, 0, 0, 0);
            case REJECTED -> synthesize(entry, ExecType.REJECT, OrderStatus.REJECTED, 0, 0, 0);
            case NEW, PARTIALLY_FILLED -> {
                if (entry.state == OrderState.PENDING_NEW) {
                    synthesize(entry, ExecType.NEW, OrderStatus.NEW, 0, 0, 0);
                }
            }
            default -> {}
        }
    }

    /** The average price of a fill from its notional, in price units per unit. */
    private static long fillPrice(final long notional, final long quantity) {
        if (notional >= 0 && notional <= Long.MAX_VALUE / SIZE_SCALE) {
            return notional * SIZE_SCALE / quantity;
        }
        return BigInteger.valueOf(notional)
                .multiply(BigInteger.valueOf(SIZE_SCALE))
                .divide(BigInteger.valueOf(quantity))
                .longValue();
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void synthesize(
            final GatewayOrders.Entry entry,
            final ExecType execType,
            final OrderStatus orderStatus,
            final long filledQty,
            final long fillPrice,
            final long fee) {
        final boolean fill = filledQty > 0;
        final OrderExecutionReportEncoder encoder = this.outbound.encoder;
        encoder.exchangeId(entry.exchangeId).securityId(entry.securityId);
        for (int i = 0; i < OrderExecutionReportEncoder.exchangeOrderIdLength(); i++) {
            encoder.exchangeOrderId(i, i < entry.exchangeOrderIdLength ? entry.exchangeOrderId[i] : 0);
        }
        this.outbound.encodeClientOid(entry.counter, entry.strategyId);
        final long cumulative = entry.cumulativeQty + filledQty;
        encoder.execType(execType)
                .orderStatus(orderStatus)
                .filledQty(fill ? filledQty : OrderExecutionReportEncoder.filledQtyNullValue())
                .fillPrice(fill ? fillPrice : OrderExecutionReportEncoder.fillPriceNullValue())
                .cumulativeQty(cumulative)
                .leavesQty(
                        orderStatus == OrderStatus.NEW || orderStatus == OrderStatus.PARTIALLY_FILLED
                                ? Math.max(0, entry.size - cumulative)
                                : 0)
                .timestampEvent(OrderExecutionReportEncoder.timestampEventNullValue())
                .timestampRecv(this.wallClock.nanoTime())
                .rejectReason(RejectReason.NULL_VAL)
                .fee(fill ? fee : OrderExecutionReportEncoder.feeNullValue());
        encoder.flags().clear();
        publishToOms(this.outbound);
        this.orders.reported(entry.counter, this.outbound.decoder);
    }

    private void reject(
            final long counter,
            final int strategyId,
            final int exchangeId,
            final long securityId,
            final RejectReason reason) {
        refusal(counter, strategyId, exchangeId, securityId, ExecType.REJECT, OrderStatus.REJECTED, reason);
    }

    private void cancelReject(
            final long counter,
            final int strategyId,
            final int exchangeId,
            final long securityId,
            final RejectReason reason) {
        refusal(counter, strategyId, exchangeId, securityId, ExecType.CANCEL_REJECT, OrderStatus.CANCELED, reason);
        // The order is no longer being cancelled, as far as the OMS will know.
        this.orders.reported(counter, this.outbound.decoder);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void refusal(
            final long counter,
            final int strategyId,
            final int exchangeId,
            final long securityId,
            final ExecType execType,
            final OrderStatus orderStatus,
            final RejectReason reason) {
        final OrderExecutionReportEncoder encoder = this.outbound.encoder;
        encoder.exchangeId(exchangeId).securityId(securityId);
        for (int i = 0; i < OrderExecutionReportEncoder.exchangeOrderIdLength(); i++) {
            encoder.exchangeOrderId(i, (byte) 0);
        }
        this.outbound.encodeClientOid(counter, strategyId);
        encoder.execType(execType)
                .orderStatus(orderStatus)
                .filledQty(OrderExecutionReportEncoder.filledQtyNullValue())
                .fillPrice(OrderExecutionReportEncoder.fillPriceNullValue())
                .cumulativeQty(0)
                .leavesQty(0)
                .timestampEvent(OrderExecutionReportEncoder.timestampEventNullValue())
                .timestampRecv(this.wallClock.nanoTime())
                .rejectReason(reason)
                .fee(OrderExecutionReportEncoder.feeNullValue());
        encoder.flags().clear();
        publishToOms(this.outbound);
    }

    /** Waits for room: the OMS drains its ring on every pass, so it stays full only while the OMS is stalled. */
    private void publishToOms(final OrderExecutionReport report) {
        this.toOms.publishRaw(report.buffer, OrderExecutionReportDecoder.TEMPLATE_ID, report.totalMessageSize());
    }
}
