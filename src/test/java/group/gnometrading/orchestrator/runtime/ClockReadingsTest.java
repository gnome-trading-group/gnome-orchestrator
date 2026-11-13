package group.gnometrading.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.management.ThreadMXBean;
import group.gnometrading.orchestrator.runtime.ClockReadings.Source;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClockReadingsTest {

    private static final long WRITTEN_MS = 1_794_527_694_793L;

    @TempDir
    Path dir;

    private long now = WRITTEN_MS + 4_000;

    private ClockReadings readings(final Path file) {
        return new ClockReadings(file, () -> now);
    }

    private static String status(final String source, final long offset, final long bound, final long phcBound) {
        return "written_ms " + WRITTEN_MS + "\nsource " + source + "\nstratum 1\noffset_ns " + offset
                + "\nerror_bound_ns " + bound + "\nphc_error_bound_ns " + phcBound + "\n";
    }

    // The writer rewrites the file in place, so the reader's open handle sees each new content.
    private static void rewrite(final Path file, final String content) throws Exception {
        Files.writeString(
                file,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    @Test
    void readsTheHostsSourceBoundOffsetAndAge() throws Exception {
        final Path file = dir.resolve("clock.status");
        rewrite(file, status("PHC", -5, 23_617, 18_372));
        try (ClockReadings clock = readings(file)) {
            clock.sample();
            assertEquals(Source.PHC, clock.source());
            assertEquals(23_617, clock.errorBoundNanos());
            assertEquals(-5, clock.offsetNanos());
            assertEquals(18_372, clock.phcErrorBoundNanos());
            assertEquals(1, clock.stratum());
            assertEquals(4_000, clock.ageMs());
        }
    }

    @Test
    void isUnknownUntilTheFileAppearsThenFollowsEachRewrite() throws Exception {
        final Path file = dir.resolve("clock.status");
        try (ClockReadings clock = readings(file)) {
            clock.sample();
            assertEquals(Source.UNKNOWN, clock.source());
            assertEquals(ClockReadings.UNAVAILABLE, clock.errorBoundNanos());
            assertEquals(ClockReadings.UNAVAILABLE, clock.ageMs());

            rewrite(file, status("NTP", 384, 100_000, -1));
            clock.sample();
            assertEquals(Source.NTP, clock.source());
            assertEquals(100_000, clock.errorBoundNanos());

            rewrite(file, status("UNSYNCED", 0, -1, -1));
            clock.sample();
            assertEquals(Source.UNSYNCED, clock.source());
            assertEquals(ClockReadings.UNAVAILABLE, clock.errorBoundNanos());
        }
    }

    @Test
    void keepsTheLastWholeReadingWhenTheFileIsCaughtMidRewrite() throws Exception {
        final Path file = dir.resolve("clock.status");
        rewrite(file, status("PHC", -5, 23_617, 18_372));
        try (ClockReadings clock = readings(file)) {
            clock.sample();
            for (final String torn : new String[] {
                "",
                "written_ms 1794527694793\nsource PH",
                status("PHC", -5, 23_617, 18_372).replace("stratum 1\n", ""),
                status("GPS", 0, 1, -1),
                status("PHC", -5, 23_617, 18_372).replace("offset_ns -5", "offset_ns x")
            }) {
                rewrite(file, torn);
                now += 1_000;
                clock.sample();
                assertEquals(Source.PHC, clock.source(), torn);
                assertEquals(23_617, clock.errorBoundNanos(), torn);
            }
            assertEquals(9_000, clock.ageMs());
        }
    }

    @Test
    void samplingAllocatesNothingOnceOpen() throws Exception {
        final Path file = dir.resolve("clock.status");
        rewrite(file, status("PHC", -5, 23_617, 18_372));
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        try (ClockReadings clock = readings(file)) {
            for (int i = 0; i < 20_000; i++) {
                clock.sample();
            }
            final long threadId = Thread.currentThread().getId();
            final long before = threads.getThreadAllocatedBytes(threadId);
            for (int i = 0; i < 10_000; i++) {
                clock.sample();
            }
            assertEquals(0, threads.getThreadAllocatedBytes(threadId) - before);
        }
    }
}
