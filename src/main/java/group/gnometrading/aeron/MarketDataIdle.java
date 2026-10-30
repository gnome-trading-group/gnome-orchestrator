package group.gnometrading.aeron;

import group.gnometrading.resources.Properties;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.BackoffIdleStrategy;

/**
 * How long the threads that carry market data from a shard's reader to a session's strategy may park when idle: the
 * shard's publisher and media driver, the session's media driver and its ingress agent ({@link MarketDataPathAgent}).
 * Each park delays the next record that arrives during it, so on a quiet stream every thread on the way adds up to
 * the full park; a shorter one costs more wake-ups instead. Property {@value #PROPERTY}, default 1 ms.
 */
public record MarketDataIdle(long maxParkNanos) {

    public static final String PROPERTY = "market.data.idle.max.park.micros";
    public static final MarketDataIdle DEFAULT = new MarketDataIdle(TimeUnit.MILLISECONDS.toNanos(1));

    private static final long MIN_PARK_NANOS = 1_000;

    public MarketDataIdle {
        if (maxParkNanos < MIN_PARK_NANOS) {
            throw new IllegalArgumentException(PROPERTY + " must be at least 1 µs");
        }
    }

    public static MarketDataIdle from(final Properties properties) {
        return properties.hasProperty(PROPERTY)
                ? new MarketDataIdle(TimeUnit.MICROSECONDS.toNanos(properties.getLongProperty(PROPERTY)))
                : DEFAULT;
    }

    /** Spin, then yield, then park from 1 µs up to the maximum. */
    public BackoffIdleStrategy agentStrategy() {
        return new BackoffIdleStrategy(
                BackoffIdleStrategy.DEFAULT_MAX_SPINS,
                BackoffIdleStrategy.DEFAULT_MAX_YIELDS,
                MIN_PARK_NANOS,
                maxParkNanos);
    }

    /** As {@link #agentStrategy()} with barely any spinning, for a driver thread that shares a small host. */
    BackoffIdleStrategy driverStrategy() {
        return new BackoffIdleStrategy(1, 1, MIN_PARK_NANOS, maxParkNanos);
    }
}
