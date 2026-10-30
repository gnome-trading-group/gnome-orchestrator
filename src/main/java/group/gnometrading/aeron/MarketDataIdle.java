package group.gnometrading.aeron;

import group.gnometrading.resources.Properties;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.BackoffIdleStrategy;

/**
 * How long the threads that carry market data from a shard's reader to a session's strategy may park when idle: the
 * shard's publisher and media driver, the session's media driver and its ingress agent ({@link MarketDataPathAgent}).
 * Each park delays the next record that arrives during it, so on a quiet stream every thread on the way adds up to
 * the full park; a shorter one costs more wake-ups instead. A shard's setting ({@value #SHARD_PROPERTY}) saves every
 * session reading it, for one host's CPU; a session's ({@value #PROPERTY}) saves only that session, for its own,
 * which on a small burstable host is the larger cost. Both default to 1 ms.
 */
public record MarketDataIdle(long maxParkNanos) {

    public static final String PROPERTY = "market.data.idle.max.park.micros";
    public static final String SHARD_PROPERTY = "shard.market.data.idle.max.park.micros";
    public static final MarketDataIdle DEFAULT = new MarketDataIdle(TimeUnit.MILLISECONDS.toNanos(1));

    private static final long MIN_PARK_NANOS = 1_000;

    public MarketDataIdle {
        if (maxParkNanos < MIN_PARK_NANOS) {
            throw new IllegalArgumentException("A market-data idle park must be at least 1 µs");
        }
    }

    /** A session's. */
    public static MarketDataIdle from(final Properties properties) {
        return read(properties, PROPERTY);
    }

    /** A shard's. */
    public static MarketDataIdle forShard(final Properties properties) {
        return read(properties, SHARD_PROPERTY);
    }

    private static MarketDataIdle read(final Properties properties, final String property) {
        return properties.hasProperty(property)
                ? new MarketDataIdle(TimeUnit.MICROSECONDS.toNanos(properties.getLongProperty(property)))
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
