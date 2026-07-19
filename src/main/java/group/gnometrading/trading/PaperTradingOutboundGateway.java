package group.gnometrading.trading;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.Mbp10Decoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.simulation.exchange.SimulatedExchange;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Bridges the OMS outbound ring buffer to a {@link SimulatedExchange}, routing live market data
 * into the simulation so the book state stays current, and converting simulated fills back into
 * {@link OrderExecutionReport} messages that the OMS forwards to the strategy.
 *
 * <p>Polling order: market data first so the book is up to date before any orders are matched.
 *
 * <p>{@link SimulatedExchange} is not thread-safe. All calls to it happen on this agent's thread.
 */
public final class PaperTradingOutboundGateway implements GnomeAgent {

    private final SimulatedExchange exchange;
    private final SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private final SequencedPoller marketDataPoller;
    private final SequencedPoller orderOutboundPoller;

    private final Mbp10Schema mbp10 = new Mbp10Schema();
    private final Order order = new Order();
    private final CancelOrder cancelOrder = new CancelOrder();
    private final ModifyOrder modifyOrder = new ModifyOrder();

    public PaperTradingOutboundGateway(
            SimulatedExchange exchange,
            SequencedRingBuffer<?> marketDataBuffer,
            SequencedRingBuffer<?> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer) {
        this.exchange = exchange;
        this.execReportBuffer = execReportBuffer;
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
        work += orderOutboundPoller.poll();
        return work;
    }

    private void onMarketData(long globalSeq, int templateId, UnsafeBuffer buf, int len) throws Exception {
        if (templateId == Mbp10Decoder.TEMPLATE_ID) {
            mbp10.wrap(buf);
            writeExecReports(exchange.onMarketData(mbp10));
        }
    }

    private void onOrderOutbound(long globalSeq, int templateId, UnsafeBuffer buf, int len) throws Exception {
        if (templateId == OrderDecoder.TEMPLATE_ID) {
            order.wrap(buf);
            writeExecReports(exchange.submitOrder(order));
        } else if (templateId == CancelOrderDecoder.TEMPLATE_ID) {
            cancelOrder.wrap(buf);
            writeExecReports(exchange.cancelOrder(cancelOrder));
        } else if (templateId == ModifyOrderDecoder.TEMPLATE_ID) {
            modifyOrder.wrap(buf);
            writeExecReports(exchange.modifyOrder(modifyOrder));
        }
    }

    private void writeExecReports(List<OrderExecutionReport> reports) {
        for (OrderExecutionReport report : reports) {
            execReportBuffer.publishRaw(
                    report.buffer, report.messageHeaderDecoder.templateId(), report.totalMessageSize());
        }
    }
}
