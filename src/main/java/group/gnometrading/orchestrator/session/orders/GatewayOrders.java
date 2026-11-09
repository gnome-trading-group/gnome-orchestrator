package group.gnometrading.orchestrator.session.orders;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.gateway.OrderState;
import java.util.function.Consumer;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * The session's orders sent through an order gateway and not yet ended, as the session's OMS has heard of them: their
 * terms, state, and cumulative fills, notional and fees. A hello lists them so the gateway can reconcile after a
 * restart, and a gateway's cumulative {@code OrderStatus} is turned back into ordinary reports by its difference from
 * them. The gateway client's thread; a fixed pool, allocates nothing once built.
 */
final class GatewayOrders {

    private static final long SIZE_SCALE = 1_000_000L;

    /** One open order. */
    static final class Entry {
        long counter;
        int strategyId;
        int exchangeId;
        long securityId;
        Side side;
        long price;
        long size;
        OrderState state;
        long cumulativeQty;
        long notional;
        long fees;
        final byte[] exchangeOrderId = new byte[OrderExecutionReportEncoder.exchangeOrderIdLength()];
        int exchangeOrderIdLength;

        void reset() {
            this.counter = 0;
            this.strategyId = 0;
            this.exchangeId = 0;
            this.securityId = 0;
            this.side = null;
            this.price = 0;
            this.size = 0;
            this.state = null;
            this.cumulativeQty = 0;
            this.notional = 0;
            this.fees = 0;
            this.exchangeOrderIdLength = 0;
        }

        boolean terminal() {
            return this.state == OrderState.FILLED
                    || this.state == OrderState.CANCELED
                    || this.state == OrderState.REJECTED
                    || this.state == OrderState.EXPIRED;
        }
    }

    private final Long2ObjectHashMap<Entry> open;
    private final Entry[] pool;
    private int free;

    GatewayOrders(final int maxOrders) {
        this.open = new Long2ObjectHashMap<>(maxOrders * 2, 0.6f);
        this.pool = new Entry[maxOrders];
        for (int i = 0; i < maxOrders; i++) {
            this.pool[i] = new Entry();
        }
        this.free = maxOrders;
    }

    /** A NEW sent: held as PENDING_NEW until the gateway reports on it. @return false if the pool is spent */
    boolean sent(
            final long counter,
            final int strategyId,
            final int exchangeId,
            final long securityId,
            final Side side,
            final long price,
            final long size) {
        if (this.free == 0 || this.open.containsKey(counter)) {
            return false;
        }
        final Entry entry = this.pool[--this.free];
        entry.reset();
        entry.counter = counter;
        entry.strategyId = strategyId;
        entry.exchangeId = exchangeId;
        entry.securityId = securityId;
        entry.side = side;
        entry.price = price;
        entry.size = size;
        entry.state = OrderState.PENDING_NEW;
        this.open.put(counter, entry);
        return true;
    }

    /** A cancel sent for the order. */
    void cancelling(final long counter) {
        final Entry entry = this.open.get(counter);
        if (entry != null && !entry.terminal()) {
            entry.state = OrderState.PENDING_CANCEL;
        }
    }

    Entry get(final long counter) {
        return this.open.get(counter);
    }

    /** What a report passed on to the OMS says of its order; one that ends it frees its entry. */
    void reported(final long counter, final OrderExecutionReportDecoder report) {
        final Entry entry = this.open.get(counter);
        if (entry == null) {
            return;
        }
        final ExecType execType = report.execType();
        if (execType == ExecType.CANCEL_REJECT) {
            if (entry.state == OrderState.PENDING_CANCEL) {
                entry.state = entry.cumulativeQty > 0 ? OrderState.PARTIALLY_FILLED : OrderState.NEW;
            }
            return;
        }
        if (execType == ExecType.FILL || execType == ExecType.PARTIAL_FILL) {
            entry.notional += notional(report.filledQty(), report.fillPrice());
            final long fee = report.fee();
            entry.fees += fee == OrderExecutionReportEncoder.feeNullValue() ? 0 : fee;
        }
        if (entry.exchangeOrderIdLength == 0) {
            copyExchangeOrderId(entry, report);
        }
        entry.cumulativeQty = Math.max(entry.cumulativeQty, report.cumulativeQty());
        entry.state = next(entry.state, report.orderStatus());
        if (entry.terminal()) {
            release(entry);
        }
    }

    private static void copyExchangeOrderId(final Entry entry, final OrderExecutionReportDecoder report) {
        int length = 0;
        while (length < entry.exchangeOrderId.length && report.exchangeOrderId(length) != 0) {
            entry.exchangeOrderId[length] = report.exchangeOrderId(length);
            length++;
        }
        entry.exchangeOrderIdLength = length;
    }

    /** A cancel in flight stays the state until the order ends or the cancel is refused. */
    private static OrderState next(final OrderState current, final OrderStatus reported) {
        final boolean cancelling = current == OrderState.PENDING_CANCEL;
        return switch (reported) {
            case NEW -> cancelling ? current : OrderState.NEW;
            case PARTIALLY_FILLED -> cancelling ? current : OrderState.PARTIALLY_FILLED;
            case FILLED -> OrderState.FILLED;
            case CANCELED -> OrderState.CANCELED;
            case REJECTED -> OrderState.REJECTED;
            case EXPIRED -> OrderState.EXPIRED;
            case NULL_VAL -> current;
        };
    }

    void release(final Entry entry) {
        this.open.remove(entry.counter);
        this.pool[this.free++] = entry;
    }

    int size() {
        return this.open.size();
    }

    /** Every open order, for a hello; doesn't allocate but visits the whole table. */
    void forEach(final Consumer<Entry> visitor) {
        for (final Entry entry : this.open.values()) {
            visitor.accept(entry);
        }
    }

    /** Quantity (1e6 a unit) times price (1e9 a dollar), in price units, without overflowing at any real size. */
    static long notional(final long quantity, final long price) {
        return (quantity / SIZE_SCALE) * price + (quantity % SIZE_SCALE) * price / SIZE_SCALE;
    }
}
