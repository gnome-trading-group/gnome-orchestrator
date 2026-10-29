package group.gnometrading.trading;

import group.gnometrading.schemas.MessageHeaderDecoder;
import group.gnometrading.schemas.ShardStatusDecoder;
import io.aeron.Subscription;
import org.agrona.DirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;

/**
 * One shard a session reads from: its status subscription, which also owns the receive port every stream from that
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
            feed.statusReconnecting = streams.reconnecting() != 0;
        }
    }
}
