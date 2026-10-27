package group.gnometrading.collectors;

import group.gnometrading.SecurityMaster;
import group.gnometrading.feeds.Assignment;
import group.gnometrading.gateways.feed.ConnectionGroup;
import group.gnometrading.gateways.feed.FeedConnection;
import group.gnometrading.gateways.feed.Feeds;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.md.Source;
import group.gnometrading.md.Stream;
import group.gnometrading.utils.CauseChain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What a shard reads, kept in step with its {@link Assignment}: every {@link #ASSIGNMENT_POLL_NANOS} the assigned
 * streams are compared with those running, new ones start and dropped ones stop, the others untouched. A stream's
 * market goes on its source's connections through {@link Feeds}; its records go to the collector agent.
 *
 * <p>{@code sources} adds a stream's source to the feeds the first time one of its streams starts.
 *
 * <p>A stream that can't be started (gone from the registry, a source with no feed) is tried again after
 * {@link #RETRY_NANOS}, as is one whose collector failed.
 *
 * <p>Its methods run on one thread, the runtime's.
 */
final class ShardRuntime {

    static final long ASSIGNMENT_POLL_NANOS = TimeUnit.SECONDS.toNanos(10);
    static final long RETRY_NANOS = TimeUnit.MINUTES.toNanos(5);

    /** Builds a stream's ring and minute files. */
    @FunctionalInterface
    interface StreamFactory {
        ShardStream create(int streamId, String name);
    }

    private final Logger logger;
    private final SecurityMaster securityMaster;
    private final Assignment assignment;
    private final Feeds feeds;
    private final Consumer<Source> sources;
    private final MarketDataCollectorAgent agent;
    // A source's market-data account label, for the heartbeat; null for a public feed.
    private final Function<String, String> accountOf;
    private final StreamFactory factory;
    private final Map<Integer, ShardStream> running = new HashMap<>();
    private final Map<Integer, Long> retryAt = new HashMap<>();
    private volatile List<ShardStream> view = List.of();
    private volatile List<CollectorHeartbeat.Connection> connectionView = List.of();
    private volatile long generation = -1;
    private long nextPollNanos;
    // Read by the health check's thread.
    private volatile long lastTickNanos;
    // Runtime thread only, like every tick.
    private boolean stopped;

    ShardRuntime(
            final Logger logger,
            final SecurityMaster securityMaster,
            final Assignment assignment,
            final Feeds feeds,
            final Consumer<Source> sources,
            final MarketDataCollectorAgent agent,
            final StreamFactory factory,
            final Function<String, String> accountOf) {
        this.logger = logger;
        this.securityMaster = securityMaster;
        this.assignment = assignment;
        this.feeds = feeds;
        this.sources = sources;
        this.agent = agent;
        this.factory = factory;
        this.accountOf = accountOf;
    }

    /** The streams running, for the heartbeat's thread. */
    List<ShardStream> streams() {
        return view;
    }

    /** The connections open after the last tick, for the heartbeat's thread. */
    List<CollectorHeartbeat.Connection> connections() {
        return connectionView;
    }

    /** The assignment generation last applied; -1 before any. */
    long generation() {
        return generation;
    }

    /** When the last tick finished, on the tick's clock; 0 before the first. */
    long lastTickNanos() {
        return lastTickNanos;
    }

    /** Never throws: one bad tick mustn't end the schedule that runs it. Does nothing once {@link #stopAll} has run. */
    void tick(final long now) {
        // The schedule outlives stopAll by up to the stop timeout; a tick then would start every stream again.
        if (stopped) {
            return;
        }
        try {
            if (now >= nextPollNanos) {
                nextPollNanos = now + ASSIGNMENT_POLL_NANOS;
                applyAssignment(now);
            }
            replaceFailed(now);
            feeds.tick(now);
            publishConnections();
        } catch (Throwable error) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Shard tick failed: %s", CauseChain.of(error));
        }
        lastTickNanos = now;
    }

    /** A failed read keeps the streams running as they are, until the next poll. */
    private void applyAssignment(final long now) {
        final Assignment.Streams assigned;
        try {
            assigned = assignment.read();
        } catch (Exception error) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Failed to read the assignment: %s", CauseChain.of(error));
            return;
        }
        apply(assigned, now);
    }

    private void apply(final Assignment.Streams assigned, final long now) {
        final Set<Integer> wanted = new HashSet<>();
        for (int streamId : assigned.streamIds()) {
            wanted.add(streamId);
        }
        for (Integer streamId : new ArrayList<>(running.keySet())) {
            if (!wanted.contains(streamId)) {
                stop(running.remove(streamId), now);
                logger.logf(LogMessage.DEBUG, "Stopped stream %d", streamId);
            }
        }
        retryAt.keySet().retainAll(wanted);
        for (int streamId : wanted) {
            final Long retry = retryAt.get(streamId);
            if (!running.containsKey(streamId) && (retry == null || now >= retry)) {
                start(streamId, now);
            }
        }
        generation = assigned.generation();
        publishView();
    }

    private void start(final int streamId, final long now) {
        ShardStream stream = null;
        try {
            final Stream info = securityMaster.getStream(streamId);
            if (info == null) {
                throw new IllegalStateException("not in the registry");
            }
            if (!"LISTING".equals(info.subjectKind())) {
                throw new IllegalStateException("a " + info.subjectKind() + " stream; no vendor feeds yet");
            }
            final Source source = securityMaster.getSource(info.sourceId());
            sources.accept(source);
            stream = factory.create(
                    streamId, source.code() + " " + info.nativeSubjectId() + " (stream " + streamId + ")");
            agent.add(stream);
            feeds.addStream(streamId, source.code(), info.nativeSubjectId(), stream.ring(), now);
            stream.subject(feeds.subject(streamId));
            running.put(streamId, stream);
            retryAt.remove(streamId);
            logger.logf(LogMessage.DEBUG, "Started %s", stream.name());
        } catch (Throwable error) {
            if (stream != null) {
                agent.retire(stream, null);
            }
            retryAt.put(streamId, now + RETRY_NANOS);
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Can't start stream %d, retrying in %d minutes: %s",
                    streamId,
                    TimeUnit.NANOSECONDS.toMinutes(RETRY_NANOS),
                    CauseChain.of(error));
        }
    }

    private void stop(final ShardStream stream, final long now) {
        feeds.removeStream(stream.streamId(), now);
        agent.retire(stream, null);
    }

    /** A stream whose collector failed starts over, with new minute files, once it has waited. */
    private void replaceFailed(final long now) {
        boolean replaced = false;
        for (ShardStream stream : new ArrayList<>(running.values())) {
            if (stream.failure() != null && now - stream.failedAtNanos() >= RETRY_NANOS) {
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Restarting %s after its collector failed: %s",
                        stream.name(),
                        CauseChain.of(stream.failure()));
                running.remove(stream.streamId());
                stop(stream, now);
                start(stream.streamId(), now);
                replaced = true;
            }
        }
        if (replaced) {
            publishView();
        }
    }

    private void publishConnections() {
        final List<CollectorHeartbeat.Connection> connections = new ArrayList<>();
        for (ConnectionGroup<?> group : feeds.groups()) {
            for (FeedConnection<?> connection : group.connections()) {
                connections.add(new CollectorHeartbeat.Connection(
                        connection.name(),
                        group.source(),
                        accountOf.apply(group.source()),
                        connection.size(),
                        connection.pauseControl().isPauseRequested()));
            }
        }
        connectionView = List.copyOf(connections);
    }

    private void publishView() {
        view = List.copyOf(running.values());
    }

    /** Stops every connection, then has the agent flush every stream; {@code done} counts down once per stream. */
    void stopAll(final CountDownLatch done) {
        stopped = true;
        feeds.closeAll();
        for (ShardStream stream : running.values()) {
            agent.retire(stream, done);
        }
        running.clear();
        publishView();
        publishConnections();
    }

    int size() {
        return running.size();
    }
}
