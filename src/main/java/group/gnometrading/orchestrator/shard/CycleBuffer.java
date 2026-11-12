package group.gnometrading.orchestrator.shard;

import com.github.luben.zstd.ZstdDirectBufferCompressingStreamNoFinalizer;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * One cycle's file: records zstd-compressed as they arrive into pooled direct memory, so a minute of market data puts
 * nothing on the heap, and uploaded straight from that memory.
 *
 * <p>Filled on the thread receiving data, then handed to the uploader, which finishes, uploads and releases it.
 */
final class CycleBuffer {

    private static final int COMPRESSION_LEVEL = 3;
    // zstd only reads and writes direct buffers: input is copied into the staging buffer, and compressed output
    // collects in the target until it is appended to the file's buffer.
    private static final int STAGING_CAPACITY = 1 << 16;
    private static final int TARGET_CAPACITY = 1 << 17;
    private static final int INITIAL_OUTPUT_CAPACITY = 1 << 16;
    private static final int HTTP_PRECONDITION_FAILED = 412;

    private final DirectBufferPool pool;
    private final LocalDateTime timestamp;
    private final String s3Key;
    private final ByteBuffer staging;
    private final UnsafeBuffer stagingView;
    private final ByteBuffer target;
    private final ZstdDirectBufferCompressingStreamNoFinalizer compressor;
    private ByteBuffer output;
    private boolean finished;
    private boolean empty = true;
    private boolean released;

    CycleBuffer(DirectBufferPool pool, LocalDateTime timestamp, String s3Key) {
        this.pool = pool;
        this.timestamp = timestamp;
        this.s3Key = s3Key;
        this.staging = pool.acquire(STAGING_CAPACITY);
        this.stagingView = new UnsafeBuffer(this.staging);
        this.target = pool.acquire(TARGET_CAPACITY);
        this.output = pool.acquire(INITIAL_OUTPUT_CAPACITY);
        try {
            this.compressor = new Compressor(this.target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start a zstd stream", e);
        }
    }

    LocalDateTime timestamp() {
        return timestamp;
    }

    String s3Key() {
        return s3Key;
    }

    /** Compresses {@code length} bytes of {@code source} from {@code offset}. */
    void write(DirectBuffer source, int offset, int length) throws IOException {
        empty &= length == 0;
        for (int done = 0; done < length; ) {
            final int chunk = Math.min(STAGING_CAPACITY, length - done);
            stagingView.putBytes(0, source, offset + done, chunk);
            compressStaged(chunk);
            done += chunk;
        }
    }

    /** Compresses {@code length} bytes of {@code source} from {@code index}, leaving its position and limit alone. */
    void write(ByteBuffer source, int index, int length) throws IOException {
        empty &= length == 0;
        for (int done = 0; done < length; ) {
            final int chunk = Math.min(STAGING_CAPACITY, length - done);
            staging.clear();
            staging.put(0, source, index + done, chunk);
            compressStaged(chunk);
            done += chunk;
        }
    }

    private void compressStaged(int length) throws IOException {
        staging.clear().limit(length);
        compressor.compress(staging);
    }

    /** Whether nothing was written to it: a minute with no records, which isn't worth a file. */
    boolean isEmpty() {
        return empty;
    }

    /** Bytes of the finished file; only meaningful after {@link #finish}. */
    int compressedSize() {
        return output.position();
    }

    /** Ends the zstd frame; nothing may be written afterwards. */
    void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        // zstd writes no frame for a stream it never compressed into; an empty minute is released, not uploaded, but
        // a finished buffer must still be a whole frame.
        compressStaged(0);
        compressor.close();
        pool.release(staging);
        pool.release(target);
    }

    void upload(S3Client s3Client, String bucketName, Logger logger) throws IOException {
        finish();
        final int length = output.position();
        if (length == 0) {
            logger.logf(LogMessage.DEBUG, "Skipping upload for empty buffer");
            return;
        }
        logger.logf(LogMessage.DEBUG, "Uploading file: %s (%d bytes)", s3Key, length);
        try {
            // A fresh view per attempt: the SDK may read the body more than once when it retries.
            s3Client.putObject(
                    request -> request.key(s3Key).bucket(bucketName).ifNoneMatch("*"),
                    RequestBody.fromContentProvider(
                            () -> new ByteBufferInputStream(output.duplicate().flip()),
                            length,
                            "application/octet-stream"));
        } catch (S3Exception e) {
            // Each minute has one file and the first writer keeps it: a 412 is our own retried success or another
            // shard's copy of the minute, never a failure to retry.
            if (e.statusCode() != HTTP_PRECONDITION_FAILED) {
                throw e;
            }
            logger.logf(LogMessage.DEBUG, "Already written, keeping the first: %s", s3Key);
        }
    }

    /** Returns every buffer to the pool; the file is gone afterwards. */
    void release() {
        if (released) {
            return;
        }
        released = true;
        try {
            finish();
        } catch (IOException e) {
            // The file is being discarded, so a failure to end its frame changes nothing.
        }
        pool.release(output);
    }

    private void append(ByteBuffer compressed) {
        if (output.remaining() < compressed.remaining()) {
            final ByteBuffer grown =
                    pool.acquire(Math.max(output.capacity() * 2, output.position() + compressed.remaining()));
            grown.put(output.flip());
            pool.release(output);
            output = grown;
        }
        output.put(compressed);
    }

    /** zstd hands back its target, in write mode, whenever it fills and when the frame ends. */
    private final class Compressor extends ZstdDirectBufferCompressingStreamNoFinalizer {
        Compressor(ByteBuffer target) throws IOException {
            super(target, COMPRESSION_LEVEL);
        }

        @Override
        protected ByteBuffer flushBuffer(ByteBuffer toFlush) {
            append(toFlush.flip());
            return toFlush.clear();
        }
    }

    private static final class ByteBufferInputStream extends InputStream {
        private final ByteBuffer buffer;

        ByteBufferInputStream(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public int read() {
            return buffer.hasRemaining() ? buffer.get() & 0xFF : -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            if (!buffer.hasRemaining()) {
                return -1;
            }
            final int count = Math.min(length, buffer.remaining());
            buffer.get(bytes, offset, count);
            return count;
        }

        @Override
        public int available() {
            return buffer.remaining();
        }
    }
}
