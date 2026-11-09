package group.gnometrading.orchestrator.gateway.core;

import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.gateway.OrderState;
import group.gnometrading.schemas.gateway.OrderStatusDecoder;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * What one session has been sent of each of its orders, kept as the reports are numbered: state, cumulative and remaining
 * quantity, filled notional and fees. A summary of the session's orders ({@link group.gnometrading.orchestrator.gateway.transport.OrderStatuses}) is read from here, so it
 * holds every fill however long ago, not just those still in the replay ring. An order that has ended is kept until its
 * last report has gone out, then forgotten. Egress's; a fixed pool, allocates nothing once built.
 */
public final class OrderTotals {

    private static final long SIZE_SCALE = 1_000_000L;

    /** One order's totals. */
    public static final class Order {
        public long counter;
        public long listingKey;
        public OrderState state;
        public long cumulativeQty;
        public long leavesQty;
        public long notional;
        public long fees;
        // The newest report sequence that told of it.
        public long lastSeq;

        boolean terminal() {
            return this.state == OrderState.FILLED
                    || this.state == OrderState.CANCELED
                    || this.state == OrderState.REJECTED
                    || this.state == OrderState.EXPIRED;
        }
    }

    private final Long2ObjectHashMap<Order> orders;
    private final Order[] pool;
    private int free;
    // Orders that couldn't be tracked for want of room, so a summary would miss them. Written by egress only; volatile
    // so the gateway's minute report reads it.
    private volatile long untracked;

    public OrderTotals(final int maxOrders) {
        this.orders = new Long2ObjectHashMap<>(maxOrders * 2, 0.6f);
        this.pool = new Order[maxOrders];
        for (int i = 0; i < maxOrders; i++) {
            this.pool[i] = new Order();
        }
        this.free = maxOrders;
    }

    /** A report numbered {@code seq}: a refused cancel or amend changes nothing, a fill adds its notional and fee. */
    public void report(final OrderExecutionReport report, final long seq) {
        final OrderExecutionReportDecoder decoder = report.decoder;
        final ExecType execType = decoder.execType();
        if (execType == ExecType.CANCEL_REJECT) {
            return;
        }
        final Order order = order(report.getClientOidCounter());
        if (order == null) {
            return;
        }
        if (execType == ExecType.FILL || execType == ExecType.PARTIAL_FILL) {
            order.notional += notional(decoder.filledQty(), decoder.fillPrice());
            final long fee = decoder.fee();
            order.fees += fee == OrderExecutionReportEncoder.feeNullValue() ? 0 : fee;
        }
        order.listingKey = PublishedMap.listingKey(decoder.exchangeId(), decoder.securityId());
        order.state = state(decoder);
        order.cumulativeQty = decoder.cumulativeQty();
        order.leavesQty = decoder.leavesQty();
        order.lastSeq = seq;
    }

    /** A status numbered {@code seq} is a snapshot: it replaces what the order's totals were. */
    public void status(final OrderStatusDecoder status, final long seq) {
        final Order order = order(status.clientOidCounter());
        if (order == null) {
            return;
        }
        order.listingKey = PublishedMap.listingKey(status.exchangeId(), status.securityId());
        order.state = status.state();
        order.cumulativeQty = status.cumulativeQty();
        order.leavesQty = status.leavesQty();
        order.notional = status.notional();
        order.fees = status.fees();
        order.lastSeq = seq;
    }

    /** Report {@code seq}, about order {@code counter}, has gone out: an order it ended is forgotten. */
    public void sent(final long seq, final long counter) {
        final Order order = this.orders.get(counter);
        if (order != null && order.terminal() && order.lastSeq <= seq) {
            this.orders.remove(counter);
            this.pool[this.free++] = order;
        }
    }

    public Long2ObjectHashMap<Order>.ValueCollection orders() {
        return this.orders.values();
    }

    /** Orders tracked: open, or ended with their last report not yet sent. Approximate from another thread. */
    public int size() {
        return this.orders.size();
    }

    /** Orders left untracked for want of room: a resync's statuses miss them. Any thread. */
    public long untracked() {
        return this.untracked;
    }

    private Order order(final long counter) {
        Order order = this.orders.get(counter);
        if (order == null) {
            if (this.free == 0) {
                this.untracked = this.untracked + 1;
                return null;
            }
            order = this.pool[--this.free];
            order.counter = counter;
            order.state = OrderState.PENDING_NEW;
            order.cumulativeQty = 0;
            order.leavesQty = 0;
            order.notional = 0;
            order.fees = 0;
            order.lastSeq = 0;
            this.orders.put(counter, order);
        }
        return order;
    }

    private static OrderState state(final OrderExecutionReportDecoder decoder) {
        return switch (decoder.orderStatus()) {
            case NEW -> OrderState.NEW;
            case PARTIALLY_FILLED -> OrderState.PARTIALLY_FILLED;
            case FILLED -> OrderState.FILLED;
            case CANCELED -> OrderState.CANCELED;
            case REJECTED -> OrderState.REJECTED;
            case EXPIRED -> OrderState.EXPIRED;
            case NULL_VAL -> OrderState.PENDING_NEW;
        };
    }

    /** Quantity (1e6 a unit) times price (1e9 a dollar), in price units, without overflowing at any real size. */
    public static long notional(final long quantity, final long price) {
        return (quantity / SIZE_SCALE) * price + (quantity % SIZE_SCALE) * price / SIZE_SCALE;
    }
}
