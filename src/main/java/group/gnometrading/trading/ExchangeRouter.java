package group.gnometrading.trading;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Handles cross-exchange order routing and execution report aggregation for multi-listing sessions.
 *
 * <p>In the outbound direction: reads Order/CancelOrder/ModifyOrder messages from the OMS outbound
 * buffer, inspects {@code exchangeId}, and routes each message to the correct per-exchange outbound
 * buffer consumed by the corresponding outbound gateway.
 *
 * <p>In the inbound direction: polls all per-exchange execution report buffers and forwards every
 * report to the single combined execution report buffer consumed by the OmsAgent.
 *
 * <p>Runs on its own thread. Is the sole producer of each per-exchange outbound buffer and of the
 * combined execution report buffer, satisfying the single-producer constraint.
 *
 * <p>Only instantiated for multi-exchange sessions. Single-exchange sessions wire buffers directly
 * with no routing hop.
 */
public final class ExchangeRouter implements GnomeAgent {

    private final SequencedPoller orderPoller;
    private final List<SequencedPoller> execReportPollers;
    private final Map<Integer, SequencedRingBuffer<?>> outboundByExchangeId;
    private final SequencedRingBuffer<OrderExecutionReport> combinedExecReportBuffer;

    private final Order order = new Order();
    private final CancelOrder cancelOrder = new CancelOrder();
    private final ModifyOrder modifyOrder = new ModifyOrder();

    public ExchangeRouter(
            SequencedRingBuffer<?> orderOutboundBuffer,
            Map<Integer, SequencedRingBuffer<?>> outboundByExchangeId,
            Collection<SequencedRingBuffer<OrderExecutionReport>> perExchangeExecReportBuffers,
            SequencedRingBuffer<OrderExecutionReport> combinedExecReportBuffer) {
        this.outboundByExchangeId = outboundByExchangeId;
        this.combinedExecReportBuffer = combinedExecReportBuffer;
        this.orderPoller = orderOutboundBuffer.createPoller(this::onOrderOutbound);
        this.execReportPollers = perExchangeExecReportBuffers.stream()
                .map(buf -> buf.createPoller(this::onExecReport))
                .toList();
    }

    @Override
    public String roleName() {
        return "exchange-router";
    }

    @Override
    public void onStart() {}

    @Override
    public int doWork() throws Exception {
        int work = 0;
        work += orderPoller.poll();
        for (SequencedPoller poller : execReportPollers) {
            work += poller.poll();
        }
        return work;
    }

    private void onOrderOutbound(long globalSeq, int templateId, UnsafeBuffer buf, int len) throws Exception {
        int exchangeId = readExchangeId(templateId, buf);
        if (exchangeId >= 0) {
            SequencedRingBuffer<?> target = outboundByExchangeId.get(exchangeId);
            if (target != null) {
                target.publishRaw(buf, templateId, len);
            }
        }
    }

    private int readExchangeId(int templateId, UnsafeBuffer buf) {
        if (templateId == OrderDecoder.TEMPLATE_ID) {
            order.wrap(buf);
            return order.decoder.exchangeId();
        } else if (templateId == CancelOrderDecoder.TEMPLATE_ID) {
            cancelOrder.wrap(buf);
            return cancelOrder.decoder.exchangeId();
        } else if (templateId == ModifyOrderDecoder.TEMPLATE_ID) {
            modifyOrder.wrap(buf);
            return modifyOrder.decoder.exchangeId();
        }
        return -1;
    }

    private void onExecReport(long globalSeq, int templateId, UnsafeBuffer buf, int len) throws Exception {
        combinedExecReportBuffer.publishRaw(buf, templateId, len);
    }
}
