package group.gnometrading.trading;

import com.github.luben.zstd.ZstdOutputStream;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.sequencer.JournalWriter;
import group.gnometrading.utils.Schedule;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.agrona.concurrent.EpochClock;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Periodically flushes the session journal to disk and uploads it to S3 on shutdown.
 *
 * <p>Runs on its own thread. The {@link #doWork()} loop drives a {@link Schedule} that
 * calls {@link JournalWriter#flush()} at the configured interval, protecting against data
 * loss on hard crash (SIGKILL, OOM) between full page-cache flushes.
 *
 * <p>On {@link #onClose()}, the writer is finalized, the written portion of the journal
 * file is zstd-compressed, and the result is uploaded to S3 at
 * {@code s3://bucket/{strategyId}/{sessionId}/journal.zst}. Upload is skipped when no
 * {@link S3Client} is provided (local runs).
 */
public final class JournalManagerAgent implements GnomeAgent {

    private final JournalWriter writer;
    private final Path journalPath;
    private final S3Client s3Client;
    private final String bucket;
    private final String s3Key;
    private final Schedule flushSchedule;
    private final Logger logger;

    public JournalManagerAgent(
            JournalWriter writer,
            Path journalPath,
            S3Client s3Client,
            String bucket,
            String s3Key,
            EpochClock clock,
            Duration flushInterval,
            Logger logger) {
        this.writer = writer;
        this.journalPath = journalPath;
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.s3Key = s3Key;
        this.flushSchedule = new Schedule(clock, flushInterval.toMillis(), writer::flush);
        this.logger = logger;
    }

    @Override
    public String roleName() {
        return "journal-manager";
    }

    @Override
    public void onStart() {
        flushSchedule.start();
    }

    @Override
    public int doWork() {
        flushSchedule.check();
        return 0;
    }

    @Override
    public void onClose() {
        writer.close();
        if (s3Client == null) {
            logger.logf(LogMessage.DEBUG, "Journal saved locally at %s (no S3 client)", journalPath);
            return;
        }
        try {
            byte[] compressed = compress();
            s3Client.putObject(
                    request -> request.bucket(bucket).key(s3Key),
                    RequestBody.fromBytes(compressed));
            logger.logf(LogMessage.DEBUG, "Journal uploaded to s3://%s/%s (%d bytes)", bucket, s3Key, compressed.length);
        } catch (Exception e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Failed to upload journal to S3: %s", e.getMessage());
        }
    }

    private byte[] compress() throws IOException {
        int writtenBytes = writer.writtenBytes();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(writtenBytes / 4 + 1024);
        try (InputStream in = Files.newInputStream(journalPath);
             ZstdOutputStream zstd = new ZstdOutputStream(bos)) {
            byte[] buf = new byte[64 * 1024];
            int remaining = writtenBytes;
            while (remaining > 0) {
                int toRead = Math.min(buf.length, remaining);
                int read = in.read(buf, 0, toRead);
                if (read < 0) {
                    break;
                }
                zstd.write(buf, 0, read);
                remaining -= read;
            }
        }
        return bos.toByteArray();
    }
}
