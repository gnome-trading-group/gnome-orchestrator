package group.gnometrading.orchestrator.shard;

import static java.time.ZoneOffset.UTC;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.luben.zstd.ZstdInputStream;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.RecordType;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.StreamHeader;
import group.gnometrading.schemas.StreamRecord;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

@ExtendWith(MockitoExtension.class)
class MarketDataCollectorTest {

    private static final int STREAM_ID = 499;
    private static final String OUTPUT_BUCKET = "test-bucket";
    private static final Pattern KEY_PATTERN =
            Pattern.compile("^499/book/mbp-10/\\d{4}/\\d{2}/\\d{2}/\\d{2}/\\d{2}\\.zst$");

    @Mock
    S3Client s3Client;

    @Mock
    Clock clock;

    @BeforeEach
    void setup() {
        doReturn(ZoneId.of("UTC")).when(clock).getZone();

        lenient()
                .when(s3Client.putObject(
                        ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    Consumer<PutObjectRequest.Builder> requestBuilder = invocation.getArgument(0);
                    return PutObjectResponse.builder().build();
                });
    }

    @Test
    void testNoUploadWithinSameMinute() throws Exception {
        date(2025, 4, 1, 12, 0, 0);
        MarketDataCollector collector = collector(Duration.ZERO);

        collector.onEvent(bufWithTimestamp("aaaaaaaa", 2025, 4, 1, 12, 0, 5), 0, false);
        collector.onEvent(bufWithTimestamp("bbbbbbbb", 2025, 4, 1, 12, 0, 15), 1, false);
        collector.onEvent(bufWithTimestamp("cccccccc", 2025, 4, 1, 12, 0, 30), 2, false);
        collector.onEvent(bufWithTimestamp("dddddddd", 2025, 4, 1, 12, 0, 59), 3, false);

        verify(s3Client, never())
                .putObject(ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class));
    }

    @Test
    void testFileUploadEveryMinute() throws Exception {
        date(2025, 4, 1, 12, 0, 0);

        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> requestBuilderCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        when(s3Client.putObject(requestBuilderCaptor.capture(), any(RequestBody.class)))
                .thenAnswer(invocation -> PutObjectResponse.builder().build());

        MarketDataCollector collector = collector(Duration.ZERO);

        collector.onEvent(bufWithTimestamp("aaaaaaaa", 2025, 4, 1, 12, 0, 30), 0, false);
        collector.onEvent(bufWithTimestamp("bbbbbbbb", 2025, 4, 1, 12, 1, 1), 1, false);

        verify(s3Client, times(1))
                .putObject(ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class));

        collector.onEvent(bufWithTimestamp("cccccccc", 2025, 4, 1, 12, 2, 1), 2, false);

        verify(s3Client, times(2))
                .putObject(ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class));

        List<Consumer<PutObjectRequest.Builder>> builders = requestBuilderCaptor.getAllValues();

        PutObjectRequest.Builder builder1 = PutObjectRequest.builder();
        builders.get(0).accept(builder1);
        PutObjectRequest request1 = builder1.build();

        PutObjectRequest.Builder builder2 = PutObjectRequest.builder();
        builders.get(1).accept(builder2);
        PutObjectRequest request2 = builder2.build();

        assertTrue(request1.key().contains("2025/04/01/12/00"), "First key should contain timestamp 2025/04/01/12/00");
        assertTrue(request2.key().contains("2025/04/01/12/01"), "Second key should contain timestamp 2025/04/01/12/01");
    }

    @Test
    void testExactMinuteBoundaryTriggersRotation() throws Exception {
        date(2025, 4, 1, 12, 0, 0);

        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> requestBuilderCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        when(s3Client.putObject(requestBuilderCaptor.capture(), any(RequestBody.class)))
                .thenAnswer(invocation -> PutObjectResponse.builder().build());

        MarketDataCollector collector = collector(Duration.ZERO);

        collector.onEvent(bufWithTimestamp("aaaaaaaa", 2025, 4, 1, 12, 0, 30), 0, false);
        collector.onEvent(bufWithTimestamp("bbbbbbbb", 2025, 4, 1, 12, 1, 0), 1, false);

        verify(s3Client, times(1))
                .putObject(ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class));

        PutObjectRequest.Builder builder = PutObjectRequest.builder();
        requestBuilderCaptor.getValue().accept(builder);
        PutObjectRequest request = builder.build();
        assertTrue(request.key().contains("2025/04/01/12/00"), "Key should be for 12:00 minute");
    }

    @Test
    void testOneSecondBeforeMinuteBoundaryDoesNotRotate() throws Exception {
        date(2025, 4, 1, 12, 0, 0);
        MarketDataCollector collector = collector(Duration.ZERO);

        collector.onEvent(bufWithTimestamp("aaaaaaaa", 2025, 4, 1, 12, 0, 30), 0, false);
        collector.onEvent(bufWithTimestamp("bbbbbbbb", 2025, 4, 1, 12, 0, 59), 1, false);

        verify(s3Client, never())
                .putObject(ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class));
    }

    @Test
    void testCollectorStartsAtNonZeroSecond() throws Exception {
        date(2025, 4, 1, 12, 0, 55);
        MarketDataCollector collector = collector(Duration.ZERO);

        collector.onEvent(bufWithTimestamp("aaaaaaaa", 2025, 4, 1, 12, 0, 58), 0, false);
        collector.onEvent(bufWithTimestamp("bbbbbbbb", 2025, 4, 1, 12, 1, 55), 1, false);

        verify(s3Client, times(1))
                .putObject(ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class));
    }

    @Test
    void aMinuteWithNoRecordsIsNotUploaded() throws Exception {
        date(2025, 4, 1, 12, 0, 0);
        MarketDataCollector collector = collector(Duration.ZERO);

        collector.onEvent(bufWithTimestamp("", 2025, 4, 1, 12, 1, 1), 0, false);

        verify(s3Client, never())
                .putObject(ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class));
    }

    @Test
    void aQuietMarketsMinuteIsUploadedOnceItHasEndedByTheWallClock() throws Exception {
        date(2025, 4, 1, 12, 0, 0);
        MarketDataCollector collector = collector(Duration.ofSeconds(5));
        collector.onEvent(bufWithTimestamp("snapshot", 2025, 4, 1, 12, 0, 10), 0, false);

        collector.expire(CycleManager.epochNanos(Instant.parse("2025-04-01T12:01:05Z")));

        verify(s3Client, times(1))
                .putObject(ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class));
    }

    @Test
    void testKeyFormatAndStructure() throws Exception {
        date(2025, 4, 1, 12, 34, 56);

        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> requestBuilderCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        when(s3Client.putObject(requestBuilderCaptor.capture(), any(RequestBody.class)))
                .thenAnswer(invocation -> PutObjectResponse.builder().build());

        MarketDataCollector collector = collector(Duration.ZERO);

        collector.onEvent(bufWithTimestamp("testdata", 2025, 4, 1, 12, 34, 58), 0, false);
        collector.onEvent(bufWithTimestamp("moredata", 2025, 4, 1, 12, 35, 1), 1, false);

        PutObjectRequest.Builder builder = PutObjectRequest.builder();
        requestBuilderCaptor.getValue().accept(builder);
        PutObjectRequest request = builder.build();
        String key = request.key();

        assertTrue(KEY_PATTERN.matcher(key).matches(), "Key should match pattern: " + key);
        assertTrue(key.startsWith("499/book/mbp-10/"), "Key should start with the stream, family and record");
        assertTrue(key.contains("2025/04/01/12/34"), "Key should contain timestamp 2025/04/01/12/34");
        assertTrue(key.endsWith(".zst"), "Key should end with .zst");

        assertEquals(OUTPUT_BUCKET, request.bucket());
    }

    @Test
    void testMultipleFileUploads() throws Exception {
        date(2025, 4, 1, 12, 0, 0);

        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> requestBuilderCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        when(s3Client.putObject(requestBuilderCaptor.capture(), any(RequestBody.class)))
                .thenAnswer(invocation -> PutObjectResponse.builder().build());

        MarketDataCollector collector = collector(Duration.ZERO);

        collector.onEvent(bufWithTimestamp("data0", 2025, 4, 1, 12, 0, 10), 0, false);
        collector.onEvent(bufWithTimestamp("data1", 2025, 4, 1, 12, 0, 20), 1, false);
        collector.onEvent(bufWithTimestamp("data2", 2025, 4, 1, 12, 1, 1), 2, false);
        collector.onEvent(bufWithTimestamp("data3", 2025, 4, 1, 12, 1, 30), 3, false);
        collector.onEvent(bufWithTimestamp("data4", 2025, 4, 1, 12, 2, 1), 4, false);

        verify(s3Client, times(2)).putObject(requestBuilderCaptor.capture(), any(RequestBody.class));

        List<Consumer<PutObjectRequest.Builder>> builders = requestBuilderCaptor.getAllValues();

        PutObjectRequest.Builder builder1 = PutObjectRequest.builder();
        builders.get(0).accept(builder1);
        PutObjectRequest request1 = builder1.build();

        PutObjectRequest.Builder builder2 = PutObjectRequest.builder();
        builders.get(1).accept(builder2);
        PutObjectRequest request2 = builder2.build();

        String key1 = request1.key();
        String key2 = request2.key();
        assertNotEquals(key1, key2, "Keys should be different for different minutes");
        assertTrue(key1.contains("2025/04/01/12/00"), "First key should be for minute 12:00");
        assertTrue(key2.contains("2025/04/01/12/01"), "Second key should be for minute 12:01");
    }

    @Test
    void testOutOfOrderTimestampsDoNotTriggerRotation() throws Exception {
        date(2025, 4, 1, 12, 0, 0);
        MarketDataCollector collector = collector(Duration.ZERO);

        collector.onEvent(bufWithTimestamp("first", 2025, 4, 1, 12, 0, 10), 0, false);
        collector.onEvent(bufWithTimestamp("future", 2025, 4, 1, 12, 1, 30), 0, false);

        verify(s3Client, times(1))
                .putObject(ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class));

        // Out-of-order event should NOT trigger another rotation
        collector.onEvent(bufWithTimestamp("past", 2025, 4, 1, 12, 0, 30), 1, false);

        verify(s3Client, times(1))
                .putObject(ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class));
    }

    @Test
    void testDataCapturedAndCompressed() throws Exception {
        List<byte[]> capturedData = new ArrayList<>();

        when(s3Client.putObject(ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    byte[] data = body.contentStreamProvider().newStream().readAllBytes();
                    capturedData.add(data);
                    return PutObjectResponse.builder().build();
                });

        date(2025, 4, 1, 12, 0, 0);
        MarketDataCollector collector = collector(Duration.ZERO);

        collector.onEvent(bufWithTimestamp("aaaaaaaa", 2025, 4, 1, 12, 0, 10), 0, false);
        collector.onEvent(bufWithTimestamp("bbbbbbbb", 2025, 4, 1, 12, 0, 20), 1, false);
        collector.onEvent(bufWithTimestamp("cccccccc", 2025, 4, 1, 12, 0, 30), 2, false);
        collector.onEvent(bufWithTimestamp("dddddddd", 2025, 4, 1, 12, 1, 1), 3, false);

        assertEquals(1, capturedData.size());
        assertTrue(capturedData.get(0).length > 0, "Should have compressed data");

        String decompressed = decompressZstd(capturedData.get(0));
        assertTrue(decompressed.contains("aaaaaaaa"), "Should contain first event data");
        assertTrue(decompressed.contains("bbbbbbbb"), "Should contain second event data");
        assertTrue(decompressed.contains("cccccccc"), "Should contain third event data");
    }

    @Test
    void flushUploadsThePartialMinute() throws Exception {
        List<byte[]> capturedData = captureUploads();
        date(2025, 4, 1, 12, 0, 0);
        MarketDataCollector collector = collector(Duration.ZERO);
        collector.onEvent(bufWithTimestamp("data1", 2025, 4, 1, 12, 0, 10), 0, false);

        collector.flush();

        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));
        assertTrue(decompressZstd(capturedData.get(0)).contains("data1"));
    }

    @Test
    void testOutOfOrderEventsRoutedToPreviousBuffer() throws Exception {
        List<byte[]> capturedData = new ArrayList<>();
        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> keyCaptor = ArgumentCaptor.forClass(Consumer.class);

        when(s3Client.putObject(keyCaptor.capture(), any(RequestBody.class))).thenAnswer(invocation -> {
            RequestBody body = invocation.getArgument(1);
            byte[] data = body.contentStreamProvider().newStream().readAllBytes();
            capturedData.add(data);
            return PutObjectResponse.builder().build();
        });

        date(2025, 4, 1, 12, 0, 0);
        // Grace period of 10s so the previous buffer stays open long enough for the late event
        MarketDataCollector collector = collector(Duration.ofSeconds(10));

        // Event in minute 12:00
        collector.onEvent(bufWithTimestamp("early", 2025, 4, 1, 12, 0, 30), 0, false);

        // This triggers rotation: 12:00 buffer becomes previousCycle, 12:01 becomes current
        collector.onEvent(bufWithTimestamp("trigger", 2025, 4, 1, 12, 1, 2), 1, false);

        // Late event for 12:00 — arrives within grace period, should go to previous buffer
        collector.onEvent(bufWithTimestamp("late", 2025, 4, 1, 12, 0, 55), 2, false);

        // No upload yet — grace period has not expired
        verify(s3Client, never()).putObject(any(Consumer.class), any(RequestBody.class));

        // Advance event time past grace deadline (12:01:00 + 10s = 12:01:10)
        collector.onEvent(bufWithTimestamp("advance", 2025, 4, 1, 12, 1, 11), 3, false);

        // Previous buffer (12:00) should now be uploaded
        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));

        // Verify the 12:00 file contains both the in-order and out-of-order events
        assertEquals(1, capturedData.size());
        String decompressed = decompressZstd(capturedData.get(0));
        assertTrue(decompressed.contains("early"), "12:00 file should contain in-order event");
        assertTrue(decompressed.contains("late"), "12:00 file should contain out-of-order event");
        assertFalse(decompressed.contains("trigger"), "12:00 file should not contain 12:01 event");
    }

    @Test
    void testGracePeriodExpiresAndUploadsPreviousBuffer() throws Exception {
        date(2025, 4, 1, 12, 0, 0);
        MarketDataCollector collector = collector(Duration.ofSeconds(5));

        collector.onEvent(bufWithTimestamp("data", 2025, 4, 1, 12, 0, 30), 0, false);

        // Rotation: 12:00 buffer becomes previousCycle
        collector.onEvent(bufWithTimestamp("rot", 2025, 4, 1, 12, 1, 1), 1, false);

        // Still within grace period (12:01:01 < 12:01:00 + 5s = 12:01:05)
        verify(s3Client, never()).putObject(any(Consumer.class), any(RequestBody.class));

        // Exactly at grace deadline: 12:01:05 = 12:01:00 + 5s — should trigger upload
        collector.onEvent(bufWithTimestamp("grace", 2025, 4, 1, 12, 1, 5), 2, false);

        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void flushUploadsTheMinuteInItsGracePeriodAndThePartialOne() throws Exception {
        List<byte[]> capturedData = captureUploads();
        date(2025, 4, 1, 12, 0, 0);
        // Long grace period so the previous minute is still open when the stream stops.
        MarketDataCollector collector = collector(Duration.ofSeconds(30));
        collector.onEvent(bufWithTimestamp("min0", 2025, 4, 1, 12, 0, 30), 0, false);
        collector.onEvent(bufWithTimestamp("min1", 2025, 4, 1, 12, 1, 2), 1, false);
        verify(s3Client, never()).putObject(any(Consumer.class), any(RequestBody.class));

        collector.flush();
        collector.onEvent(bufWithTimestamp("after", 2025, 4, 1, 12, 1, 3), 2, false);

        verify(s3Client, times(2)).putObject(any(Consumer.class), any(RequestBody.class));
        assertTrue(decompressZstd(capturedData.get(0)).contains("min0"));
        assertTrue(decompressZstd(capturedData.get(1)).contains("min1"));
        assertFalse(decompressZstd(capturedData.get(1)).contains("after"), "nothing is written after a flush");
    }

    @Test
    void testMultipleRotationsForceFinalizePrevious() throws Exception {
        date(2025, 4, 1, 12, 0, 0);
        // Long grace period: second rotation should force-upload the previous buffer early
        MarketDataCollector collector = collector(Duration.ofSeconds(30));

        collector.onEvent(bufWithTimestamp("data0", 2025, 4, 1, 12, 0, 30), 0, false);

        // First rotation: 12:00 → previous
        collector.onEvent(bufWithTimestamp("data1", 2025, 4, 1, 12, 1, 1), 1, false);

        verify(s3Client, never()).putObject(any(Consumer.class), any(RequestBody.class));

        // Second rotation: force-uploads previous (12:00), then 12:01 → previous
        collector.onEvent(bufWithTimestamp("data2", 2025, 4, 1, 12, 2, 1), 2, false);

        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void testOutOfOrderEventAfterGraceExpiry() throws Exception {
        List<byte[]> capturedData = new ArrayList<>();

        when(s3Client.putObject(ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    byte[] data = body.contentStreamProvider().newStream().readAllBytes();
                    capturedData.add(data);
                    return PutObjectResponse.builder().build();
                });

        date(2025, 4, 1, 12, 0, 0);
        MarketDataCollector collector = collector(Duration.ofSeconds(5));

        collector.onEvent(bufWithTimestamp("early", 2025, 4, 1, 12, 0, 30), 0, false);

        // Rotation: 12:00 → previous
        collector.onEvent(bufWithTimestamp("rot", 2025, 4, 1, 12, 1, 1), 1, false);

        // Grace expires: previous uploaded
        collector.onEvent(bufWithTimestamp("grace", 2025, 4, 1, 12, 1, 6), 2, false);

        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));

        // Event for 12:00 arrives too late (grace already expired) — goes to current (12:01) buffer
        collector.onEvent(bufWithTimestamp("toolate", 2025, 4, 1, 12, 0, 55), 3, false);

        // No second upload yet
        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));

        // Trigger rotation into 12:02 and expire the grace period so 12:01 is uploaded
        collector.onEvent(bufWithTimestamp("next", 2025, 4, 1, 12, 2, 6), 4, false);

        verify(s3Client, times(2)).putObject(any(Consumer.class), any(RequestBody.class));

        // The 12:00 file should NOT contain the too-late event
        String decompressed0 = decompressZstd(capturedData.get(0));
        assertTrue(decompressed0.contains("early"));
        assertFalse(decompressed0.contains("toolate"), "Too-late event should not be in the 12:00 file");
    }

    private List<byte[]> captureUploads() {
        List<byte[]> uploads = new ArrayList<>();
        when(s3Client.putObject(ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    uploads.add(body.contentStreamProvider().newStream().readAllBytes());
                    return PutObjectResponse.builder().build();
                });
        return uploads;
    }

    private MarketDataCollector collector(Duration gracePeriod) {
        return new MarketDataCollector(
                new NullLogger(),
                clock,
                new CycleUploader(new NullLogger(), s3Client, Runnable::run),
                new DirectBufferPool(),
                STREAM_ID,
                RecordType.MBP_10,
                OUTPUT_BUCKET,
                gracePeriod);
    }

    private StreamRecord bufWithTimestamp(
            String input, int year, int month, int day, int hour, int minute, int second) {
        Instant instant =
                LocalDateTime.of(year, month, day, hour, minute, second).toInstant(UTC);
        long nanos = instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
        return new DummySchema(input, nanos);
    }

    private String decompressZstd(byte[] compressed) {
        try (ZstdInputStream zstdStream = new ZstdInputStream(new ByteArrayInputStream(compressed))) {
            return new String(zstdStream.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void date(int year, int month, int day, int hour, int minute, int second) {
        when(clock.instant())
                .thenReturn(
                        LocalDateTime.of(year, month, day, hour, minute, second).toInstant(UTC));
    }

    private static class DummySchema extends StreamRecord {
        private final int blockLength;

        public DummySchema(String input, long eventTimestamp) {
            this(input.getBytes(), eventTimestamp);
        }

        private DummySchema(byte[] input, long eventTimestamp) {
            super(RecordType.MBP_10);
            this.blockLength = StreamHeader.LENGTH + input.length;
            this.buffer.wrap(new byte[SbeMessage.HEADER_LENGTH + this.blockLength]);
            this.buffer.putLong(
                    SbeMessage.HEADER_LENGTH + StreamHeader.TIMESTAMP_EVENT_OFFSET, eventTimestamp, Statics.BYTE_ORDER);
            this.buffer.putBytes(SbeMessage.HEADER_LENGTH + StreamHeader.LENGTH, input);
        }

        @Override
        protected int getEncodedBlockLength() {
            return blockLength;
        }

        @Override
        protected void wrapCodecs(MutableDirectBuffer mutableDirectBuffer) {}

        @Override
        protected int getSbeVersion() {
            return 0;
        }
    }
}
