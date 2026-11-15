package group.gnometrading.orchestrator.shard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.shard.feeds.Assignment;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StandaloneShardTest {

    @Test
    void streamListIsReadLeniently() {
        assertArrayEquals(new int[] {3, 1, 2}, StandaloneShard.streamIds(" 3, 1,2 ,"));
        assertThrows(IllegalArgumentException.class, () -> StandaloneShard.streamIds(" , "));
    }

    @Test
    void everyStreamIsReadAndPublishedAndEverySourceSignsInWithTheAccount() throws Exception {
        final Assignment.Streams streams = StandaloneShard.fixedAssignment(
                        new int[] {7, 9}, List.of("KALSHI", "POLYMARKET_US"), "md-2")
                .read();

        assertArrayEquals(new int[] {7, 9}, streams.streamIds());
        assertArrayEquals(new int[] {7, 9}, streams.publish(), "a probe can only read published streams");
        assertEquals(Map.of("KALSHI", "md-2", "POLYMARKET_US", "md-2"), streams.accounts());
        assertEquals(Long.MIN_VALUE, streams.writeFromNanos(7));
        assertEquals(Long.MAX_VALUE, streams.writeUntilNanos(9));
    }

    @Test
    void uploadsSucceedWithoutGoingAnywhere() throws Exception {
        final CycleUploader uploader =
                new CycleUploader(new NullLogger(), new StandaloneShard.DiscardingS3(), Runnable::run);
        final CycleBuffer cycle =
                new CycleBuffer(new DirectBufferPool(), LocalDateTime.of(2026, 11, 15, 12, 0), "raw/KALSHI/x.zst");
        CycleBufferTest.write(cycle, "frames".getBytes());

        uploader.upload(cycle, "standalone-discarded");

        assertEquals(0, uploader.failedUploads());
    }
}
