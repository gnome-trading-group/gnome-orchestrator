package group.gnometrading.orchestrator.shard;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The shard's logger, counting what it logs as {@link LogMessage#UNKNOWN_ERROR}. What the feeds handle by themselves (a
 * resync, a reconnect) is logged otherwise, so these are the ones someone should look at: the status reports them
 * ({@link ShardStatusWriter}) and the reconciler alerts on them. Every other line passes straight through.
 */
final class ShardErrors implements Logger {

    static final long WINDOW_NANOS = TimeUnit.HOURS.toNanos(1);
    // More in an hour than this already reads as "many"; the count stops there rather than keep every time.
    static final int MAX_COUNTED = 256;

    /** What it has counted, as of one moment. {@code latest} is null before the first. */
    record Reading(long total, int lastHour, String latest, long latestAtNanos) {}

    private final Logger delegate;
    private final LongSupplier wallClock;
    private final long[] times = new long[MAX_COUNTED];
    private long total;
    private String latest;
    private long latestAtNanos;

    ShardErrors(final Logger delegate, final LongSupplier wallClock) {
        this.delegate = delegate;
        this.wallClock = wallClock;
    }

    synchronized Reading read() {
        final long since = wallClock.getAsLong() - WINDOW_NANOS;
        int lastHour = 0;
        for (int i = 0; i < Math.min(total, MAX_COUNTED); i++) {
            if (times[i] > since) {
                lastHour++;
            }
        }
        return new Reading(total, lastHour, latest, latestAtNanos);
    }

    private synchronized void count(final String line) {
        final long now = wallClock.getAsLong();
        times[(int) (total % MAX_COUNTED)] = now;
        total++;
        latest = line;
        latestAtNanos = now;
    }

    @Override
    public void log(final LogMessage msg) {
        delegate.log(msg);
        if (msg == LogMessage.UNKNOWN_ERROR) {
            count(msg.name());
        }
    }

    @Override
    public void log(final LogMessage msg, final long v1) {
        delegate.log(msg, v1);
        if (msg == LogMessage.UNKNOWN_ERROR) {
            count(msg.name());
        }
    }

    @Override
    public void log(final LogMessage msg, final long v1, final long v2) {
        delegate.log(msg, v1, v2);
        if (msg == LogMessage.UNKNOWN_ERROR) {
            count(msg.name());
        }
    }

    @Override
    public void log(final LogMessage msg, final long v1, final long v2, final long v3) {
        delegate.log(msg, v1, v2, v3);
        if (msg == LogMessage.UNKNOWN_ERROR) {
            count(msg.name());
        }
    }

    @Override
    public void log(final LogMessage msg, final long v1, final long v2, final long v3, final long v4) {
        delegate.log(msg, v1, v2, v3, v4);
        if (msg == LogMessage.UNKNOWN_ERROR) {
            count(msg.name());
        }
    }

    @Override
    public void log(final LogMessage msg, final long v1, final long v2, final long v3, final long v4, final long v5) {
        delegate.log(msg, v1, v2, v3, v4, v5);
        if (msg == LogMessage.UNKNOWN_ERROR) {
            count(msg.name());
        }
    }

    @Override
    public void log(
            final LogMessage msg,
            final long v1,
            final long v2,
            final long v3,
            final long v4,
            final long v5,
            final long v6) {
        delegate.log(msg, v1, v2, v3, v4, v5, v6);
        if (msg == LogMessage.UNKNOWN_ERROR) {
            count(msg.name());
        }
    }

    @Override
    public void logf(final LogMessage msg, final String format, final Object... args) {
        delegate.logf(msg, format, args);
        if (msg == LogMessage.UNKNOWN_ERROR) {
            count(String.format(format, args));
        }
    }
}
