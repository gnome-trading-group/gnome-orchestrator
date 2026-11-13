package group.gnometrading.orchestrator.gateway.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.latency.LatencyKey;
import group.gnometrading.latency.MinuteHistograms;
import group.gnometrading.latency.MinuteSummary;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.schemas.gateway.AdmissionAction;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** An account's minute as the gateway publishes it: what went, what waited, and each session's sends. */
class LaneAdmissionsTest {

    private final long[] now = {1_000};
    private final LaneAdmissions admissions = new LaneAdmissions(5, JournalRecorder.NONE, () -> this.now[0]);

    @Test
    void aMinuteCountsEachSessionsSendsAndOnlyWhatWasSent() {
        this.admissions.decided(11, 1, RequestClass.NEW, AdmissionAction.SENT, 0, 10);
        this.admissions.decided(11, 2, RequestClass.CANCEL, AdmissionAction.SENT, 0, 10);
        this.admissions.decided(12, 1, RequestClass.NEW, AdmissionAction.SENT, 0, 10);
        this.admissions.decided(12, 2, RequestClass.NEW, AdmissionAction.HELD, 0, 0);
        this.admissions.decided(12, 2, RequestClass.NEW, AdmissionAction.REJECTED_RATE_LIMITED, 0, 0);
        endMinute();

        final LaneAdmissions.Minute minute = this.admissions.minute(this.now[0]);
        assertEquals(Map.of(11L, 2L, 12L, 1L), minute.sentBySlot());
        assertEquals(1, minute.cancels());
        assertEquals(2, minute.orders());
        assertEquals(1, minute.held());
        assertEquals(1, minute.refusedWaiting());
    }

    @Test
    void eachMinuteStartsItsSessionsCountsAfresh() {
        this.admissions.decided(11, 1, RequestClass.NEW, AdmissionAction.SENT, 0, 10);
        endMinute();
        this.admissions.decided(12, 1, RequestClass.NEW, AdmissionAction.SENT, 0, 10);
        endMinute();

        assertEquals(Map.of(12L, 1L), this.admissions.minute(this.now[0]).sentBySlot());
    }

    @Test
    void aLaneQuietForOverTwoMinutesHasNoMinute() {
        this.admissions.decided(11, 1, RequestClass.NEW, AdmissionAction.SENT, 0, 10);
        endMinute();
        this.now[0] += 3 * LaneAdmissions.MINUTE_NANOS;

        assertNull(this.admissions.minute(this.now[0]));
    }

    @Test
    void eachStepOfARequestGoesIntoItsClasssHistogram() {
        final long[] wall = {100 * MinuteHistograms.MINUTE_NANOS};
        final LaneAdmissions timed = new LaneAdmissions(5, JournalRecorder.NONE, () -> this.now[0], () -> wall[0]);
        timed.taken(RequestClass.NEW, 1_000);
        timed.decided(11, 1, RequestClass.NEW, AdmissionAction.SENT, 2_000, 10);
        timed.decided(11, 1, RequestClass.NEW, AdmissionAction.HELD, 9_000, 10);
        timed.sent(RequestClass.NEW, 3_000);
        timed.answered(RequestClass.NEW, 4_000, true);
        timed.answered(RequestClass.CANCEL, 5_000, true);
        timed.answered(RequestClass.CANCEL, 6_000, false);
        wall[0] += MinuteHistograms.MINUTE_NANOS;
        timed.latency().roll(wall[0]);

        final MinuteSummary minute = new MinuteSummary(timed.latency().capacity());
        assertTrue(timed.latency().summarize(minute));
        final Map<String, Long> maxes = new TreeMap<>();
        for (int key = 0; key < minute.keys(); key++) {
            if (minute.count(key) > 0) {
                final LatencyKey label = timed.latency().label(key);
                maxes.put(label.metric() + " " + label.action(), minute.max(key));
            }
        }
        assertEquals(
                Map.of(
                        "gw.queue NEW", 1_000L,
                        "gw.budget_wait NEW", 2_000L,
                        "gw.sign_send NEW", 3_000L,
                        "venue.answer NEW", 4_000L,
                        "venue.answer CANCEL", 5_000L),
                maxes,
                "a hold isn't a send, and an unanswered request has no answer time");
    }

    /** Moves past the minute's end; the next thing the lane tells ends it. */
    private void endMinute() {
        this.now[0] += LaneAdmissions.MINUTE_NANOS;
        this.admissions.connectionsBusy();
    }
}
