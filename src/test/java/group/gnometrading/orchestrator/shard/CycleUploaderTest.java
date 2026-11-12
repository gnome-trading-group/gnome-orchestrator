package group.gnometrading.orchestrator.shard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import group.gnometrading.logging.NullLogger;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

@ExtendWith(MockitoExtension.class)
class CycleUploaderTest {

    private static final String BUCKET = "test-bucket";
    private static final LocalDateTime MINUTE_0 = LocalDateTime.of(2025, 4, 1, 12, 0);
    private static final LocalDateTime MINUTE_1 = LocalDateTime.of(2025, 4, 1, 12, 1);
    private static final LocalDateTime MINUTE_2 = LocalDateTime.of(2025, 4, 1, 12, 2);

    @Mock
    S3Client s3Client;

    private static CycleBuffer cycleWithData(String key) throws IOException {
        CycleBuffer cycle = new CycleBuffer(new DirectBufferPool(), MINUTE_0, key);
        CycleBufferTest.write(cycle, "data".getBytes());
        return cycle;
    }

    @Test
    void rotationHandsTheFinishedCycleToTheUploaderInsteadOfUploadingOnTheCaller() throws IOException {
        List<Runnable> queued = new ArrayList<>();
        CycleUploader uploader = new CycleUploader(new NullLogger(), s3Client, queued::add);
        CycleManager manager = new CycleManager(
                new NullLogger(),
                uploader,
                new DirectBufferPool(),
                BUCKET,
                ts -> ts + ".zst",
                ZoneOffset.UTC,
                Duration.ofMinutes(1),
                nanos(MINUTE_0),
                Duration.ZERO);

        CycleBufferTest.write(manager.currentCycle(), "data".getBytes());
        manager.rotate(nanos(MINUTE_1));
        manager.rotate(nanos(MINUTE_2));

        verify(s3Client, never()).putObject(any(Consumer.class), any(RequestBody.class));
        assertEquals(1, queued.size());

        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        queued.get(0).run();
        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void aSlowUploadDoesNotBlockTheCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class))).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return PutObjectResponse.builder().build();
        });
        CycleUploader uploader = new CycleUploader(new NullLogger(), s3Client);

        uploader.upload(cycleWithData("slow.zst"), BUCKET);

        assertTrue(started.await(5, TimeUnit.SECONDS), "Upload should run on the uploader thread");
        release.countDown();
        uploader.close();
        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void closeDrainsQueuedUploads() throws Exception {
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        CycleUploader uploader = new CycleUploader(new NullLogger(), s3Client);

        for (int i = 0; i < 5; i++) {
            uploader.upload(cycleWithData(i + ".zst"), BUCKET);
        }
        uploader.close();

        verify(s3Client, times(5)).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void uploadsInlineOnceTheUploaderHasShutDown() throws Exception {
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        CycleUploader uploader = new CycleUploader(new NullLogger(), s3Client, task -> {
            throw new RejectedExecutionException();
        });

        uploader.upload(cycleWithData("late.zst"), BUCKET);

        verify(s3Client, times(1)).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void aFailedUploadIsLoggedNotThrown() throws Exception {
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("S3 down"));
        CycleUploader uploader = new CycleUploader(new NullLogger(), s3Client, Runnable::run);
        CycleBuffer cycle = cycleWithData("failed.zst");

        assertDoesNotThrow(() -> uploader.upload(cycle, BUCKET));
    }

    @Test
    void aFailedUploadIsRetriedWithBackoff() throws Exception {
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("S3 down"))
                .thenThrow(new IllegalStateException("S3 down"))
                .thenReturn(PutObjectResponse.builder().build());
        List<Long> sleeps = new ArrayList<>();
        CycleUploader uploader = new CycleUploader(new NullLogger(), s3Client, Runnable::run, () -> 0, sleeps::add, 1);

        uploader.upload(cycleWithData("flaky.zst"), BUCKET);

        verify(s3Client, times(3)).putObject(any(Consumer.class), any(RequestBody.class));
        assertEquals(List.of(CycleUploader.RETRY_BACKOFF_NANOS[0], CycleUploader.RETRY_BACKOFF_NANOS[1]), sleeps);
        assertEquals(0, uploader.failedUploads());
    }

    @Test
    void anUploadThatKeepsFailingIsDroppedAndCounted() throws Exception {
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("S3 down"));
        List<Long> sleeps = new ArrayList<>();
        CycleUploader uploader = new CycleUploader(new NullLogger(), s3Client, Runnable::run, () -> 0, sleeps::add, 1);

        uploader.upload(cycleWithData("lost.zst"), BUCKET);

        verify(s3Client, times(CycleUploader.RETRY_BACKOFF_NANOS.length + 1))
                .putObject(any(Consumer.class), any(RequestBody.class));
        assertEquals(CycleUploader.RETRY_BACKOFF_NANOS.length, sleeps.size());
        assertEquals(1, uploader.failedUploads());
    }

    @Test
    void anUploadThatNeverReturnsIsStuckOnceItOutlastsTheLimit() throws Exception {
        CountDownLatch inUpload = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class))).thenAnswer(invocation -> {
            inUpload.countDown();
            release.await();
            return PutObjectResponse.builder().build();
        });
        CycleUploader uploader = new CycleUploader(new NullLogger(), s3Client);
        assertFalse(uploader.isStuck(System.nanoTime(), 0), "idle is not stuck");

        uploader.upload(cycleWithData("hung.zst"), BUCKET);
        assertTrue(inUpload.await(5, TimeUnit.SECONDS));
        long later = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);

        assertTrue(uploader.isStuck(later, TimeUnit.MINUTES.toNanos(5)));
        assertFalse(uploader.isStuck(System.nanoTime(), TimeUnit.MINUTES.toNanos(5)));
        release.countDown();
        uploader.close();
        assertFalse(uploader.isStuck(later, TimeUnit.MINUTES.toNanos(5)));
    }

    @Test
    void uploadsAreSpacedOutWhileOthersAreQueued() throws Exception {
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        Deque<Runnable> queue = new ArrayDeque<>();
        List<Long> sleeps = new ArrayList<>();
        CycleUploader uploader = new CycleUploader(new NullLogger(), s3Client, queue::add, queue::size, sleeps::add, 1);

        for (int i = 0; i < 3; i++) {
            uploader.upload(cycleWithData(i + ".zst"), BUCKET);
        }
        while (!queue.isEmpty()) {
            queue.poll().run();
        }

        verify(s3Client, times(3)).putObject(any(Consumer.class), any(RequestBody.class));
        assertEquals(List.of(CycleUploader.MAX_SPACING_NANOS, CycleUploader.MAX_SPACING_NANOS), sleeps);
    }

    @Test
    void aLargeBacklogIsSpacedToDrainWithinTheWindow() throws Exception {
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        List<Runnable> tasks = new ArrayList<>();
        List<Long> sleeps = new ArrayList<>();
        CycleUploader uploader = new CycleUploader(new NullLogger(), s3Client, tasks::add, () -> 400, sleeps::add, 1);

        uploader.upload(cycleWithData("backlog.zst"), BUCKET);
        tasks.get(0).run();

        assertEquals(List.of(CycleUploader.DRAIN_WINDOW_NANOS / 400), sleeps);
    }

    @Test
    void aBacklogIsSharedAcrossEveryThread() throws Exception {
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        List<Runnable> tasks = new ArrayList<>();
        List<Long> sleeps = new ArrayList<>();
        CycleUploader uploader = new CycleUploader(new NullLogger(), s3Client, tasks::add, () -> 1_600, sleeps::add, 8);

        uploader.upload(cycleWithData("backlog.zst"), BUCKET);
        tasks.get(0).run();

        assertEquals(List.of(CycleUploader.DRAIN_WINDOW_NANOS * 8 / 1_600), sleeps);
    }

    @Test
    void stoppingUploadsAWholeShardsPartialMinutesWithinTheStopTimeout() throws Exception {
        AtomicInteger uploaded = new AtomicInteger();
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class))).thenAnswer(invocation -> {
            // About a cross-region put's round trip.
            Thread.sleep(100);
            uploaded.incrementAndGet();
            return PutObjectResponse.builder().build();
        });
        CycleUploader uploader = new CycleUploader(new NullLogger(), s3Client);
        for (int i = 0; i < 400; i++) {
            uploader.upload(cycleWithData(i + ".zst"), BUCKET);
        }

        long started = System.nanoTime();
        uploader.close();
        long tookSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);

        assertEquals(400, uploaded.get(), "nothing lost");
        assertTrue(tookSeconds < CycleUploader.SHUTDOWN_TIMEOUT_SECONDS / 3, "took " + tookSeconds + " s");
        assertEquals(0, uploader.failedUploads());
    }

    private static long nanos(LocalDateTime time) {
        return CycleManager.epochNanos(time.toInstant(ZoneOffset.UTC));
    }
}
