package group.gnometrading.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LatencyHistogramTest {

    @Test
    void everyValueFallsInsideItsBucket() {
        for (long micros = 0; micros < 5_000_000; micros += micros < 100 ? 1 : micros / 7) {
            int bucket = LatencyHistogram.bucketOf(micros);
            long upper = LatencyHistogram.upperMicros(bucket);
            long lower = bucket == 0 ? 0 : LatencyHistogram.upperMicros(bucket - 1);
            assertTrue(lower <= micros && micros < upper, micros + " not in [" + lower + ", " + upper + ")");
            assertTrue(upper - lower <= Math.max(1, lower / 8), "bucket " + bucket + " is wider than 12.5%");
        }
    }

    @Test
    void ranksCountFromTheSmallest() {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(3);
        histogram.record(400);
        histogram.record(9_000);

        assertEquals(4, histogram.atRank(1));
        assertEquals(416, histogram.atRank(2));
        assertEquals(9_000, histogram.atRank(3));
        assertEquals(416, histogram.percentileMicros(0.5));
    }

    @Test
    void resettingEmptiesIt() {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(70);
        histogram.reset();

        assertEquals(0, histogram.count());
        assertEquals(0, histogram.maxMicros());
        assertEquals(0, histogram.percentileMicros(0.99));
    }
}
