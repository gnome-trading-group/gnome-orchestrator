package group.gnometrading.orchestrator.shard;

import group.gnometrading.data.StreamEntry;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.agrona.DirectBuffer;

/**
 * Writes the raw frames one connection receives, each with its receive timestamp, into minute files: a 12-byte header
 * (receive nanos, payload length; little-endian) then the payload. Fed by {@link RawCaptureAgent} from the connection's
 * {@link RawCaptureQueue}, already in that layout, on the agent's thread only.
 */
public final class RawDataCollector {

    static final int HEADER_SIZE = 12; // 8B recvTimestamp + 4B payloadLength

    private final Logger logger;
    private final CycleManager cycleManager;

    public RawDataCollector(
            Logger logger,
            Clock clock,
            CycleUploader uploader,
            DirectBufferPool pool,
            String source,
            String shard,
            String connection,
            String bucketName) {
        this.logger = logger;
        this.cycleManager = new CycleManager(
                logger,
                uploader,
                pool,
                bucketName,
                ts -> StreamEntry.rawCaptureKey(
                        source, shard, connection, ts, UUID.randomUUID().toString()),
                clock.getZone(),
                Duration.ofMinutes(1),
                CycleManager.epochNanos(clock.instant()),
                Duration.ZERO);
    }

    /** Starts a frame received at {@code recvTimestamp}, rolling the minute if it is past; false once flushed. */
    boolean startFrame(long recvTimestamp) {
        if (cycleManager.isClosed()) {
            return false;
        }
        if (cycleManager.isPastCurrentCycle(recvTimestamp)) {
            cycleManager.rotate(cycleManager.cycleStart(recvTimestamp));
            cycleManager.maybeExpireGracePeriod(recvTimestamp);
        }
        return true;
    }

    /** Appends bytes of the frame last started: its header and payload, in as many pieces as they came. */
    void append(DirectBuffer source, int offset, int length) {
        try {
            cycleManager.currentCycle().write(source, offset, length);
        } catch (IOException e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Error writing raw capture: %s", e.getMessage());
        }
    }

    /** Hands the connection's open minutes to the uploader; once nothing feeds it any more. */
    public void flush() {
        cycleManager.flush();
    }
}
