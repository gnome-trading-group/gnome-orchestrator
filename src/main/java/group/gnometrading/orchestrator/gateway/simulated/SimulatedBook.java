package group.gnometrading.orchestrator.gateway.simulated;

import group.gnometrading.collections.FixedCapacityQueue;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.CancelOrderEncoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.MessageHeaderEncoder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
import group.gnometrading.schemas.ModifyOrderEncoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderEncoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
import group.gnometrading.utils.ByteBufferUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * One paper session's simulated venue on one listing: its own book, fed the listing's stream, which no other session's
 * orders reach. Holds each order, cancel and amend for the simulated network latency on the way to the venue and each
 * report on the way back, as the session's in-process simulation does. The simulated lane's thread only.
 */
final class SimulatedBook {

    private static final int EXEC_REPORT_SIZE =
            MessageHeaderEncoder.ENCODED_LENGTH + OrderExecutionReportEncoder.BLOCK_LENGTH;
    private static final int MAX_ACTION_SIZE = MessageHeaderEncoder.ENCODED_LENGTH
            + Math.max(
                    OrderEncoder.BLOCK_LENGTH,
                    Math.max(CancelOrderEncoder.BLOCK_LENGTH, ModifyOrderEncoder.BLOCK_LENGTH));
    private static final int PENDING_CAPACITY = 256;

    /** Where a report the venue sent back goes once its latency has passed. */
    interface ReportSink {
        /** @return false if there was no room for it now; it is offered again on the next pass */
        boolean deliver(long slot, OrderExecutionReport report);
    }

    final long slot;
    final long listingKey;
    final int listingId;
    final int streamId;
    final MbpSimulatedExchange exchange;
    // Whether a clean record of the listing has reached the book yet: until then an order could rest on an empty book
    // that the first snapshot crosses, so new orders are refused.
    boolean clean;

    private final byte[] orderIdPrefix;
    private final byte[] orderIdBytes = new byte[OrderExecutionReportEncoder.exchangeOrderIdLength()];
    private final ByteBuffer orderIdBuffer = ByteBuffer.wrap(this.orderIdBytes);
    private final Order order = new Order();
    private final CancelOrder cancel = new CancelOrder();
    private final ModifyOrder modify = new ModifyOrder();
    private final FixedCapacityQueue<Pending> actions =
            new FixedCapacityQueue<>(Pending[]::new, () -> new Pending(MAX_ACTION_SIZE), PENDING_CAPACITY);
    private final FixedCapacityQueue<Pending> reports =
            new FixedCapacityQueue<>(Pending[]::new, () -> new Pending(EXEC_REPORT_SIZE), PENDING_CAPACITY);
    private final OrderExecutionReport outgoing = new OrderExecutionReport();

    /** @param sessionTag what the session's venue order ids start with, as a live venue's would for it */
    SimulatedBook(
            final long slot,
            final long listingKey,
            final int listingId,
            final int streamId,
            final MbpSimulatedExchange exchange,
            final String sessionTag) {
        this.slot = slot;
        this.listingKey = listingKey;
        this.listingId = listingId;
        this.streamId = streamId;
        this.exchange = exchange;
        this.orderIdPrefix = (sessionTag + "-").getBytes(StandardCharsets.US_ASCII);
    }

    /** @return false if too many actions are already on their way */
    boolean submit(final SimulatedOrder held, final long nowNanos) {
        final Pending slot = this.actions.offer();
        if (slot == null) {
            return false;
        }
        this.order.wrap(slot.buffer);
        this.order
                .encoder
                .exchangeId(held.exchangeId)
                .securityId(held.securityId)
                .timestampSend(nowNanos);
        this.order.encodeClientOid(held.counter, held.strategyId);
        this.order
                .encoder
                .price(held.price)
                .size(held.size)
                .side(held.side)
                .orderType(held.orderType)
                .timeInForce(held.timeInForce);
        this.order.encoder.flags().clear();
        this.order.encoder.flags().postOnly(held.postOnly);
        return pend(slot, OrderDecoder.TEMPLATE_ID, this.order.totalMessageSize(), nowNanos);
    }

    boolean cancel(final SimulatedOrder held, final long nowNanos) {
        final Pending slot = this.actions.offer();
        if (slot == null) {
            return false;
        }
        this.cancel.wrap(slot.buffer);
        this.cancel
                .encoder
                .exchangeId(held.exchangeId)
                .securityId(held.securityId)
                .timestampSend(nowNanos);
        this.cancel.encodeClientOid(held.counter, held.strategyId);
        return pend(slot, CancelOrderDecoder.TEMPLATE_ID, this.cancel.totalMessageSize(), nowNanos);
    }

