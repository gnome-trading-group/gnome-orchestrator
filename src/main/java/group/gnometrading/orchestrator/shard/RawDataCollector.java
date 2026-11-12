package group.gnometrading.orchestrator.shard;

import group.gnometrading.data.StreamEntry;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.agrona.concurrent.UnsafeBuffer;

/** Captures the raw frames one connection receives, each with its receive timestamp, into minute files. */
public final class RawDataCollector {

    private static final int HEADER_SIZE = 12; // 8B recvTimestamp + 4B payloadLength

    private final Logger logger;
    private final CycleManager cycleManager;
    private final UnsafeBuffer header = new UnsafeBuffer(new byte[HEADER_SIZE]);

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

    public void capture(long recvTimestamp, ByteBuffer buffer) {
        if (cycleManager.isClosed() || !buffer.hasRemaining()) {
            return;
        }

        if (cycleManager.isPastCurrentCycle(recvTimestamp)) {
            cycleManager.rotate(cycleManager.cycleStart(recvTimestamp));
            cycleManager.maybeExpireGracePeriod(recvTimestamp);
        }

        final int payloadLength = buffer.remaining();
        header.putLong(0, recvTimestamp, ByteOrder.LITTLE_ENDIAN);
        header.putInt(Long.BYTES, payloadLength, ByteOrder.LITTLE_ENDIAN);

        try {
            final CycleBuffer cycle = cycleManager.currentCycle();
            cycle.write(header, 0, HEADER_SIZE);
            // Absolute: the gateway parses the message from this same position next.
            cycle.write(buffer, buffer.position(), payloadLength);
        } catch (IOException e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Error writing raw capture: %s", e.getMessage());
        }
    }

    /** Hands the connection's open minutes to the uploader; once the thread feeding it has stopped. */
    public void flush() {
        cycleManager.flush();
    }
}
