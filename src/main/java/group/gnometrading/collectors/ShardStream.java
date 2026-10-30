package group.gnometrading.collectors;

import group.gnometrading.collector.MarketDataCollector;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.LappingPoller;
import group.gnometrading.sequencer.SequencedEvent;
import group.gnometrading.sequencer.SequencedEventHandler;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sequencer.StreamRecordEventAdapter;
import io.aeron.Publication;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * One stream a shard records: the ring its market publishes to and the minute files the collector agent writes from
 * it. A stream whose collector throws is failed: its ring is still drained, so the connection it shares never blocks,
 * but nothing more is written until the runtime replaces it.
 */
final class ShardStream {

    /** A published stream quiet this long re-sends its last record, so a session can tell it apart from a dead one. */
    static final long RESEND_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static final int OFFER_ATTEMPTS = 3;

    private final int streamId;
    private final String name;
    private final SequencedRingBuffer<Mbp10Schema> ring;
    private final MarketDataCollector collector;
    private final SequencedEventHandler record;
    private final SequencedPoller poller;
    // Read by the heartbeat; the agent alone writes them.
    private final AtomicLong records = new AtomicLong();
    private volatile Throwable failure;
    private volatile long failedAtNanos;
    // The agent's clock for the poll in progress, so a failure inside the handler is timed.
    private long pollNanos;
    // Set by the runtime once the stream's market is placed.
    private volatile Subject subject;
    // The state the runtime last recorded for it (StreamTransitions); the runtime's thread only.
    private byte recordedState = StreamTransitions.NONE;

    // Publishing, on the publisher agent's thread; null for a stream built without (tests that only collect). It never
    // gates the ring, so a publisher that falls behind loses records rather than holding up collection.
    private final LappingPoller publishPoller;
    private final UnsafeBuffer lastRecord = new UnsafeBuffer(new byte[SequencedEvent.MAX_MESSAGE_SIZE]);
    private int lastLength;
    private long lastSentNanos;
    private long publishNanos;
    // Set by the runtime while a session leases the stream; the publisher reads it.
    private volatile StreamPublication publication;
    // Set with a new publication, so the publisher sends it the last record as soon as a session can take it.
    private volatile boolean fresh;
    // Read by the heartbeat; the publisher alone writes them.
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong backPressured = new AtomicLong();

    ShardStream(
            final int streamId,
            final String name,
            final SequencedRingBuffer<Mbp10Schema> ring,
            final MarketDataCollector collector) {
        this(streamId, name, ring, collector, new StreamRecordEventAdapter(collector), false);
    }

    ShardStream(
            final int streamId,
            final String name,
            final SequencedRingBuffer<Mbp10Schema> ring,
            final MarketDataCollector collector,
            final SequencedEventHandler record) {
        this(streamId, name, ring, collector, record, false);
    }

    /**
     * @param publishing whether the publisher agent also reads the ring, without gating it
     */
    ShardStream(
            final int streamId,
            final String name,
            final SequencedRingBuffer<Mbp10Schema> ring,
            final MarketDataCollector collector,
            final SequencedEventHandler record,
            final boolean publishing) {
        this.streamId = streamId;
        this.name = name;
        this.ring = ring;
        this.collector = collector;
        this.record = record;
        // The one poller gates the ring: once the collector fails it keeps reading and drops the records, as a
        // poller that stopped would block the producer, the whole connection the stream shares.
        this.poller = ring.createPoller(this::onRecord);
        this.publishPoller = publishing ? ring.createLappingPoller(this::onPublish) : null;
    }

    private void onPublish(final long sequence, final int templateId, final UnsafeBuffer buffer, final int length) {
        lastRecord.putBytes(0, buffer, 0, length);
        lastLength = length;
        lastSentNanos = publishNanos;
        final StreamPublication current = publication;
        if (current != null && send(current, buffer, length, true)) {
            fresh = false;
        }
    }

