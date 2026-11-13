package group.gnometrading.orchestrator.shard;

import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.StreamHeader;
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
    // Null for a stream built without (tests that only collect or publish).
    private final ShardLatency latency;
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
    // gates the ring, so a publisher that falls behind loses records rather than holding up collection. A record the
    // publication refuses is held in the ring and offered again next pass: one venue message can carry hundreds of
    // fills, more than the publication's window, which the driver drains within milliseconds.
    private final LappingPoller publishPoller;
    private final UnsafeBuffer lastRecord = new UnsafeBuffer(new byte[SequencedEvent.MAX_MESSAGE_SIZE]);
    private int lastLength;
    private long lastSentNanos;
    private long publishNanos;
    // The publisher's wall clock for the pass in progress: each record's send stamp, read once a pass.
    private long publishWallNanos;
    // Set by the runtime while a session leases the stream; the publisher reads it.
    private volatile StreamPublication publication;
    // Set with a new publication, so the publisher sends it the last record as soon as a session can take it.
    private volatile boolean fresh;
    // Read by the heartbeat; the publisher alone writes them.
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong held = new AtomicLong();
    private final AtomicLong resent = new AtomicLong();
    // The longest a record sent since the sampler last took it spent in the shard; the sampler resets it.
    private final AtomicLong waitPeakNanos = new AtomicLong();

    // The sampler's own: each total as it last read it, so it reports this stream's change.
    long sampledOffered;
    long sampledHeld;
    long sampledLapped;
    long sampledResent;
    long sampledRecords;
    long sampledFullWaits;

    ShardStream(
            final int streamId,
            final String name,
            final SequencedRingBuffer<Mbp10Schema> ring,
            final MarketDataCollector collector) {
        this(streamId, name, ring, collector, new StreamRecordEventAdapter(collector), false, null);
    }

    ShardStream(
            final int streamId,
            final String name,
            final SequencedRingBuffer<Mbp10Schema> ring,
            final MarketDataCollector collector,
            final SequencedEventHandler record) {
        this(streamId, name, ring, collector, record, false, null);
    }

    /**
     * @param publishing whether the publisher agent also reads the ring, without gating it
     * @param latency where the collector and publisher record each record's delays; null records none
     */
    ShardStream(
            final int streamId,
            final String name,
            final SequencedRingBuffer<Mbp10Schema> ring,
            final MarketDataCollector collector,
            final SequencedEventHandler record,
            final boolean publishing,
            final ShardLatency latency) {
        this.streamId = streamId;
        this.name = name;
        this.ring = ring;
        this.collector = collector;
        this.record = record;
        this.latency = latency;
        // The one poller gates the ring: once the collector fails it keeps reading and drops the records, as a
        // poller that stopped would block the producer, the whole connection the stream shares.
        this.poller = ring.createPoller(this::onRecord);
        this.publishPoller = publishing ? ring.createControlledLappingPoller(this::onPublish) : null;
    }

    /** False holds the record for the next pass: the publication had no room for it. */
    private boolean onPublish(final long sequence, final int templateId, final UnsafeBuffer buffer, final int length) {
        final StreamPublication current = publication;
        if (current != null) {
            final long result = offer(current, buffer, length, false);
            if (StreamPublication.full(result)) {
                held.lazySet(held.get() + 1);
                return false;
            }
            if (result > 0) {
                fresh = false;
                sent();
            }
        }
        lastRecord.putBytes(0, buffer, 0, length);
        lastLength = length;
        lastSentNanos = publishNanos;
        return true;
    }

    private void sent() {
        final long waited = publishWallNanos - publishPoller.timestampNanos();
        if (waited > waitPeakNanos.get()) {
            waitPeakNanos.lazySet(waited);
        }
        if (latency != null) {
            latency.sent(publishPoller.timestampNanos(), publishWallNanos);
        }
    }

    /** The publication's answer; an end-of-term rotation answers the first offer with a retry, so it gets a few. */
    private long offer(
            final StreamPublication target, final UnsafeBuffer buffer, final int length, final boolean resend) {
        long result = Publication.ADMIN_ACTION;
        for (int attempt = 0; attempt < OFFER_ATTEMPTS && result == Publication.ADMIN_ACTION; attempt++) {
            result = resend ? target.resend(buffer, 0, length) : target.offer(buffer, 0, length, publishWallNanos);
        }
        if (result > 0) {
            offered.lazySet(offered.get() + 1);
        }
        return result;
    }

    private void onRecord(final long sequence, final int templateId, final UnsafeBuffer buffer, final int length) {
        if (failure != null) {
            return;
        }
        if (latency != null) {
            latency.received(StreamHeader.timestampEvent(buffer, 0), StreamHeader.timestampRecv(buffer, 0));
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
     *
     * @param wallNanos the wall clock for the pass, each record's send stamp
     */
    int publish(final long nowNanos, final long wallNanos) throws Exception {
        if (publishPoller == null) {
            return 0;
        }
        publishNanos = nowNanos;
        publishWallNanos = wallNanos;
        final int work = publishPoller.poll();
        final StreamPublication current = publication;
        if (current != null && lastLength > 0 && (fresh || nowNanos - lastSentNanos >= RESEND_NANOS)) {
            // A publication nobody has joined yet takes nothing, so a fresh one keeps trying until a session joins.
            // A re-send that doesn't go through loses nothing: the record already went out. It is marked so a session
            // that has it drops it, while one that just joined takes it as its book.
            if (offer(current, lastRecord, lastLength, true) > 0) {
                fresh = false;
                resent.lazySet(resent.get() + 1);
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

    /** Re-sends of the last record that went out. */
    long resent() {
        return resent.get();
    }

    /** The longest a record sent since the last call spent in the shard, receipt to send; then 0. Sampler only. */
    long takeWaitPeakNanos() {
        return waitPeakNanos.getAndSet(0);
    }

    /** Records sessions never got: those the publisher was lapped past. */
    long dropped() {
        return lapped();
    }

    /** Offers the publication refused for want of room, each retried on the next pass; a burst, not a loss. */
    long held() {
        return held.get();
    }

    /**
     * Records the publisher fell a whole ring behind on: its thread not keeping up, or a publication full for as long
     * as the ring took to fill.
     */
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
