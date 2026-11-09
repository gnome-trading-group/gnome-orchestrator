package group.gnometrading.orchestrator.gateway.transport;

import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.orchestrator.gateway.core.OrderTotals;
import group.gnometrading.orchestrator.gateway.core.ReplayRing;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.gateway.MessageHeaderEncoder;
import group.gnometrading.schemas.gateway.OrderStatusEncoder;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Sends a session one {@code OrderStatus} per order it has open, or that ended with its last report not yet sent: the
 * state, cumulative and remaining quantity, filled notional and fees its {@link OrderTotals} hold. Sent instead of the
 * reports themselves when they can't be replayed as they are: after a restart (the catch-up reports every fill again)
 * or once the session falls past its ring. The session applies the difference from what it holds, so a status it already
 * agrees with changes nothing. Egress's; allocates nothing.
 */
public final class OrderStatuses {

    private final OrderStatusEncoder statusEncoder = new OrderStatusEncoder();
    private final MessageHeaderEncoder header = new MessageHeaderEncoder();
    private final UnsafeBuffer scratch = new UnsafeBuffer(new byte[ReplayRing.SLOT_LENGTH]);

    /**
     * Appends a status for each of the session's orders to its ring, numbered after everything in it.
     *
     * @return how many were appended
     */
    int summarise(final GatewaySession session, final JournalRecorder journal) {
        int emitted = 0;
        for (final OrderTotals.Order order : session.totals.orders()) {
            this.statusEncoder
                    .wrapAndApplyHeader(this.scratch, 0, this.header)
                    .sessionSeq(session.slot)
                    .reportSeq(0)
                    .clientOidCounter(order.counter)
                    .exchangeId((int) (order.listingKey >>> 32))
                    .securityId(order.listingKey & 0xFFFF_FFFFL)
                    .state(order.state)
                    .venueOrderId("")
                    .cumulativeQty(order.cumulativeQty)
                    .leavesQty(order.leavesQty)
                    .notional(order.notional)
                    .fees(order.fees);
            final int length = MessageHeaderEncoder.ENCODED_LENGTH + this.statusEncoder.encodedLength();
            final ReplayRing replay = session.replay;
            final long sequence = replay.append(this.scratch, 0, length, order.counter);
            this.statusEncoder
                    .wrap(replay.buffer(), replay.offset(sequence) + SbeMessage.HEADER_LENGTH)
                    .reportSeq(sequence);
            // Its last report is now this status: an ended order is forgotten once it has gone out.
            order.lastSeq = sequence;
            journal.record(session.slot, replay.buffer(), replay.offset(sequence), length);
            emitted++;
        }
        return emitted;
    }
}
