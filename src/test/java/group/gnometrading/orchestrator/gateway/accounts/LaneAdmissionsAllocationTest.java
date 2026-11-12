package group.gnometrading.orchestrator.gateway.accounts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.gateway.journal.GatewayJournal;
import group.gnometrading.schemas.gateway.AdmissionAction;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A lane tells its admissions of every request on its own thread, so journaling and counting them may not allocate. */
@Tag("allocation")
class LaneAdmissionsAllocationTest {

    private static final int WARM_UP = 200_000;
    private static final int MEASURED = 200_000;

    @TempDir
    Path directory;

    @Test
    void tellingDecisionsAndAnswersAllocatesNothingAcrossMinutes() throws Exception {
        final GatewayJournal journal =
                new GatewayJournal(new NullLogger(), this.directory, 64L << 20, Long.MAX_VALUE, System::nanoTime, 1);
        final long[] now = {0};
        final LaneAdmissions admissions = new LaneAdmissions(5, journal.lane(0), () -> now[0]);
        decide(admissions, now, WARM_UP);

        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long before = threads.getCurrentThreadAllocatedBytes();
        decide(admissions, now, MEASURED);
        final long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        journal.close();

        assertTrue(allocated < 1_024, "allocated " + allocated + " bytes");
    }

    /** Every kind of decision from as many sessions as an account takes, answers and busy connections too, with a minute ending every 10,000. */
    private static void decide(final LaneAdmissions admissions, final long[] now, final int count) {
        final AdmissionAction[] actions = AdmissionAction.values();
        for (int i = 0; i < count; i++) {
            now[0] += LaneAdmissions.MINUTE_NANOS / 10_000;
            final AdmissionAction action = actions[i % (actions.length - 1)];
            final RequestClass type = i % 3 == 0 ? RequestClass.CANCEL : RequestClass.NEW;
            admissions.decided(i % 1_024, i, type, action, i, i % 50);
            admissions.answered(type, 1_000L * i, i % 11 != 0);
            if (i % 13 == 0) {
                admissions.connectionsBusy();
            }
        }
    }
}
