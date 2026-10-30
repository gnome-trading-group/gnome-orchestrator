package group.gnometrading.utils;

import java.util.Arrays;

/**
 * Non-negative durations in microseconds, counted in log-linear buckets: 8 per power of two, so a reported value is
 * within 12.5% of the true one. Fixed size; recording and reading allocate nothing. One thread at a time.
 */
public final class LatencyHistogram {

    private static final int SUB_BUCKETS = 8;
    private static final int SUB_BUCKET_BITS = 3;
    private static final int BUCKETS = 240;

    private final long[] counts = new long[BUCKETS];
    private long count;
    private long maxMicros;

    /** Negative values count as 0. */
    public void record(final long micros) {
        final long value = Math.max(0, micros);
        counts[bucketOf(value)]++;
        count++;
        maxMicros = Math.max(maxMicros, value);
    }

    public long count() {
        return count;
    }

    public long maxMicros() {
        return maxMicros;
    }

    /** The upper edge of the bucket holding the quantile, capped at the largest value seen; 0 when empty. */
    public long percentileMicros(final double quantile) {
        return count == 0 ? 0 : atRank((long) Math.ceil(quantile * count));
    }

    /** The upper edge of the bucket holding the {@code rank}-th smallest value (from 1), capped at the largest seen. */
    public long atRank(final long rank) {
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += counts[i];
            if (seen >= rank) {
                return Math.min(upperMicros(i), maxMicros);
            }
        }
        return maxMicros;
    }

    public void reset() {
        Arrays.fill(counts, 0);
        count = 0;
        maxMicros = 0;
    }

    static int bucketOf(final long micros) {
        if (micros < SUB_BUCKETS) {
            return (int) micros;
        }
        final int exponent = 63 - Long.numberOfLeadingZeros(micros);
        final int sub = (int) (micros >> (exponent - SUB_BUCKET_BITS)) & (SUB_BUCKETS - 1);
        return Math.min(BUCKETS - 1, (exponent - SUB_BUCKET_BITS + 1) * SUB_BUCKETS + sub);
    }

    static long upperMicros(final int bucket) {
        if (bucket < SUB_BUCKETS) {
            return bucket + 1;
        }
        final int exponent = bucket / SUB_BUCKETS + SUB_BUCKET_BITS - 1;
        final int sub = bucket % SUB_BUCKETS;
        return (long) (SUB_BUCKETS + sub + 1) << (exponent - SUB_BUCKET_BITS);
    }
}
