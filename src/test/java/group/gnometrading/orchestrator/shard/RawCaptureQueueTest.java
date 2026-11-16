package group.gnometrading.orchestrator.shard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.luben.zstd.ZstdInputStream;
import com.sun.management.ThreadMXBean;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * The reader's side of raw capture and the agent writing it, together, through a real {@link RawDataCollector} and a
 * hand-written S3 client that keeps what it is given (a Mockito mock's invocations allocate).
 */
class RawCaptureQueueTest {

    private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);
    private static final long START = TimeUnit.DAYS.toNanos(20_000);
    private static final int SMALL_RING = 64 << 10;
    private static final int SMALL_FRAME = 1 << 10;

    private final Keeping s3 = new Keeping();
    private final Recording log = new Recording();
    private final List<GnomeAgentRunner> runners = new ArrayList<>();

    @AfterEach
    void stop() throws Exception {
        for (GnomeAgentRunner runner : runners) {
            runner.close();
        }
    }

    private RawCaptureAgent agent(int ringBytes, long maxWaitNanos) {
        return new RawCaptureAgent(log, System::nanoTime, ringBytes, maxWaitNanos);
    }

    private RawDataCollector collector(String connection, Logger collectorLog) {
        return new RawDataCollector(
                collectorLog,
                Clock.fixed(Instant.ofEpochSecond(0, START), ZoneOffset.UTC),
                new CycleUploader(log, s3, Runnable::run),
                new DirectBufferPool(),
                "KALSHI",
                "shard-1",
                connection,
                "bucket");
    }

    private RawCaptureQueue open(RawCaptureAgent agent, String connection) {
        return agent.open(connection, collector(connection, log), SMALL_FRAME);
    }

    private void run(RawCaptureAgent agent) {
        GnomeAgentRunner runner = new GnomeAgentRunner(agent, error -> {
            throw new AssertionError(error);
        });
        runners.add(runner);
        GnomeAgentRunner.startOnThread(runner);
    }

    private static byte[] payload(Random random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    /** What the file holds for these frames: each one's header, then its payload. */
    private static byte[] expected(List<Long> times, List<byte[]> payloads) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < times.size(); i++) {
            ByteBuffer header =
                    ByteBuffer.allocate(RawDataCollector.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            header.putLong(times.get(i)).putInt(payloads.get(i).length);
            out.writeBytes(header.array());
            out.writeBytes(payloads.get(i));
        }
        return out.toByteArray();
    }

    @Test
    void framesReachTheFilesByteForByteSplitByMinuteWhateverTheirSize() {
        RawCaptureAgent agent = agent(SMALL_RING, TimeUnit.SECONDS.toNanos(5));
        // The ring is sized from the venue's largest frame: here 4 times one ring message, so big ones go in pieces.
        RawCaptureQueue queue = agent.open("KALSHI-0", collector("KALSHI-0", log), 4 * SMALL_RING);
        run(agent);
        Random random = new Random(7);
        List<List<Long>> times = List.of(new ArrayList<>(), new ArrayList<>());
        List<List<byte[]>> payloads = List.of(new ArrayList<>(), new ArrayList<>());
        for (int i = 0; i < 400; i++) {
            int minute = i < 200 ? 0 : 1;
            int length = i % 50 == 0 ? 20_000 + random.nextInt(200_000) : 1 + random.nextInt(600);
            byte[] bytes = payload(random, length);
            long at = START + minute * MINUTE + i * 1_000_000L;
            // Offset into a larger direct buffer, as a reader's frame is.
            ByteBuffer frame = ByteBuffer.allocateDirect(length + 5);
            frame.position(5);
            frame.put(bytes).flip().position(5);
            queue.capture(at, frame);
            assertEquals(5, frame.position(), "the gateway parses the frame from the same position next");
            times.get(minute).add(at);
            payloads.get(minute).add(bytes);
        }

        queue.flush();

        assertEquals(0, queue.drops());
        List<byte[]> files = s3.files();
        assertEquals(2, files.size());
        assertArrayEquals(expected(times.get(0), payloads.get(0)), files.get(0));
        assertArrayEquals(expected(times.get(1), payloads.get(1)), files.get(1));
    }

    @Test
    void aFullRingWaitsThenDropsThenShedsWithoutWaitingUntilHalfFree() {
        RawCaptureAgent agent = agent(SMALL_RING, TimeUnit.MILLISECONDS.toNanos(1));
        RawCaptureQueue queue = open(agent, "KALSHI-0");
        agent.doWork();
        ByteBuffer frame = ByteBuffer.allocateDirect(SMALL_FRAME);

        int captured = 0;
        while (queue.drops() == 0) {
            frame.clear();
            queue.capture(START + captured, frame);
            captured++;
        }
        assertEquals(1, queue.waits(), "only the frame that found no room waited");
        frame.clear();
        queue.capture(START + captured, frame);
        assertEquals(1, queue.waits(), "while shedding, frames drop without waiting");
        assertEquals(2, queue.drops());

        int drained;
        do {
            drained = agent.doWork();
        } while (drained > 0);
        frame.clear();
        queue.capture(START + captured + 1, frame);
        assertEquals(2, queue.drops(), "with room again, frames go through");

        run(agent);
        queue.flush();
        assertEquals(1, s3.files().size());
    }

    @Test
    void aStoppedAgentMakesReadersDropAtOnceAndFlushWritesWhatWasQueued() {
        RawCaptureAgent agent = agent(SMALL_RING, TimeUnit.SECONDS.toNanos(5));
        RawCaptureQueue queue = open(agent, "KALSHI-0");
        byte[] queued = "queued before the stop".getBytes();
        queue.capture(START, ByteBuffer.wrap(queued));

        agent.onClose();
        long before = System.nanoTime();
        queue.capture(START + 1, ByteBuffer.wrap("after".getBytes()));
        assertTrue(System.nanoTime() - before < TimeUnit.MILLISECONDS.toNanos(50), "no wait for a stopped agent");
        assertEquals(1, queue.drops());

        queue.flush();
        assertArrayEquals(expected(List.of(START), List.of(queued)), s3.files().get(0));
    }

    @Test
    void aConnectionWhoseWritingFailsIsGivenUpAloneAndLogged() {
        RawCaptureAgent agent = agent(SMALL_RING, TimeUnit.SECONDS.toNanos(5));
        Recording failing = new Recording();
        failing.failOn = "Switching cycle";
        RawCaptureQueue bad = agent.open("KALSHI-0", collector("KALSHI-0", failing), SMALL_FRAME);
        RawCaptureQueue good = open(agent, "KALSHI-1");
        byte[] payload = "frame".getBytes();
        for (RawCaptureQueue queue : List.of(bad, good)) {
            queue.capture(START, ByteBuffer.wrap(payload));
            queue.capture(START + MINUTE, ByteBuffer.wrap(payload));
        }

        agent.doWork();

        assertTrue(
                log.lines.stream().anyMatch(line -> line.contains("Raw capture failed on KALSHI-0")),
                log.lines.toString());
        bad.capture(START + MINUTE + 1, ByteBuffer.wrap(payload));
        assertEquals(1, bad.drops(), "a given-up connection's reader drops");
        good.capture(START + MINUTE + 1, ByteBuffer.wrap(payload));
        assertEquals(0, good.drops());
        bad.flush();
        run(agent);
        good.flush();
        assertTrue(s3.keys().stream().anyMatch(key -> key.contains("/KALSHI-1/")));
    }

    @Test
    void reportsFramesWaitsAndDropsAndLogsDropsOnce() {
        long[] now = {1};
        RawCaptureAgent agent = new RawCaptureAgent(log, () -> now[0], SMALL_RING, TimeUnit.MICROSECONDS.toNanos(100));
        RawCaptureQueue queue = open(agent, "KALSHI-0");
        agent.doWork();
        ByteBuffer frame = ByteBuffer.allocateDirect(SMALL_FRAME);
        while (queue.drops() < 3) {
            frame.clear();
            queue.capture(START, frame);
        }

        now[0] += RawCaptureAgent.REPORT_EVERY_NANOS;
        agent.doWork();
        now[0] += RawCaptureAgent.REPORT_EVERY_NANOS;
        agent.doWork();

        Map<String, Long> counters = new TreeMap<>();
        for (int i = 0; i < agent.counters().count(); i++) {
            counters.put(agent.counters().name(i), agent.counters().get(i));
        }
        assertEquals(1L, counters.get("connections"));
        assertEquals(3L, counters.get("drops"));
        assertEquals(1L, counters.get("waits"));
        assertTrue(counters.get("frames") > 0);
        assertEquals(
                1,
                log.lines.stream()
                        .filter(line -> line.contains("Raw capture dropped 3 frames from KALSHI-0"))
                        .count(),
                log.lines.toString());
    }

    @Test
    void capturingAllocatesNothingOnTheReaderForSmallOrSplitFrames() {
        RawCaptureAgent agent = agent(16 << 20, TimeUnit.SECONDS.toNanos(5));
        RawCaptureQueue queue = agent.open("KALSHI-0", collector("KALSHI-0", log), SMALL_FRAME);
        ByteBuffer small = ByteBuffer.allocateDirect(200);
        // Over one ring message (2 MiB at 16 MiB), so it goes in pieces.
        ByteBuffer big = ByteBuffer.allocateDirect(3 << 20);
        for (int i = 0; i < 1_000; i++) {
            queue.capture(START + i, small.clear());
        }

        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long before = threads.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 10_000; i++) {
            queue.capture(START + 1_000 + i, small.clear());
        }
        queue.capture(START + 20_000, big.clear());
        long allocated = threads.getThreadAllocatedBytes(thread) - before;

        assertEquals(0, queue.drops());
        assertTrue(allocated < 1_000, "bytes allocated by 10,001 captures: " + allocated);
    }

    /** Keeps each upload's decompressed bytes, by key, in the order uploaded. */
    private static final class Keeping implements S3Client {
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();
        private final List<String> order = new CopyOnWriteArrayList<>();

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {}

        @Override
        public PutObjectResponse putObject(final PutObjectRequest request, final RequestBody body) {
            try (ZstdInputStream zstd = new ZstdInputStream(new ByteArrayInputStream(
                    body.contentStreamProvider().newStream().readAllBytes()))) {
                files.put(request.key(), zstd.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            order.add(request.key());
            return PutObjectResponse.builder().build();
        }

        List<byte[]> files() {
            return order.stream().map(files::get).toList();
        }

        List<String> keys() {
            return List.copyOf(order);
        }
    }

    /** Keeps formatted lines; throws on one containing {@link #failOn}, to make a collector fail. */
    private static final class Recording implements Logger {
        final List<String> lines = new CopyOnWriteArrayList<>();
        String failOn;

        @Override
        public void logf(final LogMessage msg, final String format, final Object... args) {
            final String line = String.format(format, args);
            if (failOn != null && line.contains(failOn)) {
                throw new IllegalStateException("failing on purpose");
            }
            lines.add(line);
        }

        @Override
        public void log(final LogMessage msg) {}

        @Override
        public void log(final LogMessage msg, final long v1) {}

        @Override
        public void log(final LogMessage msg, final long v1, final long v2) {}

        @Override
        public void log(final LogMessage msg, final long v1, final long v2, final long v3) {}

        @Override
        public void log(final LogMessage msg, final long v1, final long v2, final long v3, final long v4) {}

        @Override
        public void log(
                final LogMessage msg, final long v1, final long v2, final long v3, final long v4, final long v5) {}

        @Override
        public void log(
                final LogMessage msg,
                final long v1,
                final long v2,
                final long v3,
                final long v4,
                final long v5,
                final long v6) {}
    }
}
