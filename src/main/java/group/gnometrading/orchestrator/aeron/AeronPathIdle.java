package group.gnometrading.orchestrator.aeron;

import group.gnometrading.resources.Properties;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.BackoffIdleStrategy;

/**
 * How long the threads that carry messages over Aeron may park when idle: market data from a shard's reader to a
 * session's strategy (the shard's publisher and media driver, the session's media driver and its ingress agent), and
 * orders between a session and its order gateway (the gateway's media driver, ingress and egress); each such agent is
 * an {@link AeronPathAgent}. Each park delays the next message that arrives during it, so on a quiet path every thread
 * on the way adds up to the full park; a shorter one costs more wake-ups instead. A shard's setting
 * ({@value #SHARD_PROPERTY}) saves every session reading it, for one host's CPU; a session's ({@value #PROPERTY})
 * saves only that session, for its own, which on a small burstable host is the larger cost; a gateway's
 * ({@value #GATEWAY_PROPERTY}) every session's orders. All default to 1 ms.
 */
public record AeronPathIdle(long maxParkNanos) {

    public static final String PROPERTY = "market.data.idle.max.park.micros";
    public static final String SHARD_PROPERTY = "shard.market.data.idle.max.park.micros";
    public static final String GATEWAY_PROPERTY = "gateway.idle.max.park.micros";
    public static final AeronPathIdle DEFAULT = new AeronPathIdle(TimeUnit.MILLISECONDS.toNanos(1));

    private static final long MIN_PARK_NANOS = 1_000;

    public AeronPathIdle {
        if (maxParkNanos < MIN_PARK_NANOS) {
            throw new IllegalArgumentException("An Aeron-path idle park must be at least 1 µs");
        }
    }

    /** A session's. */
    public static AeronPathIdle from(final Properties properties) {
        return read(properties, PROPERTY);
    }

    /** A shard's. */
    public static AeronPathIdle forShard(final Properties properties) {
        return read(properties, SHARD_PROPERTY);
    }

    /** An order gateway's. */
    public static AeronPathIdle forGateway(final Properties properties) {
        return read(properties, GATEWAY_PROPERTY);
    }

    private static AeronPathIdle read(final Properties properties, final String property) {
        return properties.hasProperty(property)
                ? new AeronPathIdle(TimeUnit.MICROSECONDS.toNanos(properties.getLongProperty(property)))
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
