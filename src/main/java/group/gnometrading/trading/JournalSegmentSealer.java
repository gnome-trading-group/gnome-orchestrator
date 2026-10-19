package group.gnometrading.trading;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.sequencer.JournalSegment;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Seals each finished journal segment on a thread of its own, since unmapping one can hold up the journal for a
 * millisecond or more. The journal shipper, a separate process on the session's instance, uploads sealed segments to
 * S3: compressing and uploading make garbage and wait on the network, neither of which belongs in the trading process.
 */
final class JournalSegmentSealer {

    private final Logger logger;
    private final ExecutorService executor;

    JournalSegmentSealer(Logger logger) {
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "journal-sealer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Queues a finished segment. Called on the journal's thread, so it only hands the segment off. */
    void submit(JournalSegment segment) {
        executor.execute(() -> seal(segment));
    }

    private void seal(JournalSegment segment) {
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
