package group.gnometrading.orchestrator.gateway.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.schemas.gateway.AdmissionAction;
import java.util.Map;
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

    /** Moves past the minute's end; the next thing the lane tells ends it. */
    private void endMinute() {
        this.now[0] += LaneAdmissions.MINUTE_NANOS;
        this.admissions.connectionsBusy();
    }
}
