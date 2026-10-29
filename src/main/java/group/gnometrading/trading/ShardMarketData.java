package group.gnometrading.trading;

import group.gnometrading.aeron.AeronChannels;
import group.gnometrading.aeron.AeronRuntime;
import group.gnometrading.aeron.AeronStreamIds;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.GatewayHealth;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.utils.CauseChain;
import group.gnometrading.utils.DaemonThreads;
import io.aeron.Subscription;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.agrona.ErrorHandler;

/**
 * A session's market data read from collector shards over Aeron, in place of its own venue connections. The launcher
 * leases the session's streams; this waits until every one is live on a shard and subscribes. From then on, on a
 * control thread, it does what blocks on the media driver for {@link ShardFeedAgent}, which reads the records on its
 * own thread: closing what the agent stopped reading, subscribing a stalled stream again (after its old subscription is
 * closed, so the new one joins at the live position), and following the lease: a stream moving to another shard (a
 * hand-off, a roll, a replaced task) is subscribed there too and switches on its first clean record there; a move that
 * doesn't deliver within {@link #PENDING_TIMEOUT_NANOS}, or that the lease no longer names, is given up.
 */
final class ShardMarketData implements TradingOrchestrator.MarketData {

    static final long CONTROL_EVERY_MILLIS = 100;
    static final long LEASE_EVERY_NANOS = TimeUnit.SECONDS.toNanos(10);
    static final long PENDING_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final long STARTUP_POLL_MILLIS = 2_000;

    /** Reads the session's lease; null when it has none. */
    @FunctionalInterface
    interface LeaseSource {
        MarketDataLeases.Lease get() throws Exception;
    }

    private final Logger logger;
    private final AeronRuntime aeron;
    private final String localIp;
    private final LeaseSource leases;
    private final long startDeadlineNanos;
    private final LongSupplier nanoTime;
    private final List<StreamFeed> feeds;
    private final List<GatewayHealth> health;
    private final ShardFeedAgent agent;
    private final List<ShardLink> links = new ArrayList<>();
    // When the control thread asked for each stream's move, to give up one that never delivers.
    private final Map<StreamFeed, Long> pendingSince = new IdentityHashMap<>();
    private final Map<StreamFeed, Long> refusedLogged = new IdentityHashMap<>();
    private GnomeAgentRunner runner;
    private ScheduledExecutorService control;
    private long nextLeaseNanos;
    private boolean leaseMissingLogged;

    /**
     * @param feeds every stream the session reads, traded listings' first
     * @param health the traded listings' feeds, one per listing, for the heartbeat
     * @param startDeadlineNanos how long to wait for every stream to go live before giving up on starting
     */
    ShardMarketData(
            final Logger logger,
            final AeronRuntime aeron,
            final String localIp,
            final LeaseSource leases,
            final List<StreamFeed> feeds,
            final List<GatewayHealth> health,
            final ShardFeedAgent agent,
            final long startDeadlineNanos,
            final LongSupplier nanoTime) {
        if (localIp == null) {
            throw new IllegalStateException("No private IP for shards to send this session's market data to");
        }
        this.logger = logger;
        this.aeron = aeron;
        this.localIp = localIp;
        this.leases = leases;
        this.feeds = feeds;
        this.health = health;
        this.agent = agent;
        this.startDeadlineNanos = startDeadlineNanos;
        this.nanoTime = nanoTime;
    }