    boolean amend(final SimulatedOrder held, final long nowNanos) {
        final Pending slot = this.actions.offer();
        if (slot == null) {
            return false;
        }
        this.modify.wrap(slot.buffer);
        this.modify
                .encoder
                .exchangeId(held.exchangeId)
                .securityId(held.securityId)
                .timestampSend(nowNanos);
        this.modify.encodeClientOid(held.counter, held.strategyId);
        this.modify
                .encoder
                .price(held.amendPrice)
                .size(held.amendSize)
                .orderType(held.orderType)
                .timeInForce(held.timeInForce);
        this.modify.encoder.flags().clear();
        this.modify.encoder.flags().postOnly(held.amendPostOnly);
        return pend(slot, ModifyOrderDecoder.TEMPLATE_ID, this.modify.totalMessageSize(), nowNanos);
    }

    private boolean pend(final Pending slot, final int templateId, final int length, final long nowNanos) {
        slot.templateId = templateId;
        slot.length = length;
        slot.dueNanos = nowNanos + this.exchange.simulateNetworkLatency();
        return true;
    }

    /** A record of the listing's stream; a bad one moves the clock but neither the book nor any order. */
    void onMarketData(final Mbp10Schema record, final long nowNanos) {
        if (!record.isBadData()) {
            this.clean = true;
        }
        schedule(this.exchange.onMarketData(record), nowNanos);
    }

    /**
     * Hands the venue what has reached it, lets it match what has come due, and sends back what has made the trip.
     *
     * @return how much it did
     */
    int work(final long nowNanos, final ReportSink sink) {
        int work = 0;
        Pending action;
        while ((action = this.actions.peek()) != null && action.dueNanos <= nowNanos) {
            schedule(arrive(action, nowNanos), nowNanos);
            this.actions.poll();
            work++;
        }
        final List<OrderExecutionReport> due = this.exchange.processDue(nowNanos);
        schedule(due, nowNanos);
        work += due.size();
        Pending report;
        while ((report = this.reports.peek()) != null && report.dueNanos <= nowNanos) {
            this.outgoing.wrap(report.buffer);
            if (!sink.deliver(this.slot, this.outgoing)) {
                break;
            }
            this.reports.poll();
            work++;
        }
        return work;
    }

    /** What is still on its way either way: a book that holds none can be dropped without losing anything. */
    boolean idle() {
        return this.actions.isEmpty() && this.reports.isEmpty();
    }

    private List<OrderExecutionReport> arrive(final Pending action, final long nowNanos) {
        if (action.templateId == OrderDecoder.TEMPLATE_ID) {
            this.order.wrap(action.buffer);
            return this.exchange.submitOrder(this.order, nowNanos);
        }
        if (action.templateId == CancelOrderDecoder.TEMPLATE_ID) {
            this.cancel.wrap(action.buffer);
            return this.exchange.cancelOrder(this.cancel, nowNanos);
        }
        this.modify.wrap(action.buffer);
        return this.exchange.modifyOrder(this.modify, nowNanos);
    }

    private void schedule(final List<OrderExecutionReport> sent, final long nowNanos) {
        if (sent.isEmpty()) {
            return;
        }
        final long dueNanos = nowNanos + this.exchange.simulateNetworkLatency();
        for (int i = 0; i < sent.size(); i++) {
            final OrderExecutionReport report = sent.get(i);
            report.encoder.timestampEvent(nowNanos);
            report.encoder.timestampRecv(dueNanos);
            writeExchangeOrderId(report);
            final Pending slot = this.reports.offer();
            if (slot == null) {
                // A session whose reports pile up this far isn't reading them; the order's end still comes, later.
                continue;
            }
            slot.buffer.putBytes(0, report.buffer, 0, EXEC_REPORT_SIZE);
            slot.templateId = report.templateId();
            slot.length = EXEC_REPORT_SIZE;
            slot.dueNanos = dueNanos;
        }
    }

    private void writeExchangeOrderId(final OrderExecutionReport report) {
        this.orderIdBuffer.clear();
        this.orderIdBuffer.put(this.orderIdPrefix);
        ByteBufferUtils.putLongAscii(this.orderIdBuffer, report.getClientOidCounter());
        final int length = this.orderIdBuffer.position();
        for (int i = 0; i < this.orderIdBytes.length; i++) {
            report.encoder.exchangeOrderId(i, i < length ? this.orderIdBytes[i] : 0);
        }
    }

    /** Copies a report into an account report slot, tagged with the session's slot. */
    static void copy(final AccountReport into, final long slot, final OrderExecutionReport report) {
        into.sessionSlot = slot;
        into.report.buffer.putBytes(0, report.buffer, 0, report.totalMessageSize());
    }

    private static final class Pending {
        final UnsafeBuffer buffer;
        int templateId;
        int length;
        long dueNanos;

        Pending(final int size) {
            this.buffer = new UnsafeBuffer(new byte[size]);
        }
    }
}
