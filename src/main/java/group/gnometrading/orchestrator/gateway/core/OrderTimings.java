package group.gnometrading.orchestrator.gateway.core;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.lane.RequestTiming;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.schemas.gateway.MessageHeaderEncoder;
import group.gnometrading.schemas.gateway.OrderTimingEncoder;
import group.gnometrading.schemas.gateway.TimingOutcome;

/**
 * Encodes each request's timing as an {@code OrderTiming} for its session and puts it on the ring to egress. One per
 * lane (its encoder is the lane's), all on one ring. Never waits: a timing the ring has no room for is dropped and
 * counted, as timings are best effort and the lane must not stall for them. Allocates nothing.
 */
public final class OrderTimings {

    /** For a gateway that sends no timings: everything told is ignored. */
    public static final OrderTimings NONE = new OrderTimings(null);

    private final ManyToOneRingBuffer<TimingMessage> ring;
    private final OrderTimingEncoder encoder = new OrderTimingEncoder();
    private final MessageHeaderEncoder header = new MessageHeaderEncoder();
    // Written on the sending lane's thread only; read by the gateway's samples.
    private volatile long sent;
    private volatile long dropped;

    public OrderTimings(final ManyToOneRingBuffer<TimingMessage> ring) {
        this.ring = ring;
    }

    /**
     * Sends {@code timing} for account {@code accountId}'s lane, its own times moved onto the epoch by
     * {@code toEpochNanos} (0 for times already epoch); a time of 0 (a step that didn't happen) stays 0. The venue's
     * time is epoch already.
     */
    public void send(final int accountId, final RequestTiming timing, final long toEpochNanos) {
        if (this.ring == null) {
            return;
        }
        final int index = this.ring.tryClaim();
        if (index < 0) {
            this.dropped = this.dropped + 1;
            return;
        }
        final TimingMessage message = this.ring.indexAt(index);
        message.slot = timing.sessionSlot;
        this.encoder
                .wrapAndApplyHeader(message.buffer, 0, this.header)
                .venueAccountId(accountId)
                .sessionSeq(timing.sessionSlot)
                .clientOidCounter(timing.clientOidCounter)
                .requestClass(schemaClass(timing.requestClass))
                .outcome(outcome(timing.outcome))
                .ingressNanos(epoch(timing.ingressNanos, toEpochNanos))
                .takenNanos(epoch(timing.takenNanos, toEpochNanos))
                .decidedNanos(epoch(timing.decidedNanos, toEpochNanos))
                .wireSentNanos(epoch(timing.wireSentNanos, toEpochNanos))
                .answeredNanos(epoch(timing.answeredNanos, toEpochNanos))
                .answerSlackNanos(timing.answerSlackNanos)
                .venueCode(timing.venueCode)
                .venueTsNanos(timing.venueTsNanos)
                .venueProcessingMicros(timing.venueProcessingMicros);
        message.length = MessageHeaderEncoder.ENCODED_LENGTH + this.encoder.encodedLength();
        this.ring.commit(index);
        this.sent = this.sent + 1;
    }

    /** Timings put on the ring for egress. */
    public long sent() {
        return this.sent;
    }

    /** Timings dropped because the ring to egress was full. */
    public long dropped() {
        return this.dropped;
    }

    private static long epoch(final long laneNanos, final long toEpochNanos) {
        return laneNanos == 0 ? 0 : laneNanos + toEpochNanos;
    }

    public static group.gnometrading.schemas.gateway.RequestClass schemaClass(final RequestClass type) {
        return switch (type) {
            case CANCEL -> group.gnometrading.schemas.gateway.RequestClass.CANCEL;
            case CATCH_UP -> group.gnometrading.schemas.gateway.RequestClass.CATCH_UP;
            case AMEND -> group.gnometrading.schemas.gateway.RequestClass.AMEND;
            case NEW -> group.gnometrading.schemas.gateway.RequestClass.NEW;
            case BACKGROUND -> group.gnometrading.schemas.gateway.RequestClass.BACKGROUND;
        };
    }

    private static TimingOutcome outcome(final int outcome) {
        return switch (outcome) {
            case RequestTiming.ANSWERED -> TimingOutcome.ANSWERED;
            case RequestTiming.REJECTED -> TimingOutcome.REJECTED;
            case RequestTiming.TRANSPORT_ERROR -> TimingOutcome.TRANSPORT_ERROR;
            case RequestTiming.NO_ANSWER -> TimingOutcome.NO_ANSWER;
            default -> TimingOutcome.NOT_SENT;
        };
    }
}
