package group.gnometrading.orchestrator.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.luben.zstd.ZstdInputStream;
import com.sun.management.ThreadMXBean;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.RecordType;
import group.gnometrading.schemas.book.Mbp10Encoder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Recording books into minute files is the shard's busiest path, run once per record on the collector's thread.
 * Measured with a real clock and a hand-written S3 client: a Mockito mock's invocations allocate.
 */
class MarketDataCollectorAllocationTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);
    private static final long START = TimeUnit.DAYS.toNanos(20_000);

    private final UploadCounter s3 = new UploadCounter();
    private final Mbp10Schema record = new Mbp10Schema();
    private long sequence;

    private MarketDataCollector collector() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(0, START), ZoneOffset.UTC);
        return new MarketDataCollector(
                new NullLogger(),
                clock,
                new CycleUploader(new NullLogger(), s3, Runnable::run),
                new DirectBufferPool(),
                7,
                RecordType.MBP_10,
                "bucket");
    }

    /** A book that moves a little each record, so the files compress like real ones rather than to nothing. */
    private Mbp10Schema book(long eventNanos) {
        Mbp10Encoder encoder = record.encoder;
        encoder.streamId(7);
        encoder.timestampEvent(eventNanos);
        encoder.timestampRecv(eventNanos + 1_000 + (sequence % 97));
        encoder.sequence(++sequence);
        encoder.price(500_000_000L + (sequence % 13) * 1_000_000L);
        encoder.size(1_000_000L * (1 + sequence % 7));
        encoder.bidPrice0(490_000_000L + (sequence % 5) * 10_000_000L);
        encoder.askPrice0(510_000_000L + (sequence % 3) * 10_000_000L);
        encoder.bidSize0(1_000_000L * (sequence % 11));
        encoder.askSize0(1_000_000L * (sequence % 17));
        return record;
    }

    private static long allocatedOnThisThread() {
        return ((ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    @Test
    void recordingWithinAMinuteAllocatesNothingPerRecord() {
        MarketDataCollector collector = collector();
        // 20 records a millisecond: far busier than any market, so the minute's file grows through several sizes.
        long step = MINUTE / 2 / 600_000;
        for (int i = 0; i < 300_000; i++) {
            collector.onEvent(book(START + i * step), i, false);
        }

        long before = allocatedOnThisThread();
        for (int i = 300_000; i < 600_000; i++) {
            collector.onEvent(book(START + i * step), i, false);
        }
        long allocated = allocatedOnThisThread() - before;

        assertEquals(0, s3.uploads, "all within the first minute");
        // The file's buffer doubling now and then is all that may allocate: a few small objects in total, where one
        // object per record would be 300,000 x 16 bytes.
        assertTrue(allocated < 4_096, "bytes allocated by 300,000 records: " + allocated);
    }

    @Test
    void steadyMinutesReuseTheirMemory() {
        MarketDataCollector collector = collector();
        BufferPoolMXBean direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals("direct"))
                .findFirst()
                .orElseThrow();
        List<Long> directBytesAfterEachMinute = new ArrayList<>();
        List<Long> heapPerMinute = new ArrayList<>();

        int records = 0;
        for (int minute = 0; minute < 12; minute++) {
            long before = allocatedOnThisThread();
            for (int i = 0; i < 6_000; i++) {
                collector.onEvent(book(START + minute * MINUTE + i * (MINUTE / 6_000)), records++, false);
            }
            heapPerMinute.add(allocatedOnThisThread() - before - s3.takeOwnBytes());
            directBytesAfterEachMinute.add(direct.getMemoryUsed());
        }

        assertEquals(11, s3.uploads, "each finished minute, once its grace period passed");
        assertEquals(11 * 6_000, s3.records, "every record of every finished minute, readable");
        // It may shrink, when a GC frees direct memory left over from before; it must never grow.
        assertTrue(
                directBytesAfterEachMinute.get(11) <= directBytesAfterEachMinute.get(3),
                "no new direct memory once the pool has grown: " + directBytesAfterEachMinute);
        // Each new minute builds its file (key, zstd stream) and, here, uploads the last one on this same thread; in a
        // shard the upload runs on the uploader's thread. Either way it is per minute, not per record.
        long steadyMinute = heapPerMinute.subList(3, 12).stream()
                .mapToLong(Long::longValue)
                .max()
                .orElseThrow();
        assertTrue(steadyMinute < 32 * 1024, "heap bytes per steady minute: " + heapPerMinute);
    }

    /** Counts what reaches S3, and decompresses each file to prove it is whole. */
    private static final class UploadCounter implements S3Client {
        // Every Mbp10Schema allocates direct memory of its own, so one is measured once rather than per upload.
        private static final int RECORD_SIZE = new Mbp10Schema().totalMessageSize();

        int uploads;
        long records;
        // Decompressing a whole minute to count it is the test's own work, not the collector's.
        private long ownBytes;

        long takeOwnBytes() {
            long bytes = ownBytes;
            ownBytes = 0;
            return bytes;
        }

        @Override
        public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
            uploads++;
            long before = allocatedOnThisThread();
            try (InputStream in =
                    new ZstdInputStream(body.contentStreamProvider().newStream())) {
                records += in.readAllBytes().length / RECORD_SIZE;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ownBytes += allocatedOnThisThread() - before;
            return PutObjectResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {}
    }
}
