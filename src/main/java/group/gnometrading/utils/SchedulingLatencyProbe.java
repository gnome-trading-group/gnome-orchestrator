package group.gnometrading.utils;

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
 * <p>Lateness goes into a {@link LatencyHistogram}. Each finished window is published for a heartbeat or report to read.
 */
public final class SchedulingLatencyProbe implements Runnable {

    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long ONE_MS_MICROS = 1_000;
    private static final long FIVE_MS_MICROS = 5_000;

    /** One window's lateness, in microseconds. */
    public record Window(
            double windowSeconds,
            long samples,
            long p50Micros,
            long p99Micros,
            long p999Micros,
            long maxMicros,
            long over1ms,
            long over5ms) {}

    private final long intervalNanos;
    private final LatencyHistogram lateness = new LatencyHistogram();
    private volatile Window latest;
    private long over1ms;
    private long over5ms;

    SchedulingLatencyProbe(long intervalNanos) {
        this.intervalNanos = intervalNanos;
    }

    public static SchedulingLatencyProbe start(long intervalNanos) {
        SchedulingLatencyProbe probe = new SchedulingLatencyProbe(intervalNanos);
        Thread thread = new Thread(probe, "sched-latency-probe");
        thread.setDaemon(true);
        thread.start();
        return probe;
    }

    /** The last finished window; null before the first. */
    public Window latest() {
        return latest;
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
                latest = summarize(now - windowStart);
                windowStart = now;
            }
        }
    }

    public void record(long lateNanos) {
        long micros = Math.max(0, lateNanos) / 1_000;
        lateness.record(micros);
        if (micros >= ONE_MS_MICROS) {
            over1ms++;
        }
        if (micros >= FIVE_MS_MICROS) {
            over5ms++;
        }
    }

    /** The window's statistics; starts a new window. */
    public Window summarize(long windowNanos) {
        Window summary = new Window(
                windowNanos / 1e9,
                lateness.count(),
                lateness.percentileMicros(0.5),
                lateness.percentileMicros(0.99),
                lateness.percentileMicros(0.999),
                lateness.maxMicros(),
                over1ms,
                over5ms);
        lateness.reset();
        over1ms = 0;
        over5ms = 0;
        return summary;
    }

    long percentileMicros(double quantile) {
        return lateness.percentileMicros(quantile);
    }
}
