package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.logging.NullLogger;
import group.gnometrading.sequencer.JournalWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.agrona.concurrent.CachedEpochClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalSegmentSealerTest {

    private static final long SEGMENT_BYTES = 1 << 20;

    @TempDir
    Path tempDir;

    private final CachedEpochClock clock = new CachedEpochClock();
    private final JournalSegmentSealer sealer = new JournalSegmentSealer(SEGMENT_BYTES, clock, new NullLogger());
    private long sequence;

    /** Writes one record and rolls it into a segment for the sealer, a minute after the last. */
    private void segment(JournalWriter writer) {
        clock.advance(60_000);
        writer.append(0, ++sequence, 1, new UnsafeBuffer(new byte[0]), 0);
        writer.roll();
    }

    private JournalWriter writer() throws Exception {
        return new JournalWriter(tempDir, SEGMENT_BYTES, sealer::submit);
    }

    @Test
    void aSealedSegmentWaitsUntilTheShipperDeletesIt() throws Exception {
        JournalWriter writer = writer();
        segment(writer);
        segment(writer);
        segment(writer);
        writer.close();
        assertTrue(sealer.close(Duration.ofSeconds(5)));

        sealer.measureBacklog(clock.time() + 10_000);
        assertEquals(3, sealer.pendingSegments());
        assertEquals(130_000, sealer.oldestPendingMs(), "the first was sealed two minutes before the last");

        Files.delete(tempDir.resolve(JournalWriter.sealedFileName(0)));
        sealer.measureBacklog(clock.time() + 10_000);
        assertEquals(2, sealer.pendingSegments());
        assertEquals(70_000, sealer.oldestPendingMs());

        Files.delete(tempDir.resolve(JournalWriter.sealedFileName(1)));
        Files.delete(tempDir.resolve(JournalWriter.sealedFileName(2)));
        sealer.measureBacklog(clock.time() + 10_000);
        assertEquals(0, sealer.pendingSegments());
        assertEquals(0, sealer.oldestPendingMs());
    }

    @Test
    void nothingSealedIsNothingWaiting() {
        sealer.measureBacklog(clock.time());
        assertEquals(0, sealer.pendingSegments());
        assertEquals(0, sealer.oldestPendingMs());
    }

    @Test
    void theBacklogIsCountedOverTheLastSegmentsTracked() throws Exception {
        JournalWriter writer = writer();
        for (int i = 0; i < JournalSegmentSealer.TRACKED + 6; i++) {
            segment(writer);
        }
        writer.close();
        assertTrue(sealer.close(Duration.ofSeconds(5)));

        sealer.measureBacklog(clock.time());
        assertEquals(JournalSegmentSealer.TRACKED, sealer.pendingSegments());
    }
}
