package group.gnometrading.orchestrator.marketdata;

import group.gnometrading.schemas.MessageHeaderDecoder;
import group.gnometrading.schemas.ShardStatusDecoder;
import io.aeron.Subscription;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.DirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;

/**
 * One shard this process reads from: its status subscription, which also owns the receive port every stream from that
 * shard shares (named by {@link #tag}). Made by the control thread; the ingress thread reads its status, and decides
 * when no stream needs it any more ({@link #retired}), after which the control thread closes it and never reuses it.
 */
final class ShardLink {

    final String shardId;
    final String privateIp;
    final int port;
    final long tag;
    final Subscription status;
    volatile boolean retired;

    // Written by the ingress thread, when its process measures (FeedMetrics); the sampler takes the peaks.
    private final AtomicLong records = new AtomicLong();
    private final AtomicLong networkPeakNanos = new AtomicLong();
    private final AtomicLong shardWaitPeakNanos = new AtomicLong();
    private final AtomicLong statusGapPeakNanos = new AtomicLong();

    // Ingress thread only.
    long lastStatusNanos;
    boolean statusSeen;
    private final MessageHeaderDecoder header = new MessageHeaderDecoder();
    private final ShardStatusDecoder statusDecoder = new ShardStatusDecoder();

    ShardLink(final String shardId, final String privateIp, final int port, final long tag, final Subscription status) {
        this.shardId = shardId;
        this.privateIp = privateIp;
        this.port = port;
        this.tag = tag;
        this.status = status;
    }

    boolean at(final String ip, final int otherPort) {
        return privateIp.equals(ip) && port == otherPort;
    }

    /** On the ingress thread: applies a status message to the streams read from this shard. */
    void onStatus(
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final Int2ObjectHashMap<StreamFeed> feeds,
            final long now) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) {
            return;
        }
        header.wrap(buffer, offset);
        if (header.schemaId() != ShardStatusDecoder.SCHEMA_ID
                || header.templateId() != ShardStatusDecoder.TEMPLATE_ID) {
            return;
        }
        statusDecoder.wrap(
                buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version());
        if (statusSeen) {
            peak(statusGapPeakNanos, now - lastStatusNanos);
        }
        lastStatusNanos = now;
        statusSeen = true;
        final ShardStatusDecoder.StreamsDecoder streams = statusDecoder.streams();
        while (streams.hasNext()) {
            streams.next();
            final StreamFeed feed = feeds.get(streams.streamId());
            // During a hand-off both shards report the stream; only the one it is read from counts.
            if (feed == null || feed.currentLink != this) {
                continue;
            }
            feed.statusPosition = streams.position();
            feed.statusSessionId = streams.publicationSessionId();
            final long dropped = streams.dropped();
            feed.dropsRose |= feed.statusDropped >= 0 && dropped > feed.statusDropped;
            feed.statusDropped = dropped;
            feed.statusReconnecting =
                    streams.reconnecting() != 0 && !feed.cleanAfter(feed.statusSessionId, feed.statusPosition);
        }
    }

    void received() {
        records.lazySet(records.get() + 1);
    }

    void network(final long nanos) {
        peak(networkPeakNanos, nanos);
    }

    void shardWait(final long nanos) {
        peak(shardWaitPeakNanos, nanos);
    }

    private static void peak(final AtomicLong peak, final long value) {
        if (value > peak.get()) {
            peak.lazySet(value);
        }
    }

    /** Records read from this shard since it was linked. Any thread. */
    long records() {
        return records.get();
    }

    /** The longest network time since the last call, then 0. The sampler only. */
    long takeNetworkPeakNanos() {
        return networkPeakNanos.getAndSet(0);
    }

    /** The longest in-shard time since the last call, then 0. The sampler only. */
    long takeShardWaitPeakNanos() {
        return shardWaitPeakNanos.getAndSet(0);
    }

    /** The longest gap between two of its statuses since the last call, then 0: a shard too busy to send them. */
    long takeStatusGapPeakNanos() {
        return statusGapPeakNanos.getAndSet(0);
    }

    // The sampler's own: the record count it last read.
    long sampledRecords;
}
