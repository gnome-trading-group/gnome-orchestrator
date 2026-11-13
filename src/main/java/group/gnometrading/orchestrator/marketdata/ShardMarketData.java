package group.gnometrading.orchestrator.marketdata;

import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.AeronStreamIds;
import group.gnometrading.orchestrator.runtime.DaemonThreads;
import group.gnometrading.orchestrator.runtime.GatewayHealth;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.utils.CauseChain;
import io.aeron.Subscription;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.agrona.ErrorHandler;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Market data read from collector shards over Aeron, for one session or, {@link #shared shared}, for several. The
 * launcher leases each session's streams, and its lease says where each one is live. On a control thread this does
 * what blocks on the media driver for {@link ShardFeedAgent}, which reads the records on its own thread: closing what
 * the agent stopped reading, subscribing a stalled stream again (after its old subscription is closed, so the new one
 * joins at the live position), and following each lease: a stream moving to another shard (a hand-off, a roll, a
 * replaced task) is subscribed there too and switches on its first clean record there; a move that doesn't deliver
 * within {@link #PENDING_TIMEOUT_NANOS}, or that the lease no longer names, is given up.
 *
 * <p>A session's own reads fixed streams and starts only once every one is live. A shared one reads the streams of the
 * sessions {@link #addSession added} to it, each while at least one of them names it, and waits for nothing: each
 * stream is subscribed when a lease names it live. A stream several sessions name is one subscription, placed by the
 * lease of the earliest of them still added (their leases agree; reading one keeps a move from flapping between lease
 * reads taken at different moments).
 */
public final class ShardMarketData implements MarketData {

    static final long CONTROL_EVERY_MILLIS = 100;
    static final long LEASE_EVERY_NANOS = TimeUnit.SECONDS.toNanos(10);
    static final long PENDING_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final long STARTUP_POLL_MILLIS = 2_000;

    /** Reads a session's lease; null when it has none. */
    @FunctionalInterface
    public interface LeaseSource {
        MarketDataLeases.Lease get() throws Exception;
    }

    /** A session whose lease places streams. Control thread only. */
    private static final class Holder {
        final String sessionId;
        final LeaseSource leases;
        final List<StreamFeed> feeds = new ArrayList<>();
        long nextLeaseNanos = Long.MIN_VALUE;
        boolean leaseMissingLogged;

        Holder(final String sessionId, final LeaseSource leases) {
            this.sessionId = sessionId;
            this.leases = leases;
        }
    }

    private record AddSession(String sessionId, LeaseSource leases, int[] streamIds) {}

    private record RemoveSession(String sessionId) {}

    private final Logger logger;
    private final AeronRuntime aeron;
    private final String localIp;
    private final Holder session;
    private final long startDeadlineNanos;
    private final LongSupplier nanoTime;
    private final List<GatewayHealth> health;
    private final ShardFeedAgent agent;
    // Control thread only from here on, but for the session changes, which any thread queues.
    private final List<StreamFeed> feeds = new ArrayList<>();
    // The sessions naming each stream, earliest first: the first one's lease places it.
    private final Map<StreamFeed, List<Holder>> holders = new IdentityHashMap<>();
    private final Map<String, Holder> sessions = new LinkedHashMap<>();
    private final Int2ObjectHashMap<StreamFeed> byStream = new Int2ObjectHashMap<>();
    private final Queue<Object> sessionChanges = new ConcurrentLinkedQueue<>();
    private final List<ShardLink> links = new ArrayList<>();
    // When the control thread asked for each stream's move, to give up one that never delivers.
    private final Map<StreamFeed, Long> pendingSince = new IdentityHashMap<>();
    private final Map<StreamFeed, Long> refusedLogged = new IdentityHashMap<>();
    private final Map<StreamFeed, Long> badSpellsLogged = new IdentityHashMap<>();
    private GnomeAgentRunner runner;
    private ScheduledExecutorService control;

    /**
     * A session's market data.
     *
     * @param feeds every stream the session reads, traded listings' first
     * @param health the traded listings' feeds, one per listing, for the heartbeat
     * @param startDeadlineNanos how long to wait for every stream to go live before giving up on starting
     */
    public ShardMarketData(
            final Logger logger,
            final AeronRuntime aeron,
            final String localIp,
            final LeaseSource leases,
            final List<StreamFeed> feeds,
            final List<GatewayHealth> health,
            final ShardFeedAgent agent,
            final long startDeadlineNanos,
            final LongSupplier nanoTime) {
        this(logger, aeron, localIp, new Holder("session", leases), health, agent, startDeadlineNanos, nanoTime);
        sessions.put(session.sessionId, session);
        for (final StreamFeed feed : feeds) {
            this.feeds.add(feed);
            session.feeds.add(feed);
            holders.computeIfAbsent(feed, key -> new ArrayList<>()).add(session);
        }
    }

    private ShardMarketData(
            final Logger logger,
            final AeronRuntime aeron,
            final String localIp,
            final Holder session,
            final List<GatewayHealth> health,
            final ShardFeedAgent agent,
            final long startDeadlineNanos,
            final LongSupplier nanoTime) {
        if (localIp == null) {
            throw new IllegalStateException("No private IP for shards to send market data to");
        }
        this.logger = logger;
        this.aeron = aeron;
        this.localIp = localIp;
        this.session = session;
        this.health = health;
        this.agent = agent;
        this.startDeadlineNanos = startDeadlineNanos;
        this.nanoTime = nanoTime;
    }

    /**
     * Market data for a process reading streams on behalf of several sessions ({@link #addSession}), every record into
     * {@code ring}, which its agent alone writes.
     */
    public static ShardMarketData shared(
            final Logger logger,
            final AeronRuntime aeron,
            final String localIp,
            final SequencedRingBuffer<?> ring,
            final LongSupplier nanoTime) {
        return shared(logger, aeron, localIp, ring, nanoTime, null, null);
    }

    /** As {@link #shared(Logger, AeronRuntime, String, SequencedRingBuffer, LongSupplier)}, measuring into {@code metrics}. */
    public static ShardMarketData shared(
            final Logger logger,
            final AeronRuntime aeron,
            final String localIp,
            final SequencedRingBuffer<?> ring,
            final LongSupplier nanoTime,
            final FeedMetrics metrics,
            final EpochNanoClock wallClock) {
        return new ShardMarketData(
                logger,
                aeron,
                localIp,
                null,
                List.of(),
                new ShardFeedAgent(ring, List.of(), nanoTime, metrics, wallClock),
                0,
                nanoTime);
    }

    /** Its feed thread's runner, once started; for a sampler of the process's threads. */
    public GnomeAgentRunner runner() {
        return runner;
    }

    /** A sampler of what its feed thread measured ({@link FeedMetrics}), on the caller's {@code samples}. */
    public FeedSampler sampler(final SampleBuffer samples, final long intervalMs) {
        return new FeedSampler(samples, agent, intervalMs);
    }

    /**
     * Starts the agent and the control thread. A session's first waits until every stream is live, throwing when they
     * aren't within its start deadline; a shared one waits for nothing.
     */
    @Override
    public void start(final ErrorHandler errorHandler) {
        if (session != null) {
            subscribe(awaitLive());
        }
        runner = new GnomeAgentRunner(agent, errorHandler);
        GnomeAgentRunner.startOnThread(runner);
        control = DaemonThreads.scheduler("shard-feed-control");
        control.scheduleWithFixedDelay(
                this::controlTick, CONTROL_EVERY_MILLIS, CONTROL_EVERY_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Reads these streams while the session holds them; its lease, read at once and then every
     * {@link #LEASE_EVERY_NANOS}, says where each is. Adding a session already added replaces it. Any thread; takes
     * effect on the control thread's next tick.
     */
    public void addSession(final String sessionId, final LeaseSource leases, final int[] streamIds) {
        requireShared();
        sessionChanges.add(new AddSession(sessionId, leases, streamIds.clone()));
    }

    /** Stops reading the streams no remaining session holds. Any thread; takes effect on the next tick. */
    public void removeSession(final String sessionId) {
        requireShared();
        sessionChanges.add(new RemoveSession(sessionId));
    }

    private void requireShared() {
        if (session != null) {
            throw new IllegalStateException("A session's market data reads the streams it was built with");
        }
    }

    /** Every stream live on a shard that advertises where it listens; a shard may first have to start (minutes). */
    private MarketDataLeases.Lease awaitLive() {
        final long deadline = nanoTime.getAsLong() + startDeadlineNanos;
        String waiting = "no lease";
        while (nanoTime.getAsLong() < deadline) {
            try {
                final MarketDataLeases.Lease lease = session.leases.get();
                if (lease != null) {
                    waiting = notLive(lease);
                    if (waiting == null) {
                        logger.logf(LogMessage.DEBUG, "Market data: %d streams live on shards", feeds.size());
                        return lease;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for market data to go live on shards", e);
            } catch (Exception e) {
                waiting = "lease unreadable: " + CauseChain.of(e);
            }
            try {
                Thread.sleep(STARTUP_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for market data to go live on shards", e);
            }
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

    /**
     * Subscribes every one of a session's streams where the lease has it; for {@link #start}, and tests driving the
     * steps themselves.
     */
    void subscribe(final MarketDataLeases.Lease lease) {
        session.nextLeaseNanos = nanoTime.getAsLong() + LEASE_EVERY_NANOS;
        for (final StreamFeed feed : feeds) {
            follow(feed, lease.stream(feed.streamId));
        }
    }

    /** Never throws: one bad tick mustn't end the schedule. */
    void controlTick() {
        try {
            applySessionChanges();
            drainAgent();
            logBadSpells();
            final long now = nanoTime.getAsLong();
            boolean leasesRead = false;
            for (final Holder holder : sessions.values()) {
                if (now >= holder.nextLeaseNanos) {
                    holder.nextLeaseNanos = now + LEASE_EVERY_NANOS;
                    leasesRead = true;
                    followLease(holder);
                }
            }
            if (leasesRead) {
                logRefused();
            }
        } catch (Throwable error) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Market data control failed: %s", CauseChain.of(error));
        }
    }

    private void applySessionChanges() {
        Object change;
        while ((change = sessionChanges.poll()) != null) {
            if (change instanceof AddSession add) {
                dropSession(add.sessionId());
                addHolder(add);
            } else if (change instanceof RemoveSession remove) {
                dropSession(remove.sessionId());
            }
        }
    }

    private void addHolder(final AddSession add) {
        final Holder holder = new Holder(add.sessionId(), add.leases());
        sessions.put(holder.sessionId, holder);
        for (final int streamId : add.streamIds()) {
            StreamFeed feed = byStream.get(streamId);
            if (feed == null) {
                feed = new StreamFeed(streamId, "stream " + streamId);
                byStream.put(streamId, feed);
                feeds.add(feed);
                agent.command(new ShardFeedAgent.AddFeed(feed));
            }
            final List<Holder> named = holders.computeIfAbsent(feed, key -> new ArrayList<>());
            if (!named.contains(holder)) {
                named.add(holder);
                holder.feeds.add(feed);
            }
        }
        logger.logf(
                LogMessage.DEBUG, "Market data: session %s reads %d streams", holder.sessionId, holder.feeds.size());
    }

    private void dropSession(final String sessionId) {
        final Holder holder = sessions.remove(sessionId);
        if (holder == null) {
            return;
        }
        for (final StreamFeed feed : holder.feeds) {
            final List<Holder> named = holders.get(feed);
            named.remove(holder);
            if (named.isEmpty()) {
                dropFeed(feed);
            }
        }
        logger.logf(LogMessage.DEBUG, "Market data: session %s removed", sessionId);
    }

    private void dropFeed(final StreamFeed feed) {
        holders.remove(feed);
        feeds.remove(feed);
        byStream.remove(feed.streamId);
        pendingSince.remove(feed);
        refusedLogged.remove(feed);
        badSpellsLogged.remove(feed);
        agent.command(new ShardFeedAgent.RemoveFeed(feed));
        logger.logf(LogMessage.DEBUG, "Market data: stream %d no longer read", feed.streamId);
    }

    /** What the ingress thread handed over: in order, so a stalled subscription is closed before its replacement. */
    private void drainAgent() {
        Object message;
        while ((message = agent.toControl().poll()) != null) {
            if (message instanceof ShardFeedAgent.Close close) {
                close.subscription().close();
            } else if (message instanceof ShardFeedAgent.Resubscribe resubscribe) {
                final StreamFeed feed = resubscribe.feed();
                if (!holders.containsKey(feed)) {
                    continue;
                }
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

    private void followLease(final Holder holder) throws Exception {
        final MarketDataLeases.Lease lease = holder.leases.get();
        if (lease == null) {
            if (!holder.leaseMissingLogged) {
                holder.leaseMissingLogged = true;
                if (holder == session) {
                    logger.logf(
                            LogMessage.UNKNOWN_ERROR,
                            "Market data: the session's lease is gone; keeping what it reads");
                } else {
                    logger.logf(
                            LogMessage.UNKNOWN_ERROR,
                            "Market data: session %s's lease is gone; keeping what it reads",
                            holder.sessionId);
                }
            }
            return;
        }
        holder.leaseMissingLogged = false;
        for (final StreamFeed feed : holder.feeds) {
            if (holders.get(feed).get(0) == holder) {
                follow(feed, lease.stream(feed.streamId));
            }
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

    /**
     * Each time the ingress thread marked a stream bad, and why; several spells within one tick are logged once, with
     * the latest reason.
     */
    private void logBadSpells() {
        for (final StreamFeed feed : feeds) {
            final long spells = feed.badSpells();
            final long logged = badSpellsLogged.getOrDefault(feed, 0L);
            if (spells == logged) {
                continue;
            }
            badSpellsLogged.put(feed, spells);
            final StreamFeed.BadReason reason = feed.badReason();
            final String detail = reason == StreamFeed.BadReason.DROPS ? " (" + feed.badDropped() + " in all)" : "";
            logger.logf(
                    LogMessage.DEBUG,
                    "Market data: stream %d marked bad%s: %s%s",
                    feed.streamId,
                    spells - logged > 1 ? " " + (spells - logged) + " times" : "",
                    reason == null ? "unknown" : reason.description,
                    detail);
        }
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

    /** The agent, for tests driving the threads' steps themselves. */
    ShardFeedAgent agent() {
        return agent;
    }

    /** The stream read for {@code streamId} now, or null; control thread only, for tests. */
    StreamFeed feed(final int streamId) {
        return byStream.get(streamId);
    }

    /** A session's traded listings' feeds; none when shared. */
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
    }
}