    @Override
    public void start(final ErrorHandler errorHandler) throws Exception {
        subscribe(awaitLive());
        runner = new GnomeAgentRunner(agent, errorHandler);
        GnomeAgentRunner.startOnThread(runner);
        control = DaemonThreads.scheduler("shard-feed-control");
        control.scheduleWithFixedDelay(
                this::controlTick, CONTROL_EVERY_MILLIS, CONTROL_EVERY_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Every stream live on a shard that advertises where it listens; a shard may first have to start (minutes). */
    private MarketDataLeases.Lease awaitLive() throws Exception {
        final long deadline = nanoTime.getAsLong() + startDeadlineNanos;
        String waiting = "no lease";
        while (nanoTime.getAsLong() < deadline) {
            try {
                final MarketDataLeases.Lease lease = leases.get();
                if (lease != null) {
                    waiting = notLive(lease);
                    if (waiting == null) {
                        logger.logf(LogMessage.DEBUG, "Market data: %d streams live on shards", feeds.size());
                        return lease;
                    }
                }
            } catch (Exception e) {
                waiting = "lease unreadable: " + CauseChain.of(e);
            }
            Thread.sleep(STARTUP_POLL_MILLIS);
        }
        throw new IllegalStateException("Market data never went live on shards (" + waiting + ")");
    }

    private String notLive(final MarketDataLeases.Lease lease) {
        final StringBuilder waiting = new StringBuilder();
        for (final StreamFeed feed : feeds) {
            final MarketDataLeases.LeasedStream stream = lease.stream(feed.streamId);
            if (stream == null || !stream.live()) {
                waiting.append(waiting.isEmpty() ? "" : ", ")
                        .append(feed.streamId)
                        .append(' ')
                        .append(stream == null ? "not leased" : stream.state());
            }
        }
        return waiting.isEmpty() ? null : waiting.toString();
    }

    /** Subscribes every stream where the lease has it; for {@link #start}, and tests driving the steps themselves. */
    void subscribe(final MarketDataLeases.Lease lease) {
        nextLeaseNanos = nanoTime.getAsLong() + LEASE_EVERY_NANOS;
        for (final StreamFeed feed : feeds) {
            follow(feed, lease.stream(feed.streamId));
        }
    }

    /** Never throws: one bad tick mustn't end the schedule. */
    void controlTick() {
        try {
            drainAgent();
            final long now = nanoTime.getAsLong();
            if (now >= nextLeaseNanos) {
                nextLeaseNanos = now + LEASE_EVERY_NANOS;
                followLease();
                logRefused();
            }
        } catch (Throwable error) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Market data control failed: %s", CauseChain.of(error));
        }
    }

    /** What the ingress thread handed over: in order, so a stalled subscription is closed before its replacement. */
    private void drainAgent() {
        Object message;
        while ((message = agent.toControl().poll()) != null) {
            if (message instanceof ShardFeedAgent.Close close) {
                close.subscription().close();
            } else if (message instanceof ShardFeedAgent.Resubscribe resubscribe) {
                final StreamFeed feed = resubscribe.feed();
                final ShardLink link = resubscribe.link();
                agent.command(new ShardFeedAgent.Install(feed, subscription(feed, link), false));
                logger.logf(LogMessage.DEBUG, "Market data: stream %d stalled, subscribed again", feed.streamId);
            } else if (message instanceof ShardFeedAgent.Retire retire) {
                links.remove(retire.link());
                retire.link().status.close();
                logger.logf(LogMessage.DEBUG, "Market data: left shard %s", retire.link().shardId);
            }
        }
    }

    private void followLease() throws Exception {
        final MarketDataLeases.Lease lease = leases.get();
        if (lease == null) {
            if (!leaseMissingLogged) {
                leaseMissingLogged = true;
                logger.logf(
                        LogMessage.UNKNOWN_ERROR, "Market data: the session's lease is gone; keeping what it reads");
            }
            return;
        }
        leaseMissingLogged = false;
        for (final StreamFeed feed : feeds) {
            follow(feed, lease.stream(feed.streamId));
        }
    }

    /**
     * Subscribes where the lease says the stream is, or is moving to: the first time as its subscription, after that as
     * a pending one it switches to on its first clean record there. A pending move the lease no longer names, or that
     * hasn't delivered in time, is given up.
     */
    private void follow(final StreamFeed feed, final MarketDataLeases.LeasedStream stream) {
        if (stream == null || feed.resubscribing) {
            return;
        }
        final MarketDataLeases.Placement target = target(stream);
        if (feed.pendingLink != null) {
            givingUpStalePending(feed, target);
            return;
        }
        pendingSince.remove(feed);
        if (target == null) {
            return;
        }
        final ShardLink current = feed.currentLink;
        if (current != null
                && current.at(target.endpoint().privateIp(), target.endpoint().port())) {
            return;
        }
        final ShardLink link = link(target.shardId(), target.endpoint());
        final boolean moving = current != null;
        agent.command(new ShardFeedAgent.Install(feed, subscription(feed, link), moving));
        if (moving) {
            pendingSince.put(feed, nanoTime.getAsLong());
            logger.logf(LogMessage.DEBUG, "Market data: stream %d moving to shard %s", feed.streamId, target.shardId());
        }
    }

    /** Gives up a move the lease no longer names, or that hasn't delivered in {@link #PENDING_TIMEOUT_NANOS}. */
    private void givingUpStalePending(final StreamFeed feed, final MarketDataLeases.Placement target) {
        final ShardLink pending = feed.pendingLink;
        final Long since = pendingSince.get(feed);
        final boolean stale = since != null && nanoTime.getAsLong() - since > PENDING_TIMEOUT_NANOS;
        if (stale
                || target == null
                || !pending.at(target.endpoint().privateIp(), target.endpoint().port())) {
            agent.command(new ShardFeedAgent.CancelPending(feed));
            pendingSince.remove(feed);
            logger.logf(
                    LogMessage.DEBUG, "Market data: stream %d gave up moving to %s", feed.streamId, pending.shardId);
        }
    }

    /** Where the stream should be read: its move target when the lease names one with an endpoint, else its shard. */
    private static MarketDataLeases.Placement target(final MarketDataLeases.LeasedStream stream) {
        final MarketDataLeases.Placement next = stream.next();
        if (next != null && next.endpoint() != null) {
            return next;
        }
        return stream.live()
                ? new MarketDataLeases.Placement(stream.shardId(), stream.state(), stream.endpoint())
                : null;
    }

    private StreamFeed.FeedSink subscription(final StreamFeed feed, final ShardLink link) {
        final Subscription subscription = aeron.aeron().addSubscription(AeronChannels.tagged(link.tag), feed.streamId);
        return new StreamFeed.FeedSink(subscription, link);
    }

    /** The shard at {@code endpoint}, joining it (its status, and the receive port its streams share) if new. */
    private ShardLink link(final String shardId, final MarketDataLeases.Endpoint endpoint) {
        for (final ShardLink link : links) {
            if (!link.retired && link.at(endpoint.privateIp(), endpoint.port())) {
                return link;
            }
        }
        final long tag = aeron.aeron().nextCorrelationId();
        final Subscription status = aeron.aeron()
                .addSubscription(
                        AeronChannels.shardSubscription(localIp, endpoint.privateIp(), endpoint.port(), tag),
                        AeronStreamIds.SHARD_STATUS);
        final ShardLink link = new ShardLink(shardId, endpoint.privateIp(), endpoint.port(), tag, status);
        agent.command(new ShardFeedAgent.Watch(link));
        links.add(link);
        logger.logf(
                LogMessage.DEBUG,
                "Market data: joined shard %s at %s:%d",
                shardId,
                endpoint.privateIp(),
                endpoint.port());
        return link;
    }

    /** Messages a stream couldn't take (oversized, or not a stream record): rare, so each rise is logged. */
    private void logRefused() {
        for (final StreamFeed feed : feeds) {
            final long refused = feed.refused();
            if (refused > refusedLogged.getOrDefault(feed, 0L)) {
                refusedLogged.put(feed, refused);
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Market data: stream %d refused %d messages that aren't stream records or don't fit a slot",
                        feed.streamId,
                        refused);
            }
        }
    }

    @Override
    public List<GatewayHealth> health() {
        return health;
    }

    @Override
    public void close() throws Exception {
        if (control != null) {
            control.shutdownNow();
            control.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (runner != null) {
            runner.close();
        }
        aeron.close();
    }
}
