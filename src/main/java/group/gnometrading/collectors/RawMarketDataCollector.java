package group.gnometrading.collectors;

import com.github.luben.zstd.ZstdOutputStream;
import group.gnometrading.collections.buffer.OneToOneRingBuffer;
import group.gnometrading.gateways.inbound.RawMessageHandler;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.sm.Listing;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Archives lossless inbound venue messages in minute-sized, replayable objects. */
public final class RawMarketDataCollector implements RawMessageHandler, Closeable {

    static final byte[] MAGIC = "GNOMERAW".getBytes(StandardCharsets.US_ASCII);
    static final int FORMAT_VERSION = 1;
    static final int DEFAULT_FRAME_CAPACITY = 1 << 8;
    static final int DEFAULT_MAX_FRAME_BYTES = 1 << 16;

    private static final int DRAIN_LIMIT = 64;
    private static final long IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long NANOS_PER_MINUTE = TimeUnit.MINUTES.toNanos(1);
    private static final DateTimeFormatter KEY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu/MM/dd/HH/mm").withZone(ZoneOffset.UTC);

    private static final class RawFrame {
        private final byte[] bytes;
        private long receiveTimestampNanos;
        private int length;

        private RawFrame(final int maxFrameBytes) {
            this.bytes = new byte[maxFrameBytes];
        }
    }

    private static final class CycleBuffer {
        private final long minute;
        private final ByteArrayOutputStream byteBuffer;
        private final DataOutputStream output;
        private long firstTimestampNanos;
        private long lastTimestampNanos;
        private long messageCount;

        private CycleBuffer(long minute, int listingId) throws IOException {
            this.minute = minute;
            this.byteBuffer = new ByteArrayOutputStream();
            this.output = new DataOutputStream(new ZstdOutputStream(this.byteBuffer));
            this.output.write(MAGIC);
            this.output.writeInt(FORMAT_VERSION);
            this.output.writeInt(listingId);
        }

        private void write(long receiveTimestampNanos, byte[] message, int length) throws IOException {
            if (this.messageCount == 0) {
                this.firstTimestampNanos = receiveTimestampNanos;
            }
            this.lastTimestampNanos = receiveTimestampNanos;
            this.messageCount++;
            this.output.writeLong(receiveTimestampNanos);
            this.output.writeInt(length);
            this.output.write(message, 0, length);
        }

        private byte[] finish() throws IOException {
            this.output.close();
            return this.byteBuffer.toByteArray();
        }
    }

    private record UploadBatch(
            long minute, long firstTimestampNanos, long lastTimestampNanos, long messageCount, byte[] compressedData) {}

    private final Logger logger;
    private final S3Client s3Client;
    private final Listing listing;
    private final String bucketName;
    private final CollectionContext collectionContext;
    private final int maxFrameBytes;
    private final ExecutorService uploader;
    private final AtomicReference<Throwable> archiveFailure;
    private final AtomicReference<Throwable> uploadFailure;
    private final OneToOneRingBuffer<RawFrame> frames;
    private final Thread archiveThread;

    private CycleBuffer currentCycle;
    private volatile boolean accepting;
    private volatile boolean producerActive;
    private volatile boolean closed;
    private volatile long lastMessageNanos;
    private volatile long invalidBoundsCount;
    private volatile long oversizedFrameCount;
    private volatile long ringSaturationCount;
    private long archivedFrameCount;

    public RawMarketDataCollector(
            Logger logger, S3Client s3Client, Listing listing, String bucketName, CollectionContext collectionContext) {
        this(
                logger,
                s3Client,
                listing,
                bucketName,
                collectionContext,
                createUploader(listing.listingId()),
                true,
                DEFAULT_FRAME_CAPACITY,
                DEFAULT_MAX_FRAME_BYTES);
    }

    RawMarketDataCollector(
            Logger logger,
            S3Client s3Client,
            Listing listing,
            String bucketName,
            ExecutorService uploader,
            boolean attachShutdownHook) {
        this(
                logger,
                s3Client,
                listing,
                bucketName,
                CollectionContext.untracked(),
                uploader,
                attachShutdownHook,
                DEFAULT_FRAME_CAPACITY,
                DEFAULT_MAX_FRAME_BYTES);
    }

    RawMarketDataCollector(
            Logger logger,
            S3Client s3Client,
            Listing listing,
            String bucketName,
            CollectionContext collectionContext,
            ExecutorService uploader,
            boolean attachShutdownHook,
            int frameCapacity,
            int maxFrameBytes) {
        this(
                logger,
                s3Client,
                listing,
                bucketName,
                collectionContext,
                uploader,
                attachShutdownHook,
                frameCapacity,
                maxFrameBytes,
                true);
    }

