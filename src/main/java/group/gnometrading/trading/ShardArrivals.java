package group.gnometrading.trading;

import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.StreamHeader;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedEvent;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.util.function.LongSupplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * A copy of every record the session takes from shards, its {@code timestampRecv} replaced by when it reached this
 * session on the monotonic clock and, for a book record, the shard's own receive stamp kept in {@code timestampSent}
 * (which feeds never set): what {@link FeedComparator} pairs against the direct feeds, after moving it onto the wall
 * clock their readers stamp with, and times the shard pipeline by. That clock builds an {@code Instant}, which escape analysis didn't always remove
 * on this thread, and this thread must not allocate. Its ring has its own sequence, so nothing here is journaled, and
 * only lapping readers, so the ingress thread never waits on it. Written by the ingress thread alone; zero allocation.
 */
final class ShardArrivals {

    static final int RING_SIZE = 1 << 12;
    private static final int RECV_OFFSET = SbeMessage.HEADER_LENGTH + StreamHeader.TIMESTAMP_RECV_OFFSET;
    static final int SENT_OFFSET = SbeMessage.HEADER_LENGTH + Mbp10Encoder.timestampSentEncodingOffset();

    private final LongSupplier nanoTime;
    private final SequencedRingBuffer<Mbp10Schema> ring =
            new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence(), RING_SIZE);
    private final UnsafeBuffer copy = new UnsafeBuffer(new byte[SequencedEvent.MAX_MESSAGE_SIZE]);

    ShardArrivals(final LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    SequencedRingBuffer<Mbp10Schema> ring() {
        return ring;
    }

    void record(final DirectBuffer buffer, final int offset, final int templateId, final int length) {
        copy.putBytes(0, buffer, offset, length);
        if (templateId == Mbp10Encoder.TEMPLATE_ID) {
            copy.putLong(SENT_OFFSET, StreamHeader.timestampRecv(buffer, offset), Statics.BYTE_ORDER);
        }
        copy.putLong(RECV_OFFSET, nanoTime.getAsLong(), Statics.BYTE_ORDER);
        ring.tryPublishRaw(copy, 0, templateId, length);
    }
}
