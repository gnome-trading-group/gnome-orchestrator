package group.gnometrading.trading;

import group.gnometrading.gateways.GatewayHealth;
import group.gnometrading.sequencer.SequencedEvent;
import io.aeron.Subscription;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * One stream a session reads from shards. The ingress thread ({@link ShardFeedAgent}) owns every field: which
 * subscription it reads now and which it is moving to, the last record it passed on (to repeat flagged bad when the
 * stream is lost), and what the liveness checks need. The control thread reads only the volatile views, to decide
 * what to subscribe next, and asks the ingress thread for every change.
 */
final class StreamFeed implements GatewayHealth {

    /** Why a stream was last marked bad, for the control thread's log line. */
    enum BadReason {
        SHARD_SILENT("its shard's status stopped"),
        SILENT("nothing arrived"),
        IMAGE_GONE("its Aeron image went away"),
        RECONNECTING("its shard is reconnecting to the venue"),
        DROPS("its shard dropped records for sessions"),
        STALLED("it fell behind its shard's publication"),
        FLAGGED_BY_SHARD("its shard sent it flagged bad");

        final String description;

        BadReason(final String description) {
            this.description = description;
        }
    }

    /** A subscription to the stream on one shard. */
    record FeedSink(Subscription subscription, ShardLink link) {}

    final int streamId;
    private final String name;

    // Ingress thread only.
    FeedSink current;
    FeedSink pending;
    long pendingSinceNanos;
    boolean pendingDelivered;
    final UnsafeBuffer last = new UnsafeBuffer(new byte[SequencedEvent.MAX_MESSAGE_SIZE]);
    int lastLength;
    int lastTemplateId;
    long lastEvent = Long.MIN_VALUE;
    long lastSequence = Long.MIN_VALUE;
    long lastArrivalNanos;
    long statusPosition = -1;
    int statusSessionId;
    long statusDropped = -1;
    boolean statusReconnecting;
    boolean dropsRose;
    long behindSinceNanos;
    boolean joined;

    // Written by the ingress thread, read by the control thread.
    volatile ShardLink currentLink;
    volatile ShardLink pendingLink;
    volatile boolean resubscribing;

    private volatile boolean bad;
    // Written before badSpells is counted, so a reader that sees the count sees at least this spell's reason.
    private volatile BadReason badReason;
    private volatile long badDropped;
    private final AtomicLong badSpells = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    StreamFeed(final int streamId, final String name) {
        this.streamId = streamId;
        this.name = name;
    }

    boolean bad() {
        return bad;
    }

    /** {@code shardDropped}: the shard's drop count for the stream when it was marked; -1 before its first status. */
    void markBad(final BadReason reason, final long shardDropped) {
        if (!bad) {
            badReason = reason;
            badDropped = shardDropped;
            bad = true;
            badSpells.lazySet(badSpells.get() + 1);
        }
    }

    long badSpells() {
        return badSpells.get();
    }

    BadReason badReason() {
        return badReason;
    }

    long badDropped() {
        return badDropped;
    }

    void markGood() {
        bad = false;
    }

    /** A message the session couldn't take: too big for a slot, or not a stream record. */
    void refuse() {
        refused.lazySet(refused.get() + 1);
    }

    long refused() {
        return refused.get();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isReconnecting() {
        return bad;
    }

    @Override
    public long bookResyncs() {
        return badSpells.get();
    }
}