    /** True once offered; an end-of-term rotation answers the first offer with a retry, so it gets a few tries. */
    private boolean send(
            final StreamPublication target, final UnsafeBuffer buffer, final int length, final boolean countDrop) {
        long result = Publication.ADMIN_ACTION;
        for (int attempt = 0; attempt < OFFER_ATTEMPTS && result == Publication.ADMIN_ACTION; attempt++) {
            result = target.offer(buffer, 0, length);
        }
        if (result > 0) {
            offered.lazySet(offered.get() + 1);
            return true;
        }
        if (countDrop && StreamPublication.dropped(result)) {
            backPressured.lazySet(backPressured.get() + 1);
        }
        return false;
    }

    private void onRecord(final long sequence, final int templateId, final UnsafeBuffer buffer, final int length) {
        if (failure != null) {
            return;
        }
        try {
            record.onSequencedEvent(sequence, templateId, buffer, length);
        } catch (Throwable error) {
            failedAtNanos = pollNanos;
            failure = error;
        }
    }

    int streamId() {
        return streamId;
    }

    String name() {
        return name;
    }

    SequencedRingBuffer<Mbp10Schema> ring() {
        return ring;
    }

    Subject subject() {
        return subject;
    }

    void subject(final Subject placed) {
        this.subject = placed;
    }

    byte recordedState() {
        return recordedState;
    }

    void recordedState(final byte state) {
        this.recordedState = state;
    }

    long records() {
        return records.get();
    }

    /** Wall-clock nanos of the latest event recorded; 0 before any. */
    long lastEventNanos() {
        return collector.lastEventNanos;
    }

    Throwable failure() {
        return failure;
    }

    long failedAtNanos() {
        return failedAtNanos;
    }

    /** On the agent's thread. */
    int poll(final long nowNanos) throws Exception {
        pollNanos = nowNanos;
        final int work = poller.poll();
        if (work > 0 && failure == null) {
            records.lazySet(records.get() + work);
        }
        return work;
    }

    /**
     * On the publisher's thread: sends what arrived to the stream's publication, if it has one, and its last record
     * again once it has been quiet for {@link #RESEND_NANOS}. Returns the records read.
     */
    int publish(final long nowNanos) throws Exception {
        if (publishPoller == null) {
            return 0;
        }
        publishNanos = nowNanos;
        final int work = publishPoller.poll();
        final StreamPublication current = publication;
        if (current != null && lastLength > 0 && (fresh || nowNanos - lastSentNanos >= RESEND_NANOS)) {
            // A publication nobody has joined yet takes nothing, so a fresh one keeps trying until a session joins.
            // A re-send that doesn't go through loses nothing: the record already went out, or was counted then.
            if (send(current, lastRecord, lastLength, false)) {
                fresh = false;
            }
            lastSentNanos = nowNanos;
        }
        return work;
    }

    StreamPublication publication() {
        return publication;
    }

    /**
     * From the runtime's thread. A new publication gets the last record at once: every book record is a whole top of
     * book, so a session that joins has a book without waiting for the next change.
     */
    void publication(final StreamPublication next) {
        this.publication = next;
        this.fresh = next != null;
    }

    long offered() {
        return offered.get();
    }

    /** Records sessions never got: offers refused for back pressure, and records the publisher was lapped past. */
    long dropped() {
        return backPressured() + lapped();
    }

    /** Offers refused because a session's receive window was full: a session not reading. */
    long backPressured() {
        return backPressured.get();
    }

    /** Records the publisher fell a whole ring behind on: this shard's publisher thread not keeping up. */
    long lapped() {
        return publishPoller == null ? 0 : publishPoller.lapped();
    }

    /** Bounds the minutes this stream uploads, in epoch nanos (see {@code Assignment.Streams}). Any thread. */
    void writeWindow(final long fromNanos, final long untilNanos) {
        collector.writeFrom(fromNanos);
        collector.writeUntil(untilNanos);
    }

    /** On the agent's thread: hands over minutes that have ended by the wall clock; not a failed collector's. */
    void expire(final long wallNanos) {
        if (failure == null) {
            collector.expire(wallNanos);
        }
    }

    /** On the agent's thread, once nothing more is published: hands the open minutes to the uploader. */
    void flush() {
        collector.flush();
    }
}
