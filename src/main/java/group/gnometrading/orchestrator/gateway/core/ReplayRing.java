package group.gnometrading.orchestrator.gateway.core;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The last reports sent to one session, by report sequence, so a session that reconnects can be sent what it missed.
 * Fixed slots in one buffer; the newest overwrites the oldest. One thread; allocates nothing once built.
 */
public final class ReplayRing {

    /** Room for the largest message numbered for a session: an execution report or an order status. */
    public static final int SLOT_LENGTH = 256;

    private final UnsafeBuffer slots;
    private final int[] lengths;
    // Which order each message is about, so egress can tell when an ended order's last report has gone out.
    private final long[] counters;
    private final int mask;
    private final int capacity;
    private long newest;
    // The first sequence this ring numbered: 1, or one past where a session the gateway had forgotten left off.
    private long first = 1;

    /** @param capacity a power of two */
    public ReplayRing(final int capacity) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("A replay ring's capacity is a power of two, not " + capacity);
        }
        this.slots = new UnsafeBuffer(new byte[capacity * SLOT_LENGTH]);
        this.lengths = new int[capacity];
        this.counters = new long[capacity];
        this.mask = capacity - 1;
        this.capacity = capacity;
    }

    /**
     * Numbers the first message held after {@code sequence}, for a session the gateway had forgotten that has every report
     * up to it already: numbered from 1 again, its next reports would look to it like ones it has, and be dropped. Before
     * anything is held.
     */
    public void continueAfter(final long sequence) {
        if (this.newest != 0) {
            throw new IllegalStateException("A replay ring continues a numbering only before it holds anything");
        }
        this.newest = sequence;
        this.first = sequence + 1;
    }

    /**
     * Holds the next message, numbered one past the newest.
     *
     * @return its sequence
     */
    public long append(final DirectBuffer source, final int offset, final int length, final long counter) {
        if (length > SLOT_LENGTH) {
            throw new IllegalArgumentException("A " + length + "-byte message doesn't fit a replay slot");
        }
        final long sequence = ++this.newest;
        final int index = (int) (sequence & this.mask);
        this.slots.putBytes(index * SLOT_LENGTH, source, offset, length);
        this.lengths[index] = length;
        this.counters[index] = counter;
        return sequence;
    }

    /** The newest sequence held, 0 before any. */
    public long newest() {
        return this.newest;
    }

    /** The oldest sequence still held; one past {@link #newest} when nothing is. */
    public long oldest() {
        return Math.max(this.first, this.newest - this.capacity + 1);
    }

    public boolean holds(final long sequence) {
        return sequence >= oldest() && sequence <= this.newest;
    }

    /** Where the message {@code sequence} starts in {@link #buffer}; only while it {@linkplain #holds is held}. */
    public int offset(final long sequence) {
        return (int) (sequence & this.mask) * SLOT_LENGTH;
    }

    public long counter(final long sequence) {
        return this.counters[(int) (sequence & this.mask)];
    }

    public int length(final long sequence) {
        return this.lengths[(int) (sequence & this.mask)];
    }

    public UnsafeBuffer buffer() {
        return this.slots;
    }
}
