package group.gnometrading.collectors;

import group.gnometrading.collector.MarketDataCollector;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.SequencedEventHandler;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sequencer.StreamRecordEventAdapter;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * One stream a shard records: the ring its market publishes to and the minute files the collector agent writes from
 * it. A stream whose collector throws is failed: its ring is still drained, so the connection it shares never blocks,
 * but nothing more is written until the runtime replaces it.
 */
final class ShardStream {

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

    ShardStream(
            final int streamId,
            final String name,
            final SequencedRingBuffer<Mbp10Schema> ring,
            final MarketDataCollector collector) {
        this(streamId, name, ring, collector, new StreamRecordEventAdapter(collector));
    }

    ShardStream(
            final int streamId,
            final String name,
            final SequencedRingBuffer<Mbp10Schema> ring,
            final MarketDataCollector collector,
            final SequencedEventHandler record) {
        this.streamId = streamId;
        this.name = name;
        this.ring = ring;
        this.collector = collector;
        this.record = record;
        // The one poller gates the ring: once the collector fails it keeps reading and drops the records, as a
        // poller that stopped would block the producer, the whole connection the stream shares.
        this.poller = ring.createPoller(this::onRecord);
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

    /** Bounds the minutes this stream uploads, in epoch nanos (see {@code Assignment.Streams}). Any thread. */
    void writeWindow(final long fromNanos, final long untilNanos) {
        collector.writeFrom(fromNanos);
        collector.writeUntil(untilNanos);
    }

    /** On the agent's thread, once nothing more is published: hands the open minutes to the uploader. */
    void flush() {
        collector.flush();
    }
}
