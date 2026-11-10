package group.gnometrading.orchestrator.gateway.accounts;

import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.orchestrator.runtime.LatencyHistogram;
import group.gnometrading.schemas.gateway.AdmissionAction;
import group.gnometrading.schemas.gateway.AdmissionRecordEncoder;
import group.gnometrading.schemas.gateway.MessageHeaderEncoder;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * What one account's lane decided about each request: written to the gateway's journal as an {@code AdmissionRecord},
 * and counted by the minute for the gateway's status line (how many went, how long cancels and orders waited for the
 * budget, how many were held, refused for waiting too long, or dropped by a cancel; how long the venue took to answer
 * orders and cancels, how many it never answered, and how often every connection was in use). Told on the lane's thread, and
 * allocates nothing there; the last full minute is read from any thread, each figure on its own.
 */
public final class LaneAdmissions implements AccountLane.Admissions {

    public static final long MINUTE_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final int accountId;
    private final JournalRecorder journal;
    private final LongSupplier nanoClock;
    private final UnsafeBuffer buffer =
            new UnsafeBuffer(new byte[MessageHeaderEncoder.ENCODED_LENGTH + AdmissionRecordEncoder.BLOCK_LENGTH]);
    private final AdmissionRecordEncoder encoder = new AdmissionRecordEncoder();
    private final MessageHeaderEncoder header = new MessageHeaderEncoder();

    // The minute being counted, on the lane's thread.
    private final LatencyHistogram cancelWaits = new LatencyHistogram();
    private final LatencyHistogram orderWaits = new LatencyHistogram();
    private final LatencyHistogram orderAnswers = new LatencyHistogram();
    private final LatencyHistogram cancelAnswers = new LatencyHistogram();
    private long unanswered;
    private long connectionsBusy;
    private long held;
    private long expired;
    private long expiredWithoutConnection;
    private long dropped;
    private long minuteEndsNanos;

    // The last full minute, for the status line.
    private volatile long lastCancels;
    private volatile long lastCancelP99Micros;
    private volatile long lastCancelMaxMicros;
    private volatile long lastOrders;
    private volatile long lastOrderP99Micros;
    private volatile long lastOrderMaxMicros;
    private volatile long lastHeld;
    private volatile long lastExpired;
    private volatile long lastExpiredWithoutConnection;
    private volatile long lastDropped;
    private volatile long lastOrderAnswerP50Micros;
    private volatile long lastOrderAnswerP99Micros;
    private volatile long lastCancelAnswerP50Micros;
    private volatile long lastCancelAnswerP99Micros;
    private volatile long lastUnanswered;
    private volatile long lastConnectionsBusy;
    private volatile long lastMinuteEndedNanos;

    public LaneAdmissions(final int accountId, final JournalRecorder journal, final LongSupplier nanoClock) {
        this.accountId = accountId;
        this.journal = journal;
        this.nanoClock = nanoClock;
        this.minuteEndsNanos = nanoClock.getAsLong() + MINUTE_NANOS;
    }

    @Override
    public void decided(
            final long sessionSlot,
            final long clientOidCounter,
            final RequestClass type,
            final AdmissionAction action,
            final long queuedNanos,
            final long tokensLeft) {
        rollMinute();
        count(type, action, queuedNanos);
        this.encoder
                .wrapAndApplyHeader(this.buffer, 0, this.header)
                .venueAccountId(this.accountId)
                .sessionSeq(sessionSlot)
                .clientOidCounter(clientOidCounter)
                .requestClass(schemaClass(type))
                .action(action)
                .queuedNanos(queuedNanos)
                .tokensLeft(tokensLeft);
        this.journal.record(
                sessionSlot, this.buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + this.encoder.encodedLength());
    }

    @Override
    public void answered(final RequestClass type, final long roundTripNanos, final boolean whole) {
        rollMinute();
        if (!whole) {
            this.unanswered++;
            return;
        }
        final long micros = TimeUnit.NANOSECONDS.toMicros(roundTripNanos);
        if (type == RequestClass.CANCEL) {
            this.cancelAnswers.record(micros);
        } else if (type.isOrder()) {
            this.orderAnswers.record(micros);
        }
    }

    @Override
    public void connectionsBusy() {
        rollMinute();
        this.connectionsBusy++;
    }

    @Override
    public void refusedWithoutConnection() {
        rollMinute();
        this.expiredWithoutConnection++;
    }

