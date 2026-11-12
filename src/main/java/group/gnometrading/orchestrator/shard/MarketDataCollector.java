package group.gnometrading.orchestrator.shard;

import com.lmax.disruptor.EventHandler;
import group.gnometrading.annotations.VisibleForTesting;
import group.gnometrading.data.StreamEntry;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.RecordType;
import group.gnometrading.schemas.StreamRecord;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Writes one stream's records into minute files, rotated by the records' event time, at the stream's final key: one
 * file per minute, the first writer's. {@link #writeFrom} and {@link #writeUntil} bound which minutes this collector
 * writes, so a stream handed between shards is cut at a minute boundary.
 */
public final class MarketDataCollector implements EventHandler<StreamRecord> {

    private static final Duration GRACE_PERIOD = Duration.ofSeconds(5);

    private final Logger logger;
    private final Clock clock;
    private final CycleManager cycleManager;

    public volatile long lastEventNanos;

    public MarketDataCollector(
            Logger logger,
            Clock clock,
            CycleUploader uploader,
            DirectBufferPool pool,
            int streamId,
            RecordType recordType,
            String bucketName) {
        this(logger, clock, uploader, pool, streamId, recordType, bucketName, GRACE_PERIOD);
    }

    @VisibleForTesting
    MarketDataCollector(
            Logger logger,
            Clock clock,
            CycleUploader uploader,
            DirectBufferPool pool,
            int streamId,
            RecordType recordType,
            String bucketName,
            Duration gracePeriod) {
        this.logger = logger;
        this.clock = clock;
        this.lastEventNanos = 0;
        this.cycleManager = new CycleManager(
                logger,
                uploader,
                pool,
                bucketName,
                ts -> new StreamEntry(streamId, recordType, ts).getKey(),
                clock.getZone(),
                StreamEntry.CYCLE_CHRONO_UNIT.getDuration(),
                CycleManager.epochNanos(clock.instant()),
                gracePeriod);
    }

    @Override
    public void onEvent(final StreamRecord schema, long sequence, boolean endOfBatch) {
        this.lastEventNanos = TimeUnit.MILLISECONDS.toNanos(this.clock.millis());
        if (cycleManager.isClosed()) {
            return;
        }

        final long eventNanos = schema.getEventTimestamp();
        final long eventCycleStart = cycleManager.cycleStart(eventNanos);

        if (cycleManager.isPastCurrentCycle(eventNanos)) {
            cycleManager.rotate(eventCycleStart);
        }

        cycleManager.maybeExpireGracePeriod(eventNanos);

        try {
            cycleManager.resolveTarget(eventCycleStart).write(schema.buffer, 0, schema.totalMessageSize());
        } catch (IOException e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Error trying to write to file stream: %s", e.getMessage());
            throw new IllegalStateException(e);
        }
    }

    /** Writes only minutes starting at or after {@code epochNanos}; {@link Long#MAX_VALUE} writes none. Any thread. */
    public void writeFrom(long epochNanos) {
        cycleManager.writeFrom(epochNanos);
    }

    /** Writes only minutes starting before {@code epochNanos}. Any thread. */
    public void writeUntil(long epochNanos) {
        cycleManager.writeUntil(epochNanos);
    }

    /**
     * Hands over the minutes that have ended by the wall clock ({@code epochNanos}), so a quiet market's are written when
     * they end. On the thread feeding it, between events; allocates nothing.
     */
    public void expire(long epochNanos) {
        cycleManager.expire(epochNanos);
    }

    /** Hands this stream's open minutes to the uploader; once the thread feeding it has stopped. */
    public void flush() {
        cycleManager.flush();
    }
}
