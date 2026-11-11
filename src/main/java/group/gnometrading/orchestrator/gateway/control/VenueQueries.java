package group.gnometrading.orchestrator.gateway.control;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.gateway.core.GatewayMessage;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.gateway.CancelEndedDecoder;
import group.gnometrading.schemas.gateway.GatewayMessageLimits;
import group.gnometrading.schemas.gateway.MessageHeaderDecoder;
import group.gnometrading.schemas.gateway.MessageHeaderEncoder;
import group.gnometrading.schemas.gateway.OrderState;
import group.gnometrading.schemas.gateway.QueryKind;
import group.gnometrading.schemas.gateway.QueryStatus;
import group.gnometrading.schemas.gateway.Side;
import group.gnometrading.schemas.gateway.VenueQueryDecoder;
import group.gnometrading.schemas.gateway.VenueQueryResultEncoder;
import group.gnometrading.sm.Listing;
import group.gnometrading.utils.CauseChain;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * A starting session's questions about the account at the venue, answered on the control thread with the account's own
 * order lookup (its read budget), so the session needs no key of its own: the open orders on a listing, one order by
 * the id its reports carry, and cancelling what an ended session of the same strategy left resting.
 *
 * <p>A cancel never names an order the gateway trusts the session to own. Every order the strategy's ended sessions
 * still hold on the listing is cancelled, then the named order is looked up: if it rests still, it was nobody's the
 * session may cancel, and the answer says so. Owners in answers are 0: the gateway doesn't index its orders by the
 * venue's id, and recovery attributes orders by its ledger.
 *
 * <p>Blocks the control thread on the venue while it asks; only a starting session asks, and once per listing or
 * order. Allocates freely.
 */
final class VenueQueries {

    /** Hands an encoded answer to egress for the session's slot, ahead of its numbered reports. */
    @FunctionalInterface
    interface Answers {
        void send(long slot, UnsafeBuffer buffer, int length);
    }

    // How far back the venue is searched for an order by its id: one a restart lost track of, or a session asks about.
    static final long LOOKBACK_MILLIS = TimeUnit.DAYS.toMillis(2);
    // How long a cancelled order is given to end at the venue before the answer says it still rests.
    static final long CANCEL_SETTLE_MILLIS = 3_000;
    static final long CANCEL_POLL_MILLIS = 200;

    private final Logger logger;
    private final SessionTable sessions;
    private final SessionRegistry registry;
    private final GatewayControl.Listings listings;
    private final Answers answers;
    private final LongSupplier nanoClock;
    private final LongSupplier wallMillis;

    private final MessageHeaderDecoder header = new MessageHeaderDecoder();
    private final VenueQueryDecoder query = new VenueQueryDecoder();
    private final CancelEndedDecoder cancel = new CancelEndedDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final VenueQueryResultEncoder result = new VenueQueryResultEncoder();
    private final UnsafeBuffer outbound = new UnsafeBuffer(new byte[GatewayMessage.MAX_LENGTH]);

    VenueQueries(
            final Logger logger,
            final SessionTable sessions,
            final SessionRegistry registry,
            final GatewayControl.Listings listings,
            final Answers answers,
            final LongSupplier nanoClock,
            final LongSupplier wallMillis) {
        this.logger = logger;
        this.sessions = sessions;
        this.registry = registry;
        this.listings = listings;
        this.answers = answers;
        this.nanoClock = nanoClock;
        this.wallMillis = wallMillis;
    }

    static boolean handles(final int templateId) {
        return templateId == VenueQueryDecoder.TEMPLATE_ID || templateId == CancelEndedDecoder.TEMPLATE_ID;
    }

    void onMessage(final GatewayMessage message) {
        this.header.wrap(message.buffer, 0);
        if (SbeMessage.templateId(message.buffer, 0) == VenueQueryDecoder.TEMPLATE_ID) {
            this.query.wrap(
                    message.buffer,
                    MessageHeaderDecoder.ENCODED_LENGTH,
                    this.header.blockLength(),
                    this.header.version());
            onQuery(message.image);
        } else {
            this.cancel.wrap(
                    message.buffer,
                    MessageHeaderDecoder.ENCODED_LENGTH,
                    this.header.blockLength(),
                    this.header.version());
            onCancel(message.image);
        }
    }

