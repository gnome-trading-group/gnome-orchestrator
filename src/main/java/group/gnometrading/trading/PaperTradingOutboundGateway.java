package group.gnometrading.trading;

import group.gnometrading.collections.FixedCapacityQueue;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.CancelOrderEncoder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Mbp10Decoder;
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
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.simulation.exchange.SimulatedExchange;
import java.util.List;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Bridges the OMS outbound ring buffer to a {@link SimulatedExchange}, routing live market data
 * into the simulation so the book state stays current, and converting simulated fills back into
 * {@link OrderExecutionReport} messages that the OMS forwards to the strategy.
 *
 * <p>Polling order: market data first so the book is up to date, then drain matured actions so
 * orders are matched against the current book, then enqueue new pending actions, then deliver
 * ready reports.
 *
 * <p>{@link SimulatedExchange} is not thread-safe. All calls to it happen on this agent's thread.
 */
public final class PaperTradingOutboundGateway implements GnomeAgent {

    private static final int EXEC_REPORT_SIZE =
            MessageHeaderEncoder.ENCODED_LENGTH + OrderExecutionReportEncoder.BLOCK_LENGTH;
    private static final int MAX_ACTION_SIZE = MessageHeaderEncoder.ENCODED_LENGTH
            + Math.max(
                    OrderEncoder.BLOCK_LENGTH,
                    Math.max(CancelOrderEncoder.BLOCK_LENGTH, ModifyOrderEncoder.BLOCK_LENGTH));
    private static final int PENDING_CAPACITY = 256;

    private final SimulatedExchange exchange;
    private final SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private final SequencedPoller marketDataPoller;
    private final SequencedPoller orderOutboundPoller;
    private final EpochNanoClock clock;

    private final Mbp10Schema mbp10 = new Mbp10Schema();
    private final Order order = new Order();
    private final CancelOrder cancelOrder = new CancelOrder();
    private final ModifyOrder modifyOrder = new ModifyOrder();

    private final FixedCapacityQueue<PendingAction> pendingActions =
            new FixedCapacityQueue<>(PendingAction[]::new, PendingAction::new, PENDING_CAPACITY);
    private final FixedCapacityQueue<PendingReport> pendingReports =
            new FixedCapacityQueue<>(PendingReport[]::new, PendingReport::new, PENDING_CAPACITY);

    public PaperTradingOutboundGateway(
            SimulatedExchange exchange,
            SequencedRingBuffer<?> marketDataBuffer,
            SequencedRingBuffer<?> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            EpochNanoClock clock) {
        this.exchange = exchange;
        this.execReportBuffer = execReportBuffer;
        this.clock = clock;
        this.marketDataPoller = marketDataBuffer.createPoller(this::onMarketData);
        this.orderOutboundPoller = orderOutboundBuffer.createPoller(this::onOrderOutbound);
    }

    @Override
    public String roleName() {
        return "paper-trading-outbound-gateway";
    }

    @Override
    public void onStart() {}

    @Override
    public int doWork() throws Exception {
        int work = 0;
        work += marketDataPoller.poll();
        work += drainReadyActions();
        work += orderOutboundPoller.poll();
        work += drainReadyReports();
        return work;
    }

    private void onMarketData(long globalSeq, int templateId, UnsafeBuffer buf, int len) {
        if (templateId == Mbp10Decoder.TEMPLATE_ID) {
            mbp10.wrap(buf);
            scheduleExecReports(exchange.onMarketData(mbp10), exchange.simulateNetworkLatency());
        }
    }

    private void onOrderOutbound(long globalSeq, int templateId, UnsafeBuffer buf, int len) {
        if (templateId == OrderDecoder.TEMPLATE_ID
                || templateId == CancelOrderDecoder.TEMPLATE_ID
                || templateId == ModifyOrderDecoder.TEMPLATE_ID) {
            final PendingAction slot = pendingActions.offer();
            slot.buffer.putBytes(0, buf, 0, len);
            slot.templateId = templateId;
            slot.length = len;
            slot.deliveryNano = clock.nanoTime() + exchange.simulateNetworkLatency();
        }
    }

    private int drainReadyActions() {
        int count = 0;
        final long now = clock.nanoTime();
        PendingAction action;
        while ((action = pendingActions.peek()) != null) {
            if (now < action.deliveryNano) {
                break;
            }
            processAction(action);
            pendingActions.poll();
            count++;
        }
        return count;
    }

    private void processAction(final PendingAction action) {
        final List<OrderExecutionReport> reports;
        final long returnDelay;

        if (action.templateId == OrderDecoder.TEMPLATE_ID) {
            order.wrap(action.buffer);
            reports = exchange.submitOrder(order);
            final boolean isMaker = !hasFill(reports);
            returnDelay = exchange.simulateOrderProcessingTime(isMaker) + exchange.simulateNetworkLatency();
        } else if (action.templateId == CancelOrderDecoder.TEMPLATE_ID) {
            cancelOrder.wrap(action.buffer);
            reports = exchange.cancelOrder(cancelOrder);
            returnDelay = exchange.simulateOrderProcessingTime() + exchange.simulateNetworkLatency();
        } else if (action.templateId == ModifyOrderDecoder.TEMPLATE_ID) {
            modifyOrder.wrap(action.buffer);
            reports = exchange.modifyOrder(modifyOrder);
            returnDelay = exchange.simulateOrderProcessingTime() + exchange.simulateNetworkLatency();
        } else {
            return;
        }

        scheduleExecReports(reports, returnDelay);
    }

    private void scheduleExecReports(final List<OrderExecutionReport> reports, final long delay) {
        if (reports.isEmpty()) {
            return;
        }
        final long now = clock.nanoTime();
        final long deliveryNano = now + delay;
        for (int i = 0; i < reports.size(); i++) {
            final OrderExecutionReport report = reports.get(i);
            report.encoder.timestampEvent(now);
            report.encoder.timestampRecv(deliveryNano);
            final PendingReport slot = pendingReports.offer();
            slot.buffer.putBytes(0, report.buffer, 0, EXEC_REPORT_SIZE);
            slot.templateId = report.messageHeaderDecoder.templateId();
            slot.deliveryNano = deliveryNano;
        }
    }

    private int drainReadyReports() {
        int count = 0;
        final long now = clock.nanoTime();
        PendingReport slot;
        while ((slot = pendingReports.peek()) != null) {
            if (now < slot.deliveryNano) {
                break;
            }
            execReportBuffer.publishRaw(slot.buffer, slot.templateId, EXEC_REPORT_SIZE);
            pendingReports.poll();
            count++;
        }
        return count;
    }

    private static boolean hasFill(final List<OrderExecutionReport> reports) {
        for (int i = 0; i < reports.size(); i++) {
            final ExecType et = reports.get(i).decoder.execType();
            if (et == ExecType.FILL || et == ExecType.PARTIAL_FILL) {
                return true;
            }
        }
        return false;
    }

    private static final class PendingAction {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[MAX_ACTION_SIZE]);
        int templateId;
        int length;
        long deliveryNano;
    }

    private static final class PendingReport {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[EXEC_REPORT_SIZE]);
        int templateId;
        long deliveryNano;
    }
}
