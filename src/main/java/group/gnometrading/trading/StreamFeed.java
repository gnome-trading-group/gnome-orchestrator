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
    private final AtomicLong badSpells = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    StreamFeed(final int streamId, final String name) {
        this.streamId = streamId;
        this.name = name;
    }

    boolean bad() {
        return bad;
    }

    void markBad() {
        if (!bad) {
            bad = true;
            badSpells.lazySet(badSpells.get() + 1);
        }
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
