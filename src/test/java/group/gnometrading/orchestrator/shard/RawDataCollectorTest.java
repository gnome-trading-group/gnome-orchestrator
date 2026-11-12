package group.gnometrading.orchestrator.shard;

import static java.time.ZoneOffset.UTC;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.luben.zstd.ZstdInputStream;
import group.gnometrading.logging.NullLogger;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

@ExtendWith(MockitoExtension.class)
class RawDataCollectorTest {

    private static final String BUCKET = "test-bucket";
    private static final Pattern KEY_PATTERN =
            Pattern.compile("^raw/KALSHI/shard-1/KALSHI-0/\\d{4}/\\d{2}/\\d{2}/\\d{2}/\\d{2}/[a-f0-9-]{36}\\.zst$");

    @Mock
    S3Client s3Client;

    @Mock
    Clock clock;

    @BeforeEach
    void setup() {
        doReturn(ZoneId.of("UTC")).when(clock).getZone();
        lenient()
                .when(s3Client.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
    }

    private RawDataCollector collector() {
        return new RawDataCollector(
                new NullLogger(),
                clock,
                new CycleUploader(new NullLogger(), s3Client, Runnable::run),
                new DirectBufferPool(),
                "KALSHI",
                "shard-1",
                "KALSHI-0",
                BUCKET);
    }

    private ByteBuffer buf(String payload) {
        byte[] bytes = payload.getBytes();
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        return buf;
    }

    private long nanos(int year, int month, int day, int hour, int minute, int second) {
        Instant instant =
                LocalDateTime.of(year, month, day, hour, minute, second).toInstant(UTC);
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    private void date(int year, int month, int day, int hour, int minute, int second) {
        when(clock.instant())
                .thenReturn(
                        LocalDateTime.of(year, month, day, hour, minute, second).toInstant(UTC));
    }

    @Test
    void captureCopiesADirectBufferWithoutMovingIt() throws IOException {
        List<byte[]> uploaded = new ArrayList<>();
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class))).thenAnswer(invocation -> {
            RequestBody body = invocation.getArgument(1);
            uploaded.add(body.contentStreamProvider().newStream().readAllBytes());
            return PutObjectResponse.builder().build();
        });
        date(2025, 4, 1, 12, 0, 0);
        RawDataCollector collector = collector();

        byte[] payload = "direct payload".getBytes();
        ByteBuffer direct = ByteBuffer.allocateDirect(64);
        direct.put(new byte[3]).put(payload).flip().position(3);
        collector.capture(nanos(2025, 4, 1, 12, 0, 30), direct);
        collector.capture(nanos(2025, 4, 1, 12, 1, 1), buf("next"));

