package group.gnometrading.orchestrator.gateway.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.gateways.account.lane.RequestTiming;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.gateway.core.OrderTimings;
import group.gnometrading.orchestrator.gateway.core.TimingMessage;
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
    private static final long EPOCH_OFFSET = 1_700_000_000_000_000_000L;
    private static final MessageConsumer<TimingMessage> TAKE = message -> {};

    @TempDir
    Path directory;

    @Test
    void tellingDecisionsAndAnswersAllocatesNothingAcrossMinutes() throws Exception {
        final GatewayJournal journal =
                new GatewayJournal(new NullLogger(), this.directory, 64L << 20, Long.MAX_VALUE, System::nanoTime, 1);
        final long[] now = {0};
        final ManyToOneRingBuffer<TimingMessage> timings =
                new ManyToOneRingBuffer<>(TimingMessage[]::new, TimingMessage::new, 1_024);
        final LaneAdmissions admissions = new LaneAdmissions(
                5, journal.lane(0), () -> now[0], () -> now[0] + EPOCH_OFFSET, new OrderTimings(timings));
        decide(admissions, timings, now, WARM_UP);

        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long before = threads.getCurrentThreadAllocatedBytes();
        decide(admissions, timings, now, MEASURED);
        final long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        journal.close();

        assertTrue(allocated < 1_024, "allocated " + allocated + " bytes");
        assertEquals(0, admissions.timings().dropped(), "every timing was taken off the ring");
    }

    /**
     * Every kind of decision from as many sessions as an account takes, answers, busy connections and each request's
     * timing too, with a minute ending every 10,000.
     */
    private static void decide(
            final LaneAdmissions admissions,
            final ManyToOneRingBuffer<TimingMessage> timings,
            final long[] now,
            final int count) {
        final AdmissionAction[] actions = AdmissionAction.values();
        final RequestTiming timing = new RequestTiming();
        for (int i = 0; i < count; i++) {
            now[0] += LaneAdmissions.MINUTE_NANOS / 10_000;
            final AdmissionAction action = actions[i % (actions.length - 1)];
            final RequestClass type = i % 3 == 0 ? RequestClass.CANCEL : RequestClass.NEW;
            admissions.decided(i % 1_024, i, type, action, i, i % 50);
            admissions.answered(type, 1_000L * i, i % 11 != 0);
            if (i % 13 == 0) {
                admissions.connectionsBusy();
            }
            timing.reset();
            timing.sessionSlot = i % 1_024;
            timing.clientOidCounter = i;
            timing.requestClass = type;
            timing.ingressNanos = now[0] - 3_000;
            timing.wireSentNanos = now[0] - 2_000;
            timing.answeredNanos = now[0];
            admissions.timed(timing);
            if (i % 512 == 0) {
                timings.read(TAKE, 1_024);
            }
        }
    }
}