    private void onQuery(final int image) {
        final long queryId = this.query.queryId();
        final QueryKind kind = this.query.kind();
        final int exchangeId = this.query.exchangeId();
        final long securityId = this.query.securityId();
        final String exchangeOrderId = this.query.exchangeOrderId();
        final GatewaySession session = askedBy(image, this.query.sessionSeq());
        if (session == null) {
            return;
        }
        final Listing listing = this.listings.listing(exchangeId, securityId);
        if (listing == null) {
            answer(session, queryId, QueryStatus.FAILED, List.of(), 0, 0);
            return;
        }
        try {
            final VenueOrderQuery venue = session.account.orderQuery();
            final List<VenueOrder> orders;
            if (kind == QueryKind.OPEN_ORDERS) {
                orders = venue.listOpenOrders(listing);
            } else if (kind == QueryKind.BY_EXCHANGE_ORDER_ID) {
                orders = venue.getOrder(listing, exchangeOrderId, since())
                        .map(List::of)
                        .orElse(List.of());
            } else {
                answer(session, queryId, QueryStatus.REFUSED, List.of(), 0, 0);
                return;
            }
            answer(session, queryId, QueryStatus.OK, orders, exchangeId, securityId);
        } catch (Exception e) {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Gateway: session %s's venue query failed: %s",
                    session.sessionId,
                    CauseChain.of(e));
            answer(session, queryId, QueryStatus.FAILED, List.of(), 0, 0);
        }
    }

    private void onCancel(final int image) {
        final long queryId = this.cancel.queryId();
        final int exchangeId = this.cancel.exchangeId();
        final long securityId = this.cancel.securityId();
        final String exchangeOrderId = this.cancel.exchangeOrderId();
        final GatewaySession session = askedBy(image, this.cancel.sessionSeq());
        if (session == null) {
            return;
        }
        final Listing listing = this.listings.listing(exchangeId, securityId);
        if (listing == null || exchangeOrderId.isEmpty()) {
            answer(session, queryId, QueryStatus.FAILED, List.of(), 0, 0);
            return;
        }
        try {
            final List<GatewaySession> ended = endedOfStrategy(session);
            for (final GatewaySession owner : ended) {
                cancelOn(owner, exchangeId, securityId);
            }
            final Optional<VenueOrder> order = awaitEnded(session.account.orderQuery(), listing, exchangeOrderId);
            if (order.isPresent() && !order.get().terminal()) {
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Gateway: session %s may not cancel %s: no ended session of strategy %d holds it",
                        session.sessionId,
                        exchangeOrderId,
                        session.strategyId);
                answer(session, queryId, QueryStatus.REFUSED, List.of(order.get()), exchangeId, securityId);
                return;
            }
            answer(session, queryId, QueryStatus.OK, order.map(List::of).orElse(List.of()), exchangeId, securityId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            answer(session, queryId, QueryStatus.FAILED, List.of(), 0, 0);
        } catch (Exception e) {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Gateway: session %s's cancel of %s failed: %s",
                    session.sessionId,
                    exchangeOrderId,
                    CauseChain.of(e));
            answer(session, queryId, QueryStatus.FAILED, List.of(), 0, 0);
        }
    }

    /** The order as the venue has it once it ended, or as it still is when it hasn't within the settle time. */
    private Optional<VenueOrder> awaitEnded(
            final VenueOrderQuery venue, final Listing listing, final String exchangeOrderId) throws Exception {
        final long deadline = this.nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(CANCEL_SETTLE_MILLIS);
        Optional<VenueOrder> order = venue.getOrder(listing, exchangeOrderId, since());
        while (order.isPresent() && !order.get().terminal() && this.nanoClock.getAsLong() - deadline < 0) {
            Thread.sleep(CANCEL_POLL_MILLIS);
            order = venue.getOrder(listing, exchangeOrderId, since());
        }
        return order;
    }

    /** The asking session, if it is linked on the image it asked from; answers go only to a linked session. */
    private GatewaySession askedBy(final int image, final long slot) {
        final GatewaySession session = this.sessions.byImage(image);
        if (session == null || session.slot != slot || session.state != GatewaySession.ACTIVE) {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Gateway: a venue query from image %d not linked to slot %d",
                    image,
                    slot);
            return null;
        }
        return session;
    }

    /** Sessions of the asker's strategy and account that are neither linked here nor running in the registry. */
    private List<GatewaySession> endedOfStrategy(final GatewaySession asker) throws Exception {
        final List<GatewaySession> ended = new ArrayList<>();
        for (final GatewaySession other : this.sessions.all()) {
            if (other == asker
                    || other.strategyId != asker.strategyId
                    || other.account != asker.account
                    || other.state == GatewaySession.ACTIVE
                    || other.sessionId == null) {
                continue;
            }
            final SessionRegistry.SessionRecord record =
                    this.registry.lookup(other.sessionId, this.nanoClock.getAsLong());
            if (record == null || !record.running()) {
                ended.add(other);
            }
        }
        return ended;
    }

    private static void cancelOn(final GatewaySession owner, final int exchangeId, final long securityId) {
        final ManyToOneRingBuffer<LaneCommand> lane = owner.account.commands();
        int index;
        while ((index = lane.tryClaim()) < 0) {
            Thread.onSpinWait();
        }
        final LaneCommand command = lane.indexAt(index);
        command.reset();
        command.kind = LaneCommand.CANCEL_ORDERS;
        command.sessionSlot = owner.slot;
        command.exchangeId = exchangeId;
        command.securityId = securityId;
        lane.commit(index);
    }

    /** The venue's own id where the field holds it; a Polymarket Intl hash doesn't, and is the exchange id anyway. */
    private static String venueOrderId(final VenueOrder order) {
        final String id = order.venueId();
        return id == null || id.length() > VenueQueryResultEncoder.OrdersEncoder.venueOrderIdLength() ? "" : id;
    }

    private long since() {
        return this.wallMillis.getAsLong() - LOOKBACK_MILLIS;
    }

    /** The answer in pages, the last marked; an empty answer is one empty last page. */
    private void answer(
            final GatewaySession session,
            final long queryId,
            final QueryStatus status,
            final List<VenueOrder> orders,
            final int exchangeId,
            final long securityId) {
        final int perPage = GatewayMessageLimits.QUERY_ORDERS_PER_PAGE;
        final int pages = Math.max(1, (orders.size() + perPage - 1) / perPage);
        for (int page = 0; page < pages; page++) {
            final int from = page * perPage;
            final int to = Math.min(orders.size(), from + perPage);
            this.result
                    .wrapAndApplyHeader(this.outbound, 0, this.headerEncoder)
                    .queryId(queryId)
                    .page(page)
                    .last((short) (page == pages - 1 ? 1 : 0))
                    .status(status);
            final VenueQueryResultEncoder.OrdersEncoder rows = this.result.ordersCount(to - from);
            for (int i = from; i < to; i++) {
                final VenueOrder order = orders.get(i);
                rows.next()
                        .ownerSessionSeq(0)
                        .clientOidCounter(0)
                        .exchangeId(exchangeId)
                        .securityId(securityId)
                        .side(Side.NULL_VAL)
                        .state(order.terminal() ? OrderState.CANCELED : OrderState.NEW)
                        .price(VenueQueryResultEncoder.OrdersEncoder.priceNullValue())
                        .size(VenueQueryResultEncoder.OrdersEncoder.sizeNullValue())
                        .cumulativeQty(order.filledQty())
                        .exchangeOrderId(order.exchangeOrderId())
                        .venueOrderId(venueOrderId(order))
                        .cumulativeNotional(order.filledNotional())
                        .fees(order.fees());
            }
            this.answers.send(
                    session.slot, this.outbound, MessageHeaderEncoder.ENCODED_LENGTH + this.result.encodedLength());
        }
    }
}