        assertEquals(3, direct.position(), "the gateway still has to parse the message");
        try (ZstdInputStream zstd = new ZstdInputStream(new ByteArrayInputStream(uploaded.get(0)))) {
            byte[] record = zstd.readAllBytes();
            assertArrayEquals(payload, java.util.Arrays.copyOfRange(record, 12, record.length));
        }
    }

    @Test
    void captureWithinACycleAllocatesNothingPerMessage() {
        date(2025, 4, 1, 12, 0, 0);
        RawDataCollector collector = collector();
        ByteBuffer message = ByteBuffer.allocateDirect(256);
        message.put(new byte[200]).flip();
        long minuteStart = nanos(2025, 4, 1, 12, 0, 0);
        for (int i = 0; i < 10_000; i++) {
            collector.capture(minuteStart + i, message);
        }

        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long before = threads.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 10_000; i++) {
            collector.capture(minuteStart + 10_000 + i, message);
        }
        long allocated = threads.getThreadAllocatedBytes(thread) - before;

        // The minute file grows as compressed data accumulates, so some allocation is amortized across captures;
        // a single object per capture would be at least 16 bytes each.
        assertTrue(allocated < 10_000 * 16L, "bytes allocated by 10,000 captures: " + allocated);
    }

    @Test
    void testNoUploadWithinSameMinute() {
        date(2025, 4, 1, 12, 0, 0);
        RawDataCollector collector = collector();

        collector.capture(nanos(2025, 4, 1, 12, 0, 5), buf("aaa"));
        collector.capture(nanos(2025, 4, 1, 12, 0, 30), buf("bbb"));
        collector.capture(nanos(2025, 4, 1, 12, 0, 59), buf("ccc"));

        verify(s3Client, never()).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void testRotationAtMinuteBoundary() {
        date(2025, 4, 1, 12, 0, 0);
        RawDataCollector collector = collector();

        collector.capture(nanos(2025, 4, 1, 12, 0, 30), buf("data"));
        collector.capture(nanos(2025, 4, 1, 12, 1, 1), buf("next"));

        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void testMultipleRotations() {
        date(2025, 4, 1, 12, 0, 0);
        RawDataCollector collector = collector();

        collector.capture(nanos(2025, 4, 1, 12, 0, 10), buf("m0"));
        collector.capture(nanos(2025, 4, 1, 12, 1, 1), buf("m1"));
        collector.capture(nanos(2025, 4, 1, 12, 2, 1), buf("m2"));

        verify(s3Client, times(2)).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void testRecordFormatRoundtrip() throws IOException {
        List<byte[]> uploaded = new ArrayList<>();
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class))).thenAnswer(invocation -> {
            RequestBody body = invocation.getArgument(1);
            uploaded.add(body.contentStreamProvider().newStream().readAllBytes());
            return PutObjectResponse.builder().build();
        });

        date(2025, 4, 1, 12, 0, 0);
        RawDataCollector collector = collector();

        byte[] payload = "hello exchange".getBytes();
        long ts = nanos(2025, 4, 1, 12, 0, 30);
        collector.capture(ts, ByteBuffer.wrap(payload));

        collector.capture(nanos(2025, 4, 1, 12, 1, 1), buf("rotate"));

        assertEquals(1, uploaded.size());
        byte[] decompressed;
        try (ZstdInputStream zstd = new ZstdInputStream(new ByteArrayInputStream(uploaded.get(0)))) {
            decompressed = zstd.readAllBytes();
        }

        // First record: 8B recvTimestamp (LE long) + 4B payloadLength (LE int) + payload
        assertTrue(decompressed.length >= 12 + payload.length);

        ByteBuffer record = ByteBuffer.wrap(decompressed).order(ByteOrder.LITTLE_ENDIAN);
        long readTs = record.getLong();
        int readLen = record.getInt();
        byte[] readPayload = new byte[readLen];
        record.get(readPayload);

        assertEquals(ts, readTs);
        assertEquals(payload.length, readLen);
        assertArrayEquals(payload, readPayload);
    }

    @Test
    void testKeyFormat() {
        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> requestCaptor = ArgumentCaptor.forClass(Consumer.class);
        when(s3Client.putObject(requestCaptor.capture(), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        date(2025, 4, 1, 12, 34, 0);
        RawDataCollector collector = collector();

        collector.capture(nanos(2025, 4, 1, 12, 34, 5), buf("data"));
        collector.capture(nanos(2025, 4, 1, 12, 35, 1), buf("next"));

        PutObjectRequest.Builder builder = PutObjectRequest.builder();
        requestCaptor.getValue().accept(builder);
        String key = builder.build().key();

        assertTrue(KEY_PATTERN.matcher(key).matches(), "Key should match pattern: " + key);
        assertTrue(key.startsWith("raw/KALSHI/shard-1/KALSHI-0/"), "Key should start with the connection");
        assertTrue(key.contains("2025/04/01/12/34"), "Key should contain timestamp");
    }

    @Test
    void flushUploadsThePartialMinute() {
        date(2025, 4, 1, 12, 0, 0);
        RawDataCollector collector = collector();
        collector.capture(nanos(2025, 4, 1, 12, 0, 10), buf("data"));

        collector.flush();

        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));
    }
}
