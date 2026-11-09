package group.gnometrading.orchestrator.gateway.transport;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import org.agrona.concurrent.EpochNanoClock;

/**
 * The reports the gateway makes itself, for a command it refuses before any lane sees it: a NEW or AMEND from a session
 * that isn't registered, on a listing it doesn't lease, or while the gateway or the account can't take it. Written to
 * a ring egress drains with the accounts' reports, tagged with the session's slot. Ingress's thread; allocates nothing.
 */
public final class GatewayRejects {

    private final ManyToOneRingBuffer<AccountReport> out;
    private final EpochNanoClock clock;
    private final OrderExecutionReport report = new OrderExecutionReport();

    public GatewayRejects(final ManyToOneRingBuffer<AccountReport> out, final EpochNanoClock clock) {
        this.out = out;
        this.clock = clock;
    }

    /** Refuses a NEW. @return false if the ring is full, nothing written */
    boolean reject(
            final long slot,
            final long counter,
            final int strategyId,
            final int exchangeId,
            final long securityId,
            final RejectReason reason) {
        return write(slot, counter, strategyId, exchangeId, securityId, ExecType.REJECT, OrderStatus.REJECTED, reason);
    }

    /** Refuses a cancel or amend; the order, if any, keeps working. @return false if the ring is full */
    boolean cancelReject(
            final long slot,
            final long counter,
            final int strategyId,
            final int exchangeId,
            final long securityId,
            final RejectReason reason) {
        return write(
                slot,
                counter,
                strategyId,
                exchangeId,
                securityId,
                ExecType.CANCEL_REJECT,
                OrderStatus.CANCELED,
                reason);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private boolean write(
            final long slot,
            final long counter,
            final int strategyId,
            final int exchangeId,
            final long securityId,
            final ExecType execType,
            final OrderStatus status,
            final RejectReason reason) {
        final int index = this.out.tryClaim();
        if (index < 0) {
            return false;
        }
        final OrderExecutionReportEncoder encoder = this.report.encoder;
        encoder.exchangeId(exchangeId);
        encoder.securityId(securityId);
        for (int i = 0; i < OrderExecutionReportEncoder.exchangeOrderIdLength(); i++) {
            encoder.exchangeOrderId(i, (byte) 0);
        }
        this.report.encodeClientOid(counter, strategyId);
        encoder.execType(execType);
        encoder.orderStatus(status);
        encoder.filledQty(OrderExecutionReportEncoder.filledQtyNullValue());
        encoder.fillPrice(OrderExecutionReportEncoder.fillPriceNullValue());
        encoder.cumulativeQty(0);
        encoder.leavesQty(0);
        encoder.timestampEvent(OrderExecutionReportEncoder.timestampEventNullValue());
        encoder.timestampRecv(this.clock.nanoTime());
        encoder.flags().clear();
        encoder.rejectReason(reason);
        encoder.fee(OrderExecutionReportEncoder.feeNullValue());
        encoder.liquidity(Liquidity.NULL_VAL);
        final AccountReport slotReport = this.out.indexAt(index);
        slotReport.sessionSlot = slot;
        slotReport.report.buffer.putBytes(0, this.report.buffer, 0, this.report.totalMessageSize());
        this.out.commit(index);
        return true;
    }
}
