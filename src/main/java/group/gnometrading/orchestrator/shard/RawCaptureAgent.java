package group.gnometrading.orchestrator.shard;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.metrics.MetricCounters;
import group.gnometrading.utils.CauseChain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Writes every connection's raw frames into their minute files, on one background thread, so no feed reader pays for
 * compression or a minute's roll. Raw capture has no latency target: this only has to keep each {@link RawCaptureQueue}
 * from filling.
 *
 * <p>A connection whose writing fails is given up (logged; its reader drops from then on) without touching the others.
 * Once this stops, readers drop and each queue's flush writes what it holds on the flushing thread.
 */
public final class RawCaptureAgent implements GnomeAgent {

    static final long REPORT_EVERY_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int READ_LIMIT = 256;

    private final Logger logger;
    private final LongSupplier nanoTime;
    private final int ringBytes;
    private final long maxWaitNanos;
    private final ConcurrentLinkedQueue<RawCaptureQueue> joining = new ConcurrentLinkedQueue<>();
    private final List<RawCaptureQueue> queues = new ArrayList<>();
    private volatile boolean stopped;

    private final MetricCounters counters = new MetricCounters(7);
    private final int passes = counters.counter("passes");
    private final int passGapPeakNanos = counters.peak("passGapPeakNanos");
    private final int connections = counters.gauge("connections");
    private final int frames = counters.counter("frames");
    private final int fillPeakPermille = counters.peak("fillPeakPermille");
    private final int waits = counters.counter("waits");
    private final int drops = counters.counter("drops");
    private long lastPassNanos;
    private long nextReportNanos;
    private long goneFrames;
    private long goneWaits;
    private long goneDrops;

    /**
     * @param ringBytes each connection's queue, at least; a venue whose frames may be larger gets twice its largest
     * @param maxWaitNanos how long a reader waits for room before dropping a frame
     */
    public RawCaptureAgent(
            final Logger logger, final LongSupplier nanoTime, final int ringBytes, final long maxWaitNanos) {
        this.logger = logger;
        this.nanoTime = nanoTime;
        this.ringBytes = ringBytes;
        this.maxWaitNanos = maxWaitNanos;
    }

    /** From any thread: a queue for a new connection's frames, written into {@code collector} from the next pass. */
    public RawCaptureQueue open(final String connection, final RawDataCollector collector, final int maxFrameBytes) {
        final RawCaptureQueue queue =
                new RawCaptureQueue(connection, collector, this, Math.max(ringBytes, 2 * maxFrameBytes), maxWaitNanos);
        joining.add(queue);
        return queue;
    }

    boolean stopped() {
        return stopped;
    }

    @Override
    public int doWork() {
        RawCaptureQueue joined;
        while ((joined = joining.poll()) != null) {
            queues.add(joined);
        }
        final long now = nanoTime.getAsLong();
        counters.increment(passes);
        if (lastPassNanos != 0) {
            counters.max(passGapPeakNanos, now - lastPassNanos);
        }
        lastPassNanos = now;

        int work = 0;
        for (int i = queues.size() - 1; i >= 0; i--) {
            final RawCaptureQueue queue = queues.get(i);
            try {
                work += queue.drain(READ_LIMIT);
                counters.max(fillPeakPermille, queue.fillPermille());
                if (queue.drained()) {
                    queue.finish();
                    remove(i);
                }
            } catch (Throwable error) {
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Raw capture failed on %s; capturing nothing more from it: %s",
                        queue.connection(),
                        CauseChain.of(error));
                remove(i);
                abandon(queue);
            }
        }
        if (now >= nextReportNanos) {
            nextReportNanos = now + REPORT_EVERY_NANOS;
            report();
        }
        return work;
    }

    private void abandon(final RawCaptureQueue queue) {
        try {
            queue.abandon();
        } catch (Throwable error) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Failed to flush the raw capture of %s: %s",
                    queue.connection(),
                    CauseChain.of(error));
        }
    }

    private void remove(final int index) {
        final RawCaptureQueue queue = queues.remove(index);
        logDrops(queue);
        goneFrames += queue.frames();
        goneWaits += queue.waits();
        goneDrops += queue.drops();
    }

    private void report() {
        long frameTotal = goneFrames;
        long waitTotal = goneWaits;
        long dropTotal = goneDrops;
        for (int i = 0; i < queues.size(); i++) {
            final RawCaptureQueue queue = queues.get(i);
            frameTotal += queue.frames();
            waitTotal += queue.waits();
            dropTotal += queue.drops();
            logDrops(queue);
        }
        counters.set(connections, queues.size());
        counters.set(frames, frameTotal);
        counters.set(waits, waitTotal);
        counters.set(drops, dropTotal);
    }

    private void logDrops(final RawCaptureQueue queue) {
        final long fresh = queue.newDrops();
        if (fresh > 0) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Raw capture dropped %d frames from %s: its queue was full or capture had stopped",
                    fresh,
                    queue.connection());
        }
    }

    /** From a queue's flushing thread: this agent didn't get through it in time. */
    void flushTimedOut(final RawCaptureQueue queue) {
        logger.logf(
                LogMessage.UNKNOWN_ERROR,
                "Raw capture of %s not flushed in time; its last frames may be lost",
                queue.connection());
    }

    /** Its passes, how full the fullest queue got, and every reader's waits and drops: written on its thread only. */
    MetricCounters counters() {
        return counters;
    }

    @Override
    public void onClose() {
        stopped = true;
    }

    @Override
    public String roleName() {
        return "raw-capture";
    }
}
