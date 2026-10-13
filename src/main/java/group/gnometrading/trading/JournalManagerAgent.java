package group.gnometrading.trading;

import com.github.luben.zstd.ZstdOutputStream;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.sequencer.JournalWriter;
import group.gnometrading.sequencer.SequencedPoller;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Writes every buffer on the session sequence to the journal, periodically flushes it to disk, and uploads it to S3 on
 * shutdown.
 *
 * <p>Runs on its own thread and is the journal's only writer: {@link #doWork()} polls every journaled buffer into
 * the {@link JournalWriter}. The journal is not latency-sensitive, so it sleeps when idle; its pollers only hold up
 * a producer once that producer has lapped the ring.
 *
 * <p>{@link JournalWriter#flush()} runs at the configured interval on a separate thread, protecting against data
 * loss on hard crash (SIGKILL, OOM) between full page-cache flushes. A flush can take tens of milliseconds, and on
 * this thread it would stop the draining long enough for a busy market data buffer to fill and block the socket
 * reader publishing into it. Forcing a mapped file is safe while it is being written.
 *
 * <p>On {@link #onClose()}, the writer is finalized, the written portion of the journal
 * file is zstd-compressed, and the result is uploaded to S3 at
 * {@code s3://bucket/{strategyId}/{sessionId}/journal.zst}. Upload is skipped when no
 * {@link S3Client} is provided (local runs).
 */
public final class JournalManagerAgent implements GnomeAgent {

    private final JournalWriter writer;
    private final SequencedPoller[] pollers;
    private final Path journalPath;
    private final S3Client s3Client;
    private final String bucket;
    private final String s3Key;
    private final Duration flushInterval;
    private final Logger logger;
    private ScheduledExecutorService flusher;

    public JournalManagerAgent(
            JournalWriter writer,
            List<SequencedPoller> pollers,
            Path journalPath,
            S3Client s3Client,
            String bucket,
            String s3Key,
            Duration flushInterval,
            Logger logger) {
        this.writer = writer;
        this.pollers = pollers.toArray(new SequencedPoller[0]);
        this.journalPath = journalPath;
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.s3Key = s3Key;
        this.flushInterval = flushInterval;
        this.logger = logger;
    }

    @Override
    public String roleName() {
        return "journal-manager";
    }

    @Override
    public void onStart() {
        flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "journal-flusher");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = flushInterval.toMillis();
        flusher.scheduleAtFixedRate(writer::flush, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public int doWork() throws Exception {
        return drain();
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
        if (flusher != null) {
            flusher.shutdown();
        }
        writer.close();
        if (s3Client == null) {
            logger.logf(LogMessage.DEBUG, "Journal saved locally at %s (no S3 client)", journalPath);
            return;
        }
        try {
            byte[] compressed = compress();
            s3Client.putObject(request -> request.bucket(bucket).key(s3Key), RequestBody.fromBytes(compressed));
            logger.logf(
                    LogMessage.DEBUG, "Journal uploaded to s3://%s/%s (%d bytes)", bucket, s3Key, compressed.length);
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
