package group.gnometrading.orchestrator.aeron;

import group.gnometrading.networking.sockets.SocketPoller;
import group.gnometrading.resources.Properties;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;

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
public record AeronPathIdle(long maxParkNanos, long timerSlackNanos) {

    public static final String PROPERTY = "market.data.idle.max.park.micros";
    public static final String SHARD_PROPERTY = "shard.market.data.idle.max.park.micros";
    public static final String GATEWAY_PROPERTY = "gateway.idle.max.park.micros";
    /**
     * Above 0, the gateway's Aeron-path threads' timer slack: the kernel ends a park up to its default 50 us late,
     * so a short park only lasts as asked with less.
     */
    public static final String GATEWAY_SLACK_PROPERTY = "gateway.idle.timer.slack.nanos";

    public static final AeronPathIdle DEFAULT = new AeronPathIdle(TimeUnit.MILLISECONDS.toNanos(1));

    private static final long MIN_PARK_NANOS = 1_000;

    public AeronPathIdle {
        if (maxParkNanos < MIN_PARK_NANOS) {
            throw new IllegalArgumentException("An Aeron-path idle park must be at least 1 µs");
        }
    }

    /** Parking at most {@code maxParkNanos}, the kernel's timer slack left as it is. */
    public AeronPathIdle(final long maxParkNanos) {
        this(maxParkNanos, 0);
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
        final AeronPathIdle park = read(properties, GATEWAY_PROPERTY);
        return properties.hasProperty(GATEWAY_SLACK_PROPERTY)
                ? new AeronPathIdle(park.maxParkNanos(), properties.getLongProperty(GATEWAY_SLACK_PROPERTY))
                : park;
    }

    private static AeronPathIdle read(final Properties properties, final String property) {
        return properties.hasProperty(property)
                ? new AeronPathIdle(TimeUnit.MICROSECONDS.toNanos(properties.getLongProperty(property)))
                : DEFAULT;
    }

    /** Spin, then yield, then park from 1 µs up to the maximum. */
    public IdleStrategy agentStrategy() {
        return slacked(new BackoffIdleStrategy(
                BackoffIdleStrategy.DEFAULT_MAX_SPINS,
                BackoffIdleStrategy.DEFAULT_MAX_YIELDS,
                MIN_PARK_NANOS,
                maxParkNanos));
    }

    /** As {@link #agentStrategy()} with barely any spinning, for a driver thread that shares a small host. */
    IdleStrategy driverStrategy() {
        return slacked(new BackoffIdleStrategy(1, 1, MIN_PARK_NANOS, maxParkNanos));
    }

    private IdleStrategy slacked(final IdleStrategy parks) {
        return timerSlackNanos > 0 ? new SlackedIdleStrategy(parks, timerSlackNanos) : parks;
    }

    /**
     * Sets its thread's timer slack the first time it idles, then idles as {@code parks}: an idle strategy runs on
     * the thread it idles, which is the only place a thread's slack can be set. Allocation-free.
     */
    private static final class SlackedIdleStrategy implements IdleStrategy {
        private final IdleStrategy parks;
        private final long timerSlackNanos;
        private boolean slackSet;

        SlackedIdleStrategy(final IdleStrategy parks, final long timerSlackNanos) {
            this.parks = parks;
            this.timerSlackNanos = timerSlackNanos;
        }

        @Override
        public void idle(final int workCount) {
            setSlackOnce();
            this.parks.idle(workCount);
        }

        @Override
        public void idle() {
            setSlackOnce();
            this.parks.idle();
        }

        @Override
        public void reset() {
            this.parks.reset();
        }

        @Override
        public String alias() {
            return this.parks.alias();
        }

        private void setSlackOnce() {
            if (!this.slackSet) {
                this.slackSet = true;
                SocketPoller.setTimerSlackNanos(this.timerSlackNanos);
            }
        }
    }
}
