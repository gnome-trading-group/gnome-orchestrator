package group.gnometrading.orchestrator.gateway.accounts;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.metrics.SampleBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import org.agrona.collections.Long2LongHashMap;

/**
 * The {@code gateway.lanes} sample: once per account per lane minute, as soon as a look finds it closed, what its lane
 * did in it, the same numbers the gateway's minute document carries ({@link LaneAdmissions.Minute}): sends and how long
 * cancels and orders waited for the budget, holds, refusals and drops, the venue's answer times and requests it never
 * answered, how often every connection was busy, the venue's 429s since the account's last sample, and each session's
 * sends (at most {@link #MAX_SESSIONS}). The minute's {@code tsMs} is when it ended.
 *
 * <p>Reads the lane's published last minute, never takes it, so the minute document and the alerts read the same.
 * Runs on the sample poster's thread; allocates nothing once constructed.
 */
public final class LanesSampler {

    public static final String KIND = "gateway.lanes";
    static final int MAX_SESSIONS = 300;

    private final SampleBuffer samples;
    private final Lane[] accounts;
    private final LaneAdmissions[] admissions;
    private final LongFunction<String> sessionIdOfSlot;
    private final LongSupplier nanoClock;
    private final long[] published;
    private final long[] refusalsSeen;

    /** An account as sampled: its id and name, and the venue's 429s to it so far. */
    public record Lane(int accountId, String name, LongSupplier venueRefusals, LaneAdmissions admissions) {

        public static Lane of(final GatewayAccount account, final LaneAdmissions admissions) {
            return new Lane(account.accountId(), account.name(), account::venueRefusals, admissions);
        }
    }

    /**
     * @param sessionIdOfSlot the session holding a slot, or null when none does now
     */
    public LanesSampler(
            final SampleBuffer samples,
            final List<Lane> accounts,
            final LongFunction<String> sessionIdOfSlot,
            final LongSupplier nanoClock) {
        this.samples = samples;
        this.accounts = accounts.toArray(new Lane[0]);
        this.admissions = new LaneAdmissions[this.accounts.length];
        for (int i = 0; i < this.accounts.length; i++) {
            this.admissions[i] = this.accounts[i].admissions();
        }
        this.sessionIdOfSlot = sessionIdOfSlot;
        this.nanoClock = nanoClock;
        this.published = new long[this.accounts.length];
        this.refusalsSeen = new long[this.accounts.length];
    }

    /** Samples each account whose minute closed since it last did; @return whether any */
    public boolean sampleIfDue(final long nowMs) {
        final long now = nanoClock.getAsLong();
        boolean sampled = false;
        for (int i = 0; i < accounts.length; i++) {
            final LaneAdmissions lane = admissions[i];
            final long ended = lane.lastMinuteEndedNanos();
            if (ended == 0 || ended == published[i] || now - ended > LaneAdmissions.MINUTE_NANOS) {
                continue;
            }
            published[i] = ended;
            write(i, lane, nowMs - TimeUnit.NANOSECONDS.toMillis(now - ended));
            sampled = true;
        }
        return sampled;
    }

    private void write(final int index, final LaneAdmissions lane, final long endedMs) {
        final Lane account = accounts[index];
        final long refusals = account.venueRefusals().getAsLong();
        final JsonEncoder json = samples.begin(KIND, endedMs);
        try {
            json.writeObjectEntry("accountId", account.accountId())
                    .writeComma()
                    .writeObjectEntry("name", account.name())
                    .writeComma()
                    .writeObjectEntry("cancels", lane.lastCancels())
                    .writeComma()
                    .writeObjectEntry("cancelWaitP99Us", lane.lastCancelP99Micros())
                    .writeComma()
                    .writeObjectEntry("cancelWaitMaxUs", lane.lastCancelMaxMicros())
                    .writeComma()
                    .writeObjectEntry("orders", lane.lastOrders())
                    .writeComma()
                    .writeObjectEntry("orderWaitP99Us", lane.lastOrderP99Micros())
                    .writeComma()
                    .writeObjectEntry("orderWaitMaxUs", lane.lastOrderMaxMicros())
                    .writeComma()
                    .writeObjectEntry("held", lane.lastHeld())
                    .writeComma()
                    .writeObjectEntry("refusedWaiting", lane.lastExpired())
                    .writeComma()
                    .writeObjectEntry("refusedWithoutConnection", lane.lastExpiredWithoutConnection())
                    .writeComma()
                    .writeObjectEntry("dropped", lane.lastDropped())
                    .writeComma()
                    .writeObjectEntry("orderAnswerP50Us", lane.lastOrderAnswerP50Micros())
                    .writeComma()
                    .writeObjectEntry("orderAnswerP99Us", lane.lastOrderAnswerP99Micros())
                    .writeComma()
                    .writeObjectEntry("cancelAnswerP50Us", lane.lastCancelAnswerP50Micros())
                    .writeComma()
                    .writeObjectEntry("cancelAnswerP99Us", lane.lastCancelAnswerP99Micros())
                    .writeComma()
                    .writeObjectEntry("unanswered", lane.lastUnansweredCount())
                    .writeComma()
                    .writeObjectEntry("connectionsBusy", lane.lastConnectionsBusy())
                    .writeComma()
                    .writeObjectEntry("venueRefusals", refusals - refusalsSeen[index])
                    .writeComma()
                    .writeString("sessions")
                    .writeColon()
                    .writeArrayStart();
            writeSessions(json, lane.lastSentBySlotMap());
            json.writeArrayEnd();
            samples.commit();
        } catch (RuntimeException e) {
            samples.abandon();
            throw e;
        } finally {
            refusalsSeen[index] = refusals;
        }
    }

    private void writeSessions(final JsonEncoder json, final Long2LongHashMap sent) {
        if (sent == null) {
            return;
        }
        int written = 0;
        final Long2LongHashMap.KeyIterator slots = sent.keySet().iterator();
        while (slots.hasNext() && written < MAX_SESSIONS) {
            final long slot = slots.nextValue();
            if (written > 0) {
                json.writeComma();
            }
            final String sessionId = sessionIdOfSlot.apply(slot);
            json.writeObjectStart().writeObjectEntry("slot", slot).writeComma();
            if (sessionId == null) {
                json.writeString("sessionId").writeColon().writeNull();
            } else {
                json.writeObjectEntry("sessionId", sessionId);
            }
            json.writeComma().writeObjectEntry("sent", sent.get(slot)).writeObjectEnd();
            written++;
        }
    }
}
