package group.gnometrading.trading;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.sequencer.JournalSegment;
import java.io.File;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.concurrent.EpochClock;

/**
 * Seals each finished journal segment on a thread of its own, since unmapping one can hold up the journal for a
 * millisecond or more. The journal shipper, a separate process on the session's instance, uploads sealed segments to
 * S3: compressing and uploading make garbage and wait on the network, neither of which belongs in the trading process.
 *
 * <p>It also keeps the last {@value #TRACKED} segments it handled, so the session's heartbeat can report how many
 * the shipper hasn't uploaded yet: the shipper deletes a segment once it is in S3, so one whose file is still on disk
 * is waiting.
 */
final class JournalSegmentSealer {

    static final int TRACKED = 64;

    private final long segmentBytes;
    private final EpochClock clock;
    private final Logger logger;
    private final ExecutorService executor;

    // Written by the sealing thread, read by the heartbeat's: an entry is published by the release of the count after
    // it. Once more than TRACKED have been sealed a reader may see a slot just reused, which only shifts a reading
    // by a segment.
    private final File[] files = new File[TRACKED];
    private final long[] finishedAtMs = new long[TRACKED];
    private final AtomicLong sealed = new AtomicLong();

    // The heartbeat's thread only.
    private int pendingSegments;
    private long oldestPendingMs;

    JournalSegmentSealer(long segmentBytes, EpochClock clock, Logger logger) {
        this.segmentBytes = segmentBytes;
        this.clock = clock;
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "journal-sealer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Queues a finished segment. Called on the journal's thread, so it only hands the segment off. */
    void submit(JournalSegment segment) {
        long finishedAt = clock.time();
        executor.execute(() -> seal(segment, finishedAt));
    }

    private void seal(JournalSegment segment, long finishedAt) {
        try {
            segment.seal();
        } catch (RuntimeException e) {
            // The shipper still ships an unsealed segment once the session has ended.
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Failed to seal journal segment %d at %s: %s",
                    segment.index(),
                    segment.path(),
                    e.getMessage());
        }
        // An unsealed segment is tracked too: it stays on disk until the session ends, so it counts as waiting.
        long count = sealed.get();
        int slot = (int) (count % TRACKED);
        files[slot] = segment.path().toFile();
        finishedAtMs[slot] = finishedAt;
        sealed.setRelease(count + 1);
    }

    /**
     * Counts the tracked segments still on disk, for {@link #pendingSegments()} and {@link #oldestPendingMs()}.
     * Allocates nothing; call from one thread.
     */
    void measureBacklog(long nowMs) {
        long count = sealed.getAcquire();
        int pending = 0;
        long oldest = 0;
        for (long i = Math.max(0, count - TRACKED); i < count; i++) {
            int slot = (int) (i % TRACKED);
            if (files[slot].exists()) {
                pending++;
                oldest = Math.max(oldest, nowMs - finishedAtMs[slot]);
            }
        }
        pendingSegments = pending;
        oldestPendingMs = oldest;
    }

    /** Segments sealed but not yet uploaded, as of the last {@link #measureBacklog}; at most {@value #TRACKED}. */
    int pendingSegments() {
        return pendingSegments;
    }

    /**
     * How long the oldest of them has waited since the journal finished it, as of the last {@link #measureBacklog}; 0
     * with none waiting.
     */
    long oldestPendingMs() {
        return oldestPendingMs;
    }

    long segmentBytes() {
        return segmentBytes;
    }

    /**
     * Seals every segment already submitted, waiting at most {@code timeout}.
     *
     * @return whether everything finished in time
     */
    boolean close(Duration timeout) throws InterruptedException {
        executor.shutdown();
        boolean finished = executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Journal segments still sealing after %d s; the shipper sends them unsealed",
                    timeout.toSeconds());
        }
        return finished;
    }
}
