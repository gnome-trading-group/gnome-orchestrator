package group.gnometrading.trading;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.sequencer.JournalWriter;
import group.gnometrading.sequencer.SequencedPoller;
import java.time.Duration;
import java.util.List;
import org.agrona.concurrent.EpochClock;

/**
 * Writes every buffer on the session sequence to the journal, and ends a segment every interval so the journal
 * shipper uploads the journal while the session runs.
 *
 * <p>Runs on its own thread and is the journal's only writer: {@link #doWork()} polls every journaled buffer into
 * the {@link JournalWriter}. The journal is not latency-sensitive, so it sleeps when idle; its pollers only hold up
 * a producer once that producer has lapped the ring. Finished segments are sealed on the {@link JournalSegmentSealer}'s
 * thread, never this one, so the draining doesn't stop long enough for a busy market data buffer to fill and block the
 * socket reader publishing into it.
 *
 * <p>Nothing is forced to disk: the journal is memory-mapped, so its pages outlive a crash of this process in the
 * page cache, and the shipper sends them once the session has ended.
 *
 * <p>On {@link #onClose()}, everything left is journaled and the last segment is sealed.
 */
public final class JournalManagerAgent implements GnomeAgent {

    private final JournalWriter writer;
    private final SequencedPoller[] pollers;
    private final JournalSegmentSealer sealer;
    private final long segmentIntervalMillis;
    private final Duration sealTimeout;
    private final EpochClock clock;
    private final Logger logger;
    private long nextRollMillis;

    /**
     * @param writer whose segment listener is {@code sealer}'s {@link JournalSegmentSealer#submit}
     * @param segmentInterval how long a segment stays open before it is sealed for shipping
     * @param sealTimeout how long closing waits for the last segments to be sealed
     */
    JournalManagerAgent(
            JournalWriter writer,
            List<SequencedPoller> pollers,
            JournalSegmentSealer sealer,
            Duration segmentInterval,
            Duration sealTimeout,
            EpochClock clock,
            Logger logger) {
        this.writer = writer;
        this.pollers = pollers.toArray(new SequencedPoller[0]);
        this.sealer = sealer;
        this.segmentIntervalMillis = segmentInterval.toMillis();
        this.sealTimeout = sealTimeout;
        this.clock = clock;
        this.logger = logger;
    }

    @Override
    public String roleName() {
        return "journal-manager";
    }

    @Override
    public void onStart() {
        nextRollMillis = clock.time() + segmentIntervalMillis;
    }

    @Override
    public int doWork() throws Exception {
        int work = drain();
        long now = clock.time();
        if (now >= nextRollMillis) {
            writer.roll();
            nextRollMillis = now + segmentIntervalMillis;
        }
        return work;
    }

    private int drain() throws Exception {
        int work = 0;
        for (SequencedPoller poller : pollers) {
            work += poller.poll();
        }
        return work;
    }

    @Override
    public void onClose() {
        // Closed after every other agent, so this picks up whatever they published while closing.
        try {
            int drained;
            do {
                drained = drain();
            } while (drained > 0);
        } catch (Exception e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Failed to journal the final events: %s", e.getMessage());
        }
        writer.close();
        try {
            sealer.close(sealTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.logf(LogMessage.UNKNOWN_ERROR, "Interrupted waiting for journal segments to seal");
        }
    }
}
