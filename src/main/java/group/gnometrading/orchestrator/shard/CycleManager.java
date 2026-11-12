package group.gnometrading.orchestrator.shard;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Splits a stream of events into fixed-length cycles, one file per cycle, and hands finished cycles to the uploader.
 *
 * <p>Runs on the thread receiving data, so the per-event methods take epoch nanoseconds and never allocate. A
 * {@link LocalDateTime} is built only on rotation, once per cycle, for the file's key.
 *
 * <p>Cycles end by event time, when a record of a later cycle arrives, and by the wall clock ({@link #expire}), so a
 * quiet market's last minute is written when it ends rather than whenever its next record comes, or lost with the
 * process. A cycle with no records is released, never written. A record arriving for a cycle already handed over goes
 * into a new cycle after it: that minute's file is written once, and a second copy would only be refused.
 *
 * <p>{@link #flush} hands over the open cycles, the current one partial, when its stream stops: the process shutting
 * down or the stream being quarantined. It must run once the thread receiving data has stopped.
 *
 * <p>Only cycles starting within the write window {@code [writeFrom, writeUntil)} are uploaded; the rest are released
 * unwritten. A hand-off between shards cuts at a minute boundary this way, so each minute is written once, by one
 * shard, complete. The window is by cycle, not by a record's event time: a record stamped older than the previous
 * cycle lands in the current one, and must go wherever that cycle goes.
 */
final class CycleManager {

    @FunctionalInterface
    interface KeyFactory {
        String createKey(LocalDateTime timestamp);
    }

    private final Logger logger;
    private final CycleUploader uploader;
    private final DirectBufferPool pool;
    private final String bucketName;
    private final KeyFactory keyFactory;
    private final ZoneId zone;
    private final long cycleNanos;
    private final long gracePeriodNanos;

    // Null once the wall clock has handed the current cycle over, until a record opens the next.
    private CycleBuffer currentCycle;
    private long currentStartNanos;
    // The earliest a new cycle may start: after the last one handed over by the wall clock.
    private long nextStartNanos = Long.MIN_VALUE;
    private CycleBuffer previousCycle;
    private long previousStartNanos;

    private volatile boolean closed;
    private volatile long writeFromNanos = Long.MIN_VALUE;
    private volatile long writeUntilNanos = Long.MAX_VALUE;

    CycleManager(
            Logger logger,
            CycleUploader uploader,
            DirectBufferPool pool,
            String bucketName,
            KeyFactory keyFactory,
            ZoneId zone,
            Duration cycleLength,
            long initialNanos,
            Duration gracePeriod) {
        this.logger = logger;
        this.uploader = uploader;
        this.pool = pool;
        this.bucketName = bucketName;
        this.keyFactory = keyFactory;
        this.zone = zone;
        this.cycleNanos = cycleLength.toNanos();
        this.gracePeriodNanos = gracePeriod.toNanos();
        this.currentStartNanos = cycleStart(initialNanos);
        this.currentCycle = newCycle(this.currentStartNanos);
    }

    private CycleBuffer newCycle(long startNanos) {
        LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochSecond(0, startNanos), zone);
        return new CycleBuffer(pool, start, keyFactory.createKey(start));
    }

    static long epochNanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    /** The start of the cycle containing {@code eventNanos}. */
    long cycleStart(long eventNanos) {
        return eventNanos - Math.floorMod(eventNanos, cycleNanos);
    }

    /** Whether {@code eventNanos} falls in a cycle after the current one, or there is no current one. */
    boolean isPastCurrentCycle(long eventNanos) {
        return currentCycle == null || eventNanos - currentStartNanos >= cycleNanos;
    }

    /** Uploads only cycles starting at or after {@code epochNanos}; {@link Long#MAX_VALUE} uploads none. */
    void writeFrom(long epochNanos) {
        writeFromNanos = epochNanos;
    }

    /** Uploads only cycles starting before {@code epochNanos}. */
    void writeUntil(long epochNanos) {
        writeUntilNanos = epochNanos;
    }

    /** Uploads the cycle if it has records and falls in the write window, else releases it; true if uploaded. */
    private boolean handOver(CycleBuffer cycle, long startNanos) {
        if (!cycle.isEmpty() && startNanos >= writeFromNanos && startNanos < writeUntilNanos) {
            uploader.upload(cycle, bucketName);
            return true;
        }
        cycle.release();
        return false;
    }

    void rotate(long newCycleStartNanos) {
        if (previousCycle != null) {
            handOver(previousCycle, previousStartNanos);
            previousCycle = null;
        }

        final long startNanos = Math.max(newCycleStartNanos, nextStartNanos);
        CycleBuffer next = newCycle(startNanos);
        logger.logf(LogMessage.DEBUG, "Switching cycle to %s", next.timestamp());

        previousCycle = currentCycle;
        previousStartNanos = currentStartNanos;
        currentCycle = next;
        currentStartNanos = startNanos;
    }

    void maybeExpireGracePeriod(long eventNanos) {
        if (previousCycle == null) {
            return;
        }
        if (eventNanos - currentStartNanos >= gracePeriodNanos) {
            handOver(previousCycle, previousStartNanos);
            previousCycle = null;
        }
    }

    /**
     * Hands over, by the wall clock, the previous cycle once the current one is past its grace, and the current one
     * once it has ended and its grace passed. On the thread receiving data, between events; allocates nothing.
     */
    void expire(long wallNanos) {
        if (closed || currentCycle == null) {
            return;
        }
        maybeExpireGracePeriod(wallNanos);
        if (wallNanos - currentStartNanos >= cycleNanos + gracePeriodNanos) {
            handOver(currentCycle, currentStartNanos);
            nextStartNanos = currentStartNanos + cycleNanos;
            currentCycle = null;
        }
    }

    /** The cycle an event belongs in: the previous one while its grace period lasts, else the current one. */
    CycleBuffer resolveTarget(long eventCycleStartNanos) {
        if (previousCycle == null) {
            return currentCycle;
        }
        if (eventCycleStartNanos == previousStartNanos) {
            return previousCycle;
        }
        if (eventCycleStartNanos < previousStartNanos) {
            logger.logf(
                    LogMessage.DEBUG,
                    "Event in cycle starting %d ns predates previous cycle %s, writing to current cycle",
                    eventCycleStartNanos,
                    previousCycle.timestamp());
        }
        return currentCycle;
    }

    CycleBuffer currentCycle() {
        return currentCycle;
    }

    long currentStartNanos() {
        return currentStartNanos;
    }

    boolean isClosed() {
        return closed;
    }

    /**
     * Hands the previous cycle and the partial current one to the uploader, within the write window, and stops taking
     * events. A partial file keeps its cycle's key; if another writer already wrote that minute, the first one stands.
     */
    void flush() {
        if (closed) {
            return;
        }
        closed = true;
        if (previousCycle != null) {
            handOver(previousCycle, previousStartNanos);
            previousCycle = null;
        }
        if (currentCycle != null) {
            final boolean empty = currentCycle.isEmpty();
            final LocalDateTime timestamp = currentCycle.timestamp();
            if (handOver(currentCycle, currentStartNanos)) {
                logger.logf(LogMessage.DEBUG, "Flushing partial cycle %s: uploading what it holds", timestamp);
            } else {
                logger.logf(
                        LogMessage.DEBUG,
                        "Flushing partial cycle %s: dropped, %s",
                        timestamp,
                        empty ? "it holds no records" : "outside this collector's write window");
            }
            currentCycle = null;
        }
    }
}
