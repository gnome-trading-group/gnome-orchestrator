package group.gnometrading.orchestrator.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.latency.MinuteHistograms;
import group.gnometrading.latency.MinuteSummary;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.utils.SampledEpochNanoClock;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ShardLatencyTest {

    private static final long VENUE_LAG = TimeUnit.MILLISECONDS.toNanos(30);
    private static final long READER_TIME = TimeUnit.MILLISECONDS.toNanos(2);

    private final StreamTestKit kit = new StreamTestKit();
    private final ShardLatency latency = StreamTestKit.latency();
    private final ShardStream stream = kit.publishingStream(10, latency);

    private void record(final long eventNanos, final boolean snapshot, final boolean badData) {
        final long recv = SampledEpochNanoClock.INSTANCE.nanoTime() - READER_TIME;
        final Mbp10Schema slot = stream.ring().claim();
        final Mbp10Encoder encoder = slot.encoder;
        encoder.streamId(stream.streamId());
        encoder.timestampEvent(eventNanos == 0 ? recv - VENUE_LAG : eventNanos);
        encoder.timestampRecv(recv);
        encoder.flags().clear().marketByPrice(true).snapshot(snapshot).badData(badData);
        stream.ring().publish();
    }

    private MinuteSummary collectorMinute() throws Exception {
        stream.poll(System.nanoTime());
        latency.rollCollector(SampledEpochNanoClock.INSTANCE.nanoTime() + 2 * MinuteHistograms.MINUTE_NANOS);
        final MinuteHistograms collector = latency.sets().get(0);
        final MinuteSummary summary = new MinuteSummary(collector.capacity());
        assertTrue(collector.summarize(summary));
        return summary;
    }

    private static int key(final MinuteHistograms set, final String metric, final String source) {
        for (int key = 0; key < set.keys(); key++) {
            if (set.label(key).metric().equals(metric)
                    && set.label(key).exchange().equals(source)) {
                return key;
            }
        }
        throw new AssertionError("no key " + metric + " " + source);
    }

    @Test
    void venueTimeCountsOnlyRecordsBringingANewVenueEvent() throws Exception {
        record(0, true, false);
        final long first = SampledEpochNanoClock.INSTANCE.nanoTime() - READER_TIME - VENUE_LAG;
        record(0, false, false);
        record(first, true, false);
        record(0, true, false);
        record(0, false, true);

        final MinuteSummary minute = collectorMinute();
        final MinuteHistograms collector = latency.sets().get(0);
        final int venue = key(collector, "shard.venue_to_recv", StreamTestKit.SOURCE_CODE);
        assertEquals(
                2,
                minute.count(venue),
                "the update and the snapshot that advanced; not the first snapshot, a republish or bad data");
        assertTrue(minute.p50(venue) >= VENUE_LAG, "receipt minus venue time: " + minute.p50(venue));
        assertEquals(0, minute.count(key(collector, "shard.venue_to_recv", "")), "keyed by the stream's source");
    }

    @Test
    void everyRecordCountsTheReadersTimeOnItFromReceiptToRing() throws Exception {
        record(0, true, false);
        record(0, false, false);
        record(0, false, true);

        final MinuteSummary minute = collectorMinute();
        final int decode = key(latency.sets().get(0), "shard.decode", StreamTestKit.SOURCE_CODE);
        assertEquals(3, minute.count(decode));
        assertTrue(
                minute.p50(decode) >= READER_TIME && minute.max(decode) < TimeUnit.SECONDS.toNanos(1),
                "ring stamp minus receipt: " + minute.p50(decode));
    }
}