    RawMarketDataCollector(
            Logger logger,
            S3Client s3Client,
            Listing listing,
            String bucketName,
            CollectionContext collectionContext,
            ExecutorService uploader,
            boolean attachShutdownHook,
            int frameCapacity,
            int maxFrameBytes,
            boolean startArchiveThread) {
        this.logger = logger;
        this.s3Client = s3Client;
        this.listing = listing;
        this.bucketName = bucketName;
        this.collectionContext = collectionContext;
        this.maxFrameBytes = maxFrameBytes;
        this.uploader = uploader;
        this.archiveFailure = new AtomicReference<>();
        this.uploadFailure = new AtomicReference<>();
        this.frames = new OneToOneRingBuffer<>(RawFrame[]::new, () -> new RawFrame(maxFrameBytes), frameCapacity);
        this.currentCycle = null;
        this.accepting = true;
        this.producerActive = false;
        this.closed = false;
        this.lastMessageNanos = 0L;
        this.archivedFrameCount = 0L;
        this.archiveThread = new Thread(this::archiveLoop, "venue-raw-archiver-" + listing.listingId());
        this.archiveThread.setDaemon(true);
        if (startArchiveThread) {
            this.archiveThread.start();
        }

        if (attachShutdownHook) {
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(this::closeFromShutdownHook, "venue-raw-shutdown-" + listing.listingId()));
        }
    }

    /**
     * Copies a socket frame into a preallocated slot. The success path performs no allocation and
     * does not mutate the decoder's buffer position.
     */
    @Override
    public void onMessage(
            final ByteBuffer message, final int offset, final int length, final long receiveTimestampNanos)
            throws IOException {
        if (!this.accepting) {
            return;
        }

        this.producerActive = true;
        try {
            if (!this.accepting) {
                return;
            }
            final Throwable failure = firstFailure();
            if (failure != null) {
                throw new IOException("Venue-raw collector is unhealthy", failure);
            }
            if (length < 0 || offset < 0 || offset > message.limit() - length) {
                this.invalidBoundsCount++;
                throw failCapture("Invalid venue-raw frame bounds");
            }
            if (length > this.maxFrameBytes) {
                this.oversizedFrameCount++;
                throw failCapture("Venue-raw frame exceeds configured maximum of " + this.maxFrameBytes);
            }

            final int index = this.frames.tryClaim();
            if (index < 0) {
                this.ringSaturationCount++;
                throw failCapture("Venue-raw frame ring is full");
            }
            final RawFrame frame = this.frames.indexAt(index);
            frame.receiveTimestampNanos = receiveTimestampNanos;
            frame.length = length;
            message.get(offset, frame.bytes, 0, length);
            this.frames.commit(index);
            this.lastMessageNanos = receiveTimestampNanos;
        } finally {
            this.producerActive = false;
            LockSupport.unpark(this.archiveThread);
        }
    }

    public long lastMessageNanos() {
        return this.lastMessageNanos;
    }

    public boolean isHealthy() {
        return firstFailure() == null;
    }

    public long invalidBoundsCount() {
        return this.invalidBoundsCount;
    }

    public long oversizedFrameCount() {
        return this.oversizedFrameCount;
    }

    public long ringSaturationCount() {
        return this.ringSaturationCount;
    }

    @Override
    public synchronized void close() throws IOException {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.accepting = false;
        LockSupport.unpark(this.archiveThread);

        IOException closeFailure = null;
        try {
            this.archiveThread.join(TimeUnit.SECONDS.toMillis(60));
            if (this.archiveThread.isAlive()) {
                closeFailure = new IOException("Timed out while draining venue-raw frames");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeFailure = new IOException("Interrupted while draining venue-raw frames", e);
        } finally {
            this.uploader.shutdown();
        }

        try {
            if (!this.uploader.awaitTermination(60, TimeUnit.SECONDS)) {
                this.uploader.shutdownNow();
                if (closeFailure == null) {
                    closeFailure = new IOException("Timed out while uploading venue-raw data");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.uploader.shutdownNow();
            if (closeFailure == null) {
                closeFailure = new IOException("Interrupted while uploading venue-raw data", e);
            }
        }

        final Throwable failure = firstFailure();
        if (closeFailure != null) {
            throw closeFailure;
        }
        if (failure != null) {
            throw new IOException("Venue-raw archival failed", failure);
        }
    }

    private void archiveLoop() {
        try {
            while (true) {
                final long before = this.archivedFrameCount;
                this.frames.read(this::archiveFrame, DRAIN_LIMIT);
                if (this.archivedFrameCount != before) {
                    continue;
                }
                if (!this.accepting && !this.producerActive) {
                    final long finalBefore = this.archivedFrameCount;
                    this.frames.read(this::archiveFrame, DRAIN_LIMIT);
                    if (this.archivedFrameCount == finalBefore) {
                        break;
                    }
                    continue;
                }
                LockSupport.parkNanos(IDLE_PARK_NANOS);
            }

            if (this.currentCycle != null) {
                submit(finish(this.currentCycle));
                this.currentCycle = null;
            }
        } catch (Throwable failure) {
            recordArchiveFailure(failure);
        }
    }

    private void archiveFrame(final RawFrame frame) {
        if (this.archiveFailure.get() != null) {
            return;
        }
        try {
            final long minute = Math.floorDiv(frame.receiveTimestampNanos, NANOS_PER_MINUTE);
            if (this.currentCycle == null) {
                this.currentCycle = new CycleBuffer(minute, this.listing.listingId());
            } else if (this.currentCycle.minute != minute) {
                submit(finish(this.currentCycle));
                this.currentCycle = new CycleBuffer(minute, this.listing.listingId());
            }
            this.currentCycle.write(frame.receiveTimestampNanos, frame.bytes, frame.length);
        } catch (Throwable failure) {
            recordArchiveFailure(failure);
        } finally {
            this.archivedFrameCount++;
        }
    }

    private UploadBatch finish(final CycleBuffer cycle) throws IOException {
        return new UploadBatch(
                cycle.minute, cycle.firstTimestampNanos, cycle.lastTimestampNanos, cycle.messageCount, cycle.finish());
    }

    private void submit(final UploadBatch batch) throws IOException {
        try {
            this.uploader.execute(() -> upload(batch));
        } catch (RuntimeException e) {
            recordUploadFailure(e);
            throw new IOException("Venue-raw upload queue is unavailable", e);
        }
    }

    private void upload(final UploadBatch batch) {
        try {
            final Instant minuteStart =
                    Instant.ofEpochSecond(batch.minute * 60L).truncatedTo(ChronoUnit.MINUTES);
            final String keyPrefix = "v1/exchange=%d/security=%d/listing=%d/%s/"
                    .formatted(
                            this.listing.exchange().exchangeId(),
                            this.listing.security().securityId(),
                            this.listing.listingId(),
                            KEY_TIME_FORMAT.format(minuteStart));
            final String objectId = UUID.randomUUID().toString();
            final String dataKey = keyPrefix + objectId + ".raw.zst";
            final String manifestKey = keyPrefix + objectId + ".manifest.json";
            final String checksum = sha256(batch.compressedData);

            this.s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(this.bucketName)
                            .key(dataKey)
                            .contentType("application/zstd")
                            .metadata(java.util.Map.of("format", "gnome-raw-v1", "sha256", checksum))
                            .build(),
                    RequestBody.fromBytes(batch.compressedData));

            final String manifest =
                    ("""
                    {"format":"gnome-raw-v1","compression":"zstd","listingId":%d,"exchangeId":%d,
                    "securityId":%d,"collectionId":"%s","contractMetadataKey":"%s",
                    "firstReceiveTimestampNanos":%d,"lastReceiveTimestampNanos":%d,
                    "messageCount":%d,"sha256":"%s","dataKey":"%s"}
                    """)
                            .formatted(
                                    this.listing.listingId(),
                                    this.listing.exchange().exchangeId(),
                                    this.listing.security().securityId(),
                                    this.collectionContext.collectionId(),
                                    this.collectionContext.contractMetadataKey(),
                                    batch.firstTimestampNanos,
                                    batch.lastTimestampNanos,
                                    batch.messageCount,
                                    checksum,
                                    dataKey)
                            .replace("\n", "");
            this.s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(this.bucketName)
                            .key(manifestKey)
                            .contentType("application/json")
                            .build(),
                    RequestBody.fromString(manifest, StandardCharsets.UTF_8));

            this.logger.logf(
                    LogMessage.DEBUG, "Uploaded %d lossless venue messages to %s", batch.messageCount, dataKey);
        } catch (RuntimeException e) {
            recordUploadFailure(e);
        }
    }

    private Throwable firstFailure() {
        final Throwable archive = this.archiveFailure.get();
        return archive == null ? this.uploadFailure.get() : archive;
    }

    private IOException failCapture(final String message) {
        final IOException failure = new IOException(message);
        recordArchiveFailure(failure);
        return failure;
    }

    private void recordArchiveFailure(final Throwable failure) {
        this.archiveFailure.compareAndSet(null, failure);
        this.accepting = false;
        this.logger.logf(LogMessage.UNKNOWN_ERROR, "Venue-raw archival failed: %s", failure.getMessage());
    }

    private void recordUploadFailure(final Throwable failure) {
        this.uploadFailure.compareAndSet(null, failure);
        this.logger.logf(LogMessage.UNKNOWN_ERROR, "Venue-raw upload failed: %s", failure.getMessage());
    }

    private void closeFromShutdownHook() {
        try {
            close();
        } catch (IOException e) {
            recordArchiveFailure(e);
        }
    }

    private static String sha256(final byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static ExecutorService createUploader(final int listingId) {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8),
                runnable -> {
                    final Thread thread = new Thread(runnable, "venue-raw-uploader-" + listingId);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
