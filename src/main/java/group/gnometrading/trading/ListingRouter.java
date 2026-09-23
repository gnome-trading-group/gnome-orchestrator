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
 * Handles per-listing order routing and execution report aggregation for multi-listing sessions.
 *
 * <p>In the outbound direction: reads Order/CancelOrder/ModifyOrder messages from the OMS outbound
 * buffer, inspects {@code exchangeId} and {@code securityId}, and routes each message to the
 * correct per-listing outbound buffer consumed by the corresponding outbound gateway.
 *
 * <p>In the inbound direction: polls all per-listing execution report buffers and forwards every
 * report to the single combined execution report buffer consumed by the OmsAgent.
 *
 * <p>Runs on its own thread. Is the sole producer of each per-listing outbound buffer and of the
 * combined execution report buffer, satisfying the single-producer constraint.
 *
 * <p>Only instantiated for multi-listing sessions. Single-listing sessions wire buffers directly
 * with no routing hop.
 */
public final class ListingRouter implements GnomeAgent {

    private final SequencedPoller orderPoller;
    private final List<SequencedPoller> execReportPollers;
    private final Map<Long, SequencedRingBuffer<?>> outboundByListing;
    private final SequencedRingBuffer<OrderExecutionReport> combinedExecReportBuffer;

    private final Order order = new Order();
    private final CancelOrder cancelOrder = new CancelOrder();
    private final ModifyOrder modifyOrder = new ModifyOrder();

    public ListingRouter(
            SequencedRingBuffer<?> orderOutboundBuffer,
            Map<Long, SequencedRingBuffer<?>> outboundByListing,
            Collection<SequencedRingBuffer<OrderExecutionReport>> perListingExecReportBuffers,
            SequencedRingBuffer<OrderExecutionReport> combinedExecReportBuffer) {
        this.outboundByListing = outboundByListing;
        this.combinedExecReportBuffer = combinedExecReportBuffer;
        this.orderPoller = orderOutboundBuffer.createPoller(this::onOrderOutbound);
        this.execReportPollers = perListingExecReportBuffers.stream()
                .map(buf -> buf.createPoller(this::onExecReport))
                .toList();
    }

    @Override
    public String roleName() {
        return "listing-router";
    }

    @Override
    public void onStart() {}

    @Override
    public void onClose() {
        try {
            orderPoller.poll();
        } catch (Exception e) { // best-effort drain on shutdown
        }
        for (SequencedPoller poller : execReportPollers) {
            try {
                poller.poll();
            } catch (Exception e) { // best-effort drain on shutdown
            }
        }
    }

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
        long key = readRoutingKey(templateId, buf);
        if (key >= 0) {
            SequencedRingBuffer<?> target = outboundByListing.get(key);
            if (target != null) {
                target.publishRaw(buf, templateId, len);
            }
        }
    }

    private long readRoutingKey(int templateId, UnsafeBuffer buf) {
        if (templateId == OrderDecoder.TEMPLATE_ID) {
            order.wrap(buf);
            return routingKey(order.decoder.exchangeId(), order.decoder.securityId());
        } else if (templateId == CancelOrderDecoder.TEMPLATE_ID) {
            cancelOrder.wrap(buf);
            return routingKey(cancelOrder.decoder.exchangeId(), cancelOrder.decoder.securityId());
        } else if (templateId == ModifyOrderDecoder.TEMPLATE_ID) {
            modifyOrder.wrap(buf);
            return routingKey(modifyOrder.decoder.exchangeId(), modifyOrder.decoder.securityId());
        }
        return -1L;
    }

    static long routingKey(int exchangeId, long securityId) {
        return ((long) exchangeId << 32) | (securityId & 0xFFFFFFFFL);
    }

    private void onExecReport(long globalSeq, int templateId, UnsafeBuffer buf, int len) throws Exception {
        combinedExecReportBuffer.publishRaw(buf, templateId, len);
    }
}