    private void rollMinute() {
        final long now = this.nanoClock.getAsLong();
        if (now - this.minuteEndsNanos >= 0) {
            endMinute(now);
        }
    }

    private void count(final RequestClass type, final AdmissionAction action, final long queuedNanos) {
        switch (action) {
            case SENT -> {
                final long micros = TimeUnit.NANOSECONDS.toMicros(queuedNanos);
                if (type == RequestClass.CANCEL) {
                    this.cancelWaits.record(micros);
                } else if (type.isOrder()) {
                    this.orderWaits.record(micros);
                }
            }
            case HELD -> this.held++;
            case REJECTED_RATE_LIMITED -> this.expired++;
            case DROPPED_CONFLATED -> this.dropped++;
            default -> {}
        }
    }

    private void endMinute(final long now) {
        this.lastCancels = this.cancelWaits.count();
        this.lastCancelP99Micros = this.cancelWaits.percentileMicros(0.99);
        this.lastCancelMaxMicros = this.cancelWaits.maxMicros();
        this.lastOrders = this.orderWaits.count();
        this.lastOrderP99Micros = this.orderWaits.percentileMicros(0.99);
        this.lastOrderMaxMicros = this.orderWaits.maxMicros();
        this.lastHeld = this.held;
        this.lastExpired = this.expired;
        this.lastExpiredWithoutConnection = this.expiredWithoutConnection;
        this.lastDropped = this.dropped;
        this.lastOrderAnswerP50Micros = this.orderAnswers.percentileMicros(0.5);
        this.lastOrderAnswerP99Micros = this.orderAnswers.percentileMicros(0.99);
        this.lastCancelAnswerP50Micros = this.cancelAnswers.percentileMicros(0.5);
        this.lastCancelAnswerP99Micros = this.cancelAnswers.percentileMicros(0.99);
        this.lastUnanswered = this.unanswered;
        this.lastConnectionsBusy = this.connectionsBusy;
        this.orderAnswers.reset();
        this.cancelAnswers.reset();
        this.unanswered = 0;
        this.connectionsBusy = 0;
        this.lastMinuteEndedNanos = now;
        this.cancelWaits.reset();
        this.orderWaits.reset();
        this.held = 0;
        this.expired = 0;
        this.expiredWithoutConnection = 0;
        this.dropped = 0;
        this.minuteEndsNanos = now + MINUTE_NANOS;
    }

    /**
     * The last full minute as a status line, or null if it ended over two minutes ago: a lane that decided nothing since
     * has nothing to say. Allocates; for a side thread.
     */
    public String lastMinute(final long now) {
        final long ended = this.lastMinuteEndedNanos;
        if (ended == 0 || now - ended > 2 * MINUTE_NANOS) {
            return null;
        }
        return String.format(
                "%d cancels sent (waited p99 %d us, max %d us), %d orders and amends sent (waited p99 %d us, max %d us),"
                        + " %d held for the budget, %d refused for waiting too long (%d with no connection open),"
                        + " %d dropped by a cancel;"
                        + " venue answered orders in p50 %d us, p99 %d us, cancels in p50 %d us, p99 %d us, %d never;"
                        + " every connection in use %d times",
                this.lastCancels,
                this.lastCancelP99Micros,
                this.lastCancelMaxMicros,
                this.lastOrders,
                this.lastOrderP99Micros,
                this.lastOrderMaxMicros,
                this.lastHeld,
                this.lastExpired,
                this.lastExpiredWithoutConnection,
                this.lastDropped,
                this.lastOrderAnswerP50Micros,
                this.lastOrderAnswerP99Micros,
                this.lastCancelAnswerP50Micros,
                this.lastCancelAnswerP99Micros,
                this.lastUnanswered,
                this.lastConnectionsBusy);
    }

    private static group.gnometrading.schemas.gateway.RequestClass schemaClass(final RequestClass type) {
        return switch (type) {
            case CANCEL -> group.gnometrading.schemas.gateway.RequestClass.CANCEL;
            case CATCH_UP -> group.gnometrading.schemas.gateway.RequestClass.CATCH_UP;
            case AMEND -> group.gnometrading.schemas.gateway.RequestClass.AMEND;
            case NEW -> group.gnometrading.schemas.gateway.RequestClass.NEW;
            case BACKGROUND -> group.gnometrading.schemas.gateway.RequestClass.BACKGROUND;
        };
    }
}
