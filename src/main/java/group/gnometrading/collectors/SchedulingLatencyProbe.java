package group.gnometrading.collectors;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Measures how late a thread that asked to wake up actually gets to run.
 *
 * <p>Socket readers stamp receive time when they get the CPU, not when the bytes arrive. Whatever
 * holds a runnable thread off the CPU (a used-up CPU quota, a busy core, a GC pause) delays every
 * receive timestamp by the same amount, and nothing inside the reader can see it. This thread
 * suffers the same delays, so its wake-up lateness is the in-process measure of them.
 *
 * <p>Lateness goes into log-linear buckets (8 per power of two microseconds, so values are
 * reported to within 12.5%) and is logged as one {@code SCHED_LATENCY} line per window.
 */
final class SchedulingLatencyProbe implements Runnable {

    private static final int SUB_BUCKETS = 8;
    private static final int SUB_BUCKET_BITS = 3;
    private static final int BUCKETS = 240;
    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long ONE_MS_MICROS = 1_000;
    private static final long FIVE_MS_MICROS = 5_000;

    private final Logger logger;
    private final long intervalNanos;
    private final long[] counts = new long[BUCKETS];
    private long samples;
    private long maxMicros;
    private long over1ms;
    private long over5ms;

    SchedulingLatencyProbe(Logger logger, long intervalNanos) {
        this.logger = logger;
        this.intervalNanos = intervalNanos;
    }

    static void start(Logger logger, long intervalNanos) {
        Thread thread = new Thread(new SchedulingLatencyProbe(logger, intervalNanos), "sched-latency-probe");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        long windowStart = System.nanoTime();
        while (!Thread.currentThread().isInterrupted()) {
            long due = System.nanoTime() + intervalNanos;
            LockSupport.parkNanos(intervalNanos);
            long now = System.nanoTime();
            record(now - due);
            if (now - windowStart >= WINDOW_NANOS) {
                logger.logf(LogMessage.DEBUG, "%s", summarize(now - windowStart));
                windowStart = now;
            }
        }
    }

    void record(long lateNanos) {
        long micros = Math.max(0, lateNanos) / 1_000;
        counts[bucketOf(micros)]++;
        samples++;
        maxMicros = Math.max(maxMicros, micros);
        if (micros >= ONE_MS_MICROS) {
            over1ms++;
        }
        if (micros >= FIVE_MS_MICROS) {
            over5ms++;
        }
    }

    /** Formats the window's statistics and starts a new window. */
    String summarize(long windowNanos) {
        String summary = String.format(
                "SCHED_LATENCY window_s=%.3f samples=%d p50_us=%d p99_us=%d p999_us=%d max_us=%d over1ms=%d over5ms=%d",
                windowNanos / 1e9,
                samples,
                percentileMicros(0.5),
                percentileMicros(0.99),
                percentileMicros(0.999),
                maxMicros,
                over1ms,
                over5ms);
        Arrays.fill(counts, 0);
        samples = 0;
        maxMicros = 0;
        over1ms = 0;
        over5ms = 0;
        return summary;
    }

    /** The upper edge of the bucket holding the given quantile, capped at the largest value seen. */
    long percentileMicros(double quantile) {
        if (samples == 0) {
            return 0;
        }
        long rank = (long) Math.ceil(quantile * samples);
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += counts[i];
            if (seen >= rank) {
                return Math.min(upperMicros(i), maxMicros);
            }
        }
        return maxMicros;
    }

    static int bucketOf(long micros) {
        if (micros < SUB_BUCKETS) {
            return (int) micros;
        }
        int exponent = 63 - Long.numberOfLeadingZeros(micros);
        int sub = (int) (micros >> (exponent - SUB_BUCKET_BITS)) & (SUB_BUCKETS - 1);
        return Math.min(BUCKETS - 1, (exponent - SUB_BUCKET_BITS + 1) * SUB_BUCKETS + sub);
    }

    static long upperMicros(int bucket) {
        if (bucket < SUB_BUCKETS) {
            return bucket + 1;
        }
        int exponent = bucket / SUB_BUCKETS + SUB_BUCKET_BITS - 1;
        int sub = bucket % SUB_BUCKETS;
        return (long) (SUB_BUCKETS + sub + 1) << (exponent - SUB_BUCKET_BITS);
    }
}
