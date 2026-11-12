package group.gnometrading.orchestrator.shard;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.luben.zstd.ZstdInputStream;
import group.gnometrading.logging.NullLogger;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
class CycleManagerTest {

    private static final String BUCKET = "test-bucket";
    private static final LocalDateTime MINUTE_0 = LocalDateTime.of(2025, 4, 1, 12, 0);
    private static final LocalDateTime MINUTE_1 = LocalDateTime.of(2025, 4, 1, 12, 1);
    private static final LocalDateTime MINUTE_2 = LocalDateTime.of(2025, 4, 1, 12, 2);

    @Mock
    S3Client s3Client;

    @BeforeEach
    void setup() {
        lenient()
                .when(s3Client.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
    }

    private CycleManager manager(Duration gracePeriod) {
        return new CycleManager(
                new NullLogger(),
                new CycleUploader(new NullLogger(), s3Client, Runnable::run),
                new DirectBufferPool(),
                BUCKET,
                ts -> ts + ".zst",
                ZoneOffset.UTC,
                Duration.ofMinutes(1),
                nanos(MINUTE_0),
                gracePeriod);
    }

    @Test
    void testDoubleRotationForceUploadsPrevious() throws IOException {
        CycleManager manager = manager(Duration.ofMinutes(5));
        CycleBufferTest.write(manager.currentCycle(), "minute0".getBytes());

        manager.rotate(nanos(MINUTE_1));
        verify(s3Client, never()).putObject(any(Consumer.class), any(RequestBody.class));

        manager.rotate(nanos(MINUTE_2));
        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void testGracePeriodPreventsImmediateUpload() throws IOException {
        CycleManager manager = manager(Duration.ofSeconds(10));

        manager.rotate(nanos(MINUTE_1));
        manager.maybeExpireGracePeriod(nanos(MINUTE_1.plusSeconds(5)));

        verify(s3Client, never()).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void testGracePeriodExpiryTriggersUpload() throws IOException {
        CycleManager manager = manager(Duration.ofSeconds(10));
        CycleBufferTest.write(manager.currentCycle(), "minute0".getBytes());

        manager.rotate(nanos(MINUTE_1));
        manager.maybeExpireGracePeriod(nanos(MINUTE_1.plusSeconds(10)));

        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void testResolveTargetRoutesToPreviousBuffer() throws IOException, Exception {
        List<byte[]> uploaded = new ArrayList<>();
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class))).thenAnswer(invocation -> {
            RequestBody body = invocation.getArgument(1);
            uploaded.add(body.contentStreamProvider().newStream().readAllBytes());
            return PutObjectResponse.builder().build();
        });

        CycleManager manager = manager(Duration.ofSeconds(30));
        CycleBufferTest.write(manager.currentCycle(), "minute0".getBytes());

        manager.rotate(nanos(MINUTE_1));
        CycleBufferTest.write(manager.resolveTarget(nanos(MINUTE_0)), "late".getBytes());

        manager.rotate(nanos(MINUTE_2));

        assertEquals(1, uploaded.size());
        try (ZstdInputStream zstd = new ZstdInputStream(new ByteArrayInputStream(uploaded.get(0)))) {
            String content = new String(zstd.readAllBytes());
            assertTrue(content.contains("minute0"));
            assertTrue(content.contains("late"));
        }
    }

    @Test
    void testResolveTargetFallsBackToCurrentWhenNoPrevious() throws IOException {
        List<byte[]> uploaded = new ArrayList<>();
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class))).thenAnswer(invocation -> {
            RequestBody body = invocation.getArgument(1);
            uploaded.add(body.contentStreamProvider().newStream().readAllBytes());
            return PutObjectResponse.builder().build();
        });

        CycleManager manager = manager(Duration.ZERO);
        CycleBufferTest.write(manager.resolveTarget(nanos(MINUTE_0)), "data".getBytes());

        manager.rotate(nanos(MINUTE_1));
        manager.maybeExpireGracePeriod(nanos(MINUTE_1));

        assertEquals(1, uploaded.size());
        try (ZstdInputStream zstd = new ZstdInputStream(new ByteArrayInputStream(uploaded.get(0)))) {
            assertTrue(new String(zstd.readAllBytes()).contains("data"));
        }
    }

    @Test
    void flushUploadsThePreviousAndThePartialCurrentCycleThenCloses() throws Exception {
        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> keyCaptor = ArgumentCaptor.forClass(Consumer.class);
        when(s3Client.putObject(keyCaptor.capture(), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        CycleManager manager = manager(Duration.ofMinutes(5));
        CycleBufferTest.write(manager.currentCycle(), "min0".getBytes());
        manager.rotate(nanos(MINUTE_1));
        CycleBufferTest.write(manager.currentCycle(), "min1".getBytes());

        manager.flush();

        verify(s3Client, times(2)).putObject(any(Consumer.class), any(RequestBody.class));
        List<String> keys = new ArrayList<>();
        for (Consumer<PutObjectRequest.Builder> key : keyCaptor.getAllValues()) {
            PutObjectRequest.Builder builder = PutObjectRequest.builder();
            key.accept(builder);
            keys.add(builder.build().key());
        }
        assertEquals(List.of(MINUTE_0 + ".zst", MINUTE_1 + ".zst"), keys);
        assertTrue(manager.isClosed());
    }

    @Test
    void aSecondFlushDoesNothing() throws IOException {
        CycleManager manager = manager(Duration.ZERO);
        CycleBufferTest.write(manager.currentCycle(), "data".getBytes());
        manager.flush();
        manager.flush();

        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void testCurrentTimestampReflectsActiveBuffer() throws IOException {
        CycleManager manager = manager(Duration.ZERO);
        assertEquals(nanos(MINUTE_0), manager.currentStartNanos());

        manager.rotate(nanos(MINUTE_1));
        assertEquals(nanos(MINUTE_1), manager.currentStartNanos());
    }

    private static long nanos(LocalDateTime time) {
        return CycleManager.epochNanos(time.toInstant(ZoneOffset.UTC));
    }

    private List<String> capturingKeys() {
        List<String> keys = new ArrayList<>();
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class))).thenAnswer(invocation -> {
            Consumer<PutObjectRequest.Builder> request = invocation.getArgument(0);
            PutObjectRequest.Builder builder = PutObjectRequest.builder();
            request.accept(builder);
            keys.add(builder.build().key());
            return PutObjectResponse.builder().build();
        });
        return keys;
    }

    @Test
    void aShardTakingOverWritesNothingBeforeTheCut() throws IOException {
        List<String> keys = capturingKeys();
        CycleManager manager = manager(Duration.ZERO);
        manager.writeFrom(Long.MAX_VALUE);
        CycleBufferTest.write(manager.currentCycle(), "minute0".getBytes());

        manager.rotate(nanos(MINUTE_1));
        CycleBufferTest.write(manager.currentCycle(), "minute1".getBytes());
        manager.maybeExpireGracePeriod(nanos(MINUTE_1));
        manager.writeFrom(nanos(MINUTE_2));
        manager.rotate(nanos(MINUTE_2));
        CycleBufferTest.write(manager.currentCycle(), "minute2".getBytes());
        manager.maybeExpireGracePeriod(nanos(MINUTE_2));
        manager.flush();

        assertEquals(List.of(MINUTE_2 + ".zst"), keys, "warming wrote nothing; from the cut on, every minute");
    }

    @Test
    void aShardHandingOffWritesUpToTheCutAndNotThePartialMinuteAfterIt() throws IOException {
        List<String> keys = capturingKeys();
        CycleManager manager = manager(Duration.ZERO);
        manager.writeUntil(nanos(MINUTE_1));
        CycleBufferTest.write(manager.currentCycle(), "minute0".getBytes());

        manager.rotate(nanos(MINUTE_1));
        CycleBufferTest.write(manager.currentCycle(), "minute1".getBytes());
        manager.maybeExpireGracePeriod(nanos(MINUTE_1));
        manager.flush();

        assertEquals(List.of(MINUTE_0 + ".zst"), keys);
    }

    @Test
    void aQuietMinuteIsWrittenOnceTheWallClockPassesItsEndAndGrace() throws Exception {
        List<String> keys = capturingKeys();
        CycleManager manager = manager(Duration.ofSeconds(5));
        CycleBufferTest.write(manager.currentCycle(), "snapshot".getBytes());

        manager.expire(nanos(MINUTE_1.plusSeconds(4)));
        assertEquals(List.of(), keys, "still within the grace");

        manager.expire(nanos(MINUTE_1.plusSeconds(5)));
        assertEquals(List.of(MINUTE_0 + ".zst"), keys);
        assertNull(manager.currentCycle(), "nothing open until a record comes");
    }

    @Test
    void aMinuteWithNoRecordsIsNeverWritten() {
        CycleManager manager = manager(Duration.ofSeconds(5));

        manager.expire(nanos(MINUTE_2));
        manager.flush();

        verify(s3Client, never()).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void aRecordForAMinuteAlreadyWrittenGoesIntoTheNextFile() throws Exception {
        List<String> keys = capturingKeys();
        CycleManager manager = manager(Duration.ofSeconds(5));
        CycleBufferTest.write(manager.currentCycle(), "first".getBytes());
        manager.expire(nanos(MINUTE_1.plusSeconds(10)));

        long late = nanos(MINUTE_0.plusSeconds(59));
        assertTrue(manager.isPastCurrentCycle(late));
        manager.rotate(manager.cycleStart(late));
        CycleBufferTest.write(manager.resolveTarget(manager.cycleStart(late)), "late".getBytes());
        manager.flush();

        assertEquals(List.of(MINUTE_0 + ".zst", MINUTE_1 + ".zst"), keys);
    }
}
