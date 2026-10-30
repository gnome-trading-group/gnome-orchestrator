package group.gnometrading.trading;

import group.gnometrading.aeron.AeronIngressAgent;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.schemas.MessageHeaderDecoder;
import group.gnometrading.schemas.MessageSchema;
import group.gnometrading.schemas.StreamHeader;
import group.gnometrading.sequencer.SequencedEvent;
import group.gnometrading.sequencer.SequencedRingBuffer;
import io.aeron.Image;
import io.aeron.Subscription;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.agrona.DirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The session's market data from shards, on one thread: polls every subscription through an {@link AeronIngressAgent}
 * into the strategy's journaled market-data buffer, which it alone writes. It is also the only writer of which
 * subscription each stream reads: the control thread ({@link ShardMarketData}) makes and closes subscriptions, which
 * block on the media driver, and asks for every change through {@link #command}; this thread hands back what it has
 * stopped polling, for the control thread to close, through {@link #toControl()}.
 *
 * <ul>
 *   <li>A shard re-sends a quiet stream's last record; an exact repeat of the last record passed on is dropped.
 *   <li>During a move, the first clean record from the new shard switches the stream to it.
 *   <li>Every {@link #CHECK_EVERY_NANOS} each stream is checked, and one that can't be trusted to be delivering is
 *       marked bad: its last record goes into the buffer again flagged {@code badData}, so the OMS refuses new orders
 *       on it and the journal shows when. The next good record clears it. Lost means: its shard's status has stopped,
 *       nothing has arrived for {@link #SILENT_NANOS} (also a stream that never received anything), its image is
 *       gone, the shard reports it reconnecting or reports records it couldn't send, or it has stalled behind the
 *       shard's publication. A lagging subscriber is never told it lost data, so a stall is found by comparing the
 *       image's position with the publication's, and fixed by subscribing again, which joins at the live position.
 * </ul>
 */
final class ShardFeedAgent implements GnomeAgent {

    static final long CHECK_EVERY_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    static final long SHARD_SILENT_NANOS = TimeUnit.SECONDS.toNanos(5);
    static final long STALLED_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final long SILENT_NANOS = TimeUnit.SECONDS.toNanos(15);

    private static final int COMMAND_CAPACITY = 1024;
    private static final int TEMPLATE_ID_OFFSET = MessageHeaderDecoder.templateIdEncodingOffset();
    private static final int FLAGS_OFFSET = MessageHeaderDecoder.ENCODED_LENGTH + StreamHeader.FLAGS_OFFSET;

    /** From the control thread: start reading {@code sink} as the stream's subscription, or as the one it moves to. */
    record Install(StreamFeed feed, StreamFeed.FeedSink sink, boolean asPending) {}

    /** From the control thread: give up a move that hasn't delivered. */
    record CancelPending(StreamFeed feed) {}

    /** From the control thread: start reading a shard's status. */
    record Watch(ShardLink link) {}

    /** To the control thread: close a subscription this thread no longer polls. */
    record Close(Subscription subscription) {}

    /** To the control thread: the stream stalled; subscribe it again on {@code link} (its old one is closed first). */
    record Resubscribe(StreamFeed feed, ShardLink link) {}

    /** To the control thread: no stream needs this shard any more; close its status subscription and forget it. */
    record Retire(ShardLink link) {}

    private final AeronIngressAgent ingress = new AeronIngressAgent();
    private final SequencedRingBuffer<?> ring;
    private final List<StreamFeed> feeds;
    private final Int2ObjectHashMap<StreamFeed> byStream = new Int2ObjectHashMap<>();
    private final LongSupplier nanoTime;
    private final ManyToOneConcurrentArrayQueue<Object> commands =
            new ManyToOneConcurrentArrayQueue<>(COMMAND_CAPACITY);
    private final OneToOneConcurrentArrayQueue<Object> toControl = new OneToOneConcurrentArrayQueue<>(COMMAND_CAPACITY);
    // What waits for the ingress agent to stop polling before the control thread may close it.
    private final List<Object> afterRemoval = new ArrayList<>();
    private final UnsafeBuffer flagged = new UnsafeBuffer(new byte[SequencedEvent.MAX_MESSAGE_SIZE]);
    private long now;
    private long nextCheckNanos;

    ShardFeedAgent(final SequencedRingBuffer<?> ring, final List<StreamFeed> feeds, final LongSupplier nanoTime) {
        this.ring = ring;
        this.feeds = feeds;
        this.nanoTime = nanoTime;
        for (final StreamFeed feed : feeds) {
            byStream.put(feed.streamId, feed);
        }
    }

    /** From the control thread. */
    void command(final Object command) {
        if (!commands.offer(command)) {
            throw new IllegalStateException("Too many market data commands waiting for the ingress thread");
        }
    }

    /** What this thread hands the control thread, in order. */
    OneToOneConcurrentArrayQueue<Object> toControl() {
        return toControl;
    }

    @Override
    public int doWork() {
        now = nanoTime.getAsLong();
        int work = applyCommands();
        work += ingress.doWork();
        // The agent has now stopped polling what was removed last pass, so it may be closed.
        for (int i = 0; i < afterRemoval.size(); i++) {
            handOver(afterRemoval.get(i));
        }
        afterRemoval.clear();
        if (now >= nextCheckNanos) {
            nextCheckNanos = now + CHECK_EVERY_NANOS;
            for (int i = 0; i < feeds.size(); i++) {
                work += check(feeds.get(i));
            }
        }
        return work;
    }

    private int applyCommands() {
        int applied = 0;
        Object command;
        while ((command = commands.poll()) != null) {
            if (command instanceof Install install) {
                install(install);
            } else if (command instanceof CancelPending cancel) {
                cancelPending(cancel.feed());
            } else if (command instanceof Watch watch) {
                final ShardLink link = watch.link();
                ingress.add(link.status, (buffer, offset, length) -> {
                    link.onStatus(buffer, offset, length, byStream, now);
                    return true;
                });
            }
            applied++;
        }
        return applied;
    }

    private void install(final Install install) {
        final StreamFeed feed = install.feed();
        final StreamFeed.FeedSink sink = install.sink();
        if (sink.link().retired) {
            // Retired while the control thread was subscribing: close it; the next lease read subscribes again.
            handOver(new Close(sink.subscription()));
            return;
        }
        ingress.add(sink.subscription(), (buffer, offset, length) -> onRecord(feed, sink, buffer, offset, length));
        if (install.asPending()) {
            if (feed.pending != null) {
                stopReading(feed.pending.subscription());
            }
            feed.pending = sink;
            feed.pendingLink = sink.link();
            return;
        }
        if (feed.current != null) {
            stopReading(feed.current.subscription());
        }
        feed.current = sink;
        feed.currentLink = sink.link();
        feed.resubscribing = false;
        // From the subscription, not the last record: a stream that never receives anything is caught as silent.
        feed.lastArrivalNanos = now;
        resetStatus(feed);
    }

    private void cancelPending(final StreamFeed feed) {
        if (feed.pending == null) {
            return;
        }
        final ShardLink link = feed.pendingLink;
        stopReading(feed.pending.subscription());
        feed.pending = null;
        feed.pendingLink = null;
        retireIfUnused(link);
    }

    private boolean onRecord(
            final StreamFeed feed,
            final StreamFeed.FeedSink sink,
            final DirectBuffer buffer,
            final int offset,
            final int length) {
        if (!isStreamRecord(buffer, offset, length)) {
            feed.refuse();
            return true;
        }
        if (feed.current != sink) {
            // A move switches on the new shard's first clean record; anything else from it, or from a subscription
            // already let go, is dropped.
            if (feed.pending != sink || flaggedBad(buffer, offset)) {
                return true;
            }
            switchTo(feed, sink);
        }
        feed.lastArrivalNanos = now;
        feed.joined = true;
        if (!feed.bad() && isRepeat(feed, buffer, offset, length)) {
            return true;
        }
        final int templateId = buffer.getShort(offset + TEMPLATE_ID_OFFSET, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
        if (!ring.tryPublishRaw(buffer, offset, templateId, length)) {
            return false;
        }
        feed.last.putBytes(0, buffer, offset, length);
        feed.lastLength = length;
        feed.lastTemplateId = templateId;
        feed.lastEvent = StreamHeader.timestampEvent(buffer, offset);
        feed.lastSequence = StreamHeader.sequence(buffer, offset);
        if (flaggedBad(buffer, offset)) {
            feed.markBad(StreamFeed.BadReason.FLAGGED_BY_SHARD, feed.statusDropped);
        } else {
            feed.markGood();
        }
        return true;
    }

    private static boolean isStreamRecord(final DirectBuffer buffer, final int offset, final int length) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH + StreamHeader.LENGTH
                || length > SequencedEvent.MAX_MESSAGE_SIZE) {
            return false;
        }
        final MessageSchema schema = MessageSchema.forTemplateId(
                buffer.getShort(offset + TEMPLATE_ID_OFFSET, ByteOrder.LITTLE_ENDIAN) & 0xFFFF);
        return schema == MessageSchema.BOOK || schema == MessageSchema.ESPORTS;
    }

    private static boolean flaggedBad(final DirectBuffer buffer, final int offset) {
        return StreamHeader.isSet(StreamHeader.flags(buffer, offset), StreamHeader.BAD_DATA_BIT);
    }

    /**
     * Whether it is the last record again: a shard's re-send repeats it byte for byte. Venues often have no sequence
     * and stamp in milliseconds, so two real updates can share both; only the whole record tells them apart.
     */
    private static boolean isRepeat(
            final StreamFeed feed, final DirectBuffer buffer, final int offset, final int length) {
        if (length != feed.lastLength
                || StreamHeader.timestampEvent(buffer, offset) != feed.lastEvent
                || StreamHeader.sequence(buffer, offset) != feed.lastSequence) {
            return false;
        }
        int at = 0;
        for (; at + Long.BYTES <= length; at += Long.BYTES) {
            if (buffer.getLong(offset + at) != feed.last.getLong(at)) {
                return false;
            }
        }
        for (; at < length; at++) {
            if (buffer.getByte(offset + at) != feed.last.getByte(at)) {
                return false;
            }
        }
        return true;
    }

    private void switchTo(final StreamFeed feed, final StreamFeed.FeedSink next) {
        final StreamFeed.FeedSink previous = feed.current;
        final ShardLink previousLink = feed.currentLink;
        feed.current = next;
        feed.currentLink = next.link();
        feed.pending = null;
        feed.pendingLink = null;
        resetStatus(feed);
        if (previous != null) {
            stopReading(previous.subscription());
        }
        retireIfUnused(previousLink);
    }

    private static void resetStatus(final StreamFeed feed) {
        feed.statusPosition = -1;
        feed.statusSessionId = 0;
        feed.statusDropped = -1;
        feed.statusReconnecting = false;
        feed.dropsRose = false;
        feed.behindSinceNanos = 0;
    }

    private int check(final StreamFeed feed) {
        final StreamFeed.FeedSink sink = feed.current;
        if (sink == null) {
            return 0;
        }
        final boolean stalled = stalled(feed, sink);
        final StreamFeed.BadReason lost = stalled ? StreamFeed.BadReason.STALLED : lost(feed, sink);
        feed.dropsRose = false;
        int work = 0;
        if (lost != null && !feed.bad()) {
            work = markBad(feed, lost);
        }
        if (stalled) {
            resubscribe(feed);
        }
        return work;
    }

    /** Why the stream can't be trusted now, or null. */
    private StreamFeed.BadReason lost(final StreamFeed feed, final StreamFeed.FeedSink sink) {
        final ShardLink link = feed.currentLink;
        if (link != null && link.statusSeen && now - link.lastStatusNanos > SHARD_SILENT_NANOS) {
            return StreamFeed.BadReason.SHARD_SILENT;
        }
        if (now - feed.lastArrivalNanos > SILENT_NANOS) {
            return StreamFeed.BadReason.SILENT;
        }
        if (feed.joined && sink.subscription().imageCount() == 0) {
            return StreamFeed.BadReason.IMAGE_GONE;
        }
        if (feed.statusReconnecting) {
            return StreamFeed.BadReason.RECONNECTING;
        }
        return feed.dropsRose ? StreamFeed.BadReason.DROPS : null;
    }

    /**
     * Behind the shard's publication for {@link #STALLED_NANOS}, or reading no image of that publication at all (the
     * shard restarted or opened it again): positions are compared only within one publication.
     */
    private boolean stalled(final StreamFeed feed, final StreamFeed.FeedSink sink) {
        if (feed.statusPosition < 0) {
            feed.behindSinceNanos = 0;
            return false;
        }
        final Image image = sink.subscription().imageBySessionId(feed.statusSessionId);
        if (image != null && image.position() >= feed.statusPosition) {
            feed.behindSinceNanos = 0;
            return false;
        }
        if (feed.behindSinceNanos == 0) {
            feed.behindSinceNanos = now;
            return false;
        }
        return now - feed.behindSinceNanos > STALLED_NANOS;
    }

    /** Stops reading the stalled subscription; the control thread closes it and only then subscribes again. */
    private void resubscribe(final StreamFeed feed) {
        if (feed.resubscribing || feed.current == null) {
            return;
        }
        feed.resubscribing = true;
        final StreamFeed.FeedSink stalled = feed.current;
        feed.current = null;
        ingress.remove(stalled.subscription());
        afterRemoval.add(new Close(stalled.subscription()));
        afterRemoval.add(new Resubscribe(feed, stalled.link()));
    }

    /** Its last record again, flagged bad data; nothing to repeat before the first one arrives. */
    private int markBad(final StreamFeed feed, final StreamFeed.BadReason reason) {
        if (feed.lastLength == 0) {
            feed.markBad(reason, feed.statusDropped);
            return 0;
        }
        flagged.putBytes(0, feed.last, 0, feed.lastLength);
        flagged.putByte(FLAGS_OFFSET, (byte) (flagged.getByte(FLAGS_OFFSET) | (1 << StreamHeader.BAD_DATA_BIT)));
        if (ring.tryPublishRaw(flagged, 0, feed.lastTemplateId, feed.lastLength)) {
            feed.markBad(reason, feed.statusDropped);
            return 1;
        }
        return 0;
    }

    private void stopReading(final Subscription subscription) {
        ingress.remove(subscription);
        afterRemoval.add(new Close(subscription));
    }

    /** A shard no stream reads now or is moving to: stop reading its status and let the control thread forget it. */
    private void retireIfUnused(final ShardLink link) {
        if (link == null || link.retired) {
            return;
        }
        for (int i = 0; i < feeds.size(); i++) {
            final StreamFeed feed = feeds.get(i);
            if (feed.currentLink == link || feed.pendingLink == link) {
                return;
            }
        }
        link.retired = true;
        ingress.remove(link.status);
        afterRemoval.add(new Retire(link));
    }

    private void handOver(final Object message) {
        if (!toControl.offer(message)) {
            throw new IllegalStateException("The market data control thread is too far behind");
        }
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.HOT_PATH;
    }

    @Override
    public String roleName() {
        return "shard-feed";
    }
}
