package group.gnometrading.orchestrator.aeron;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Messages waiting, in order, for a publication that had no room for them: offered again from the head on the next
 * pass. One thread; fixed slots, allocates nothing once built.
 */
public final class PendingMessages {

    private final int capacity;
    private final int slotLength;
    private final UnsafeBuffer slots;
    private final int[] lengths;
    private int head;
    private int size;

    public PendingMessages(final int capacity, final int slotLength) {
        this.capacity = capacity;
        this.slotLength = slotLength;
        this.slots = new UnsafeBuffer(new byte[capacity * slotLength]);
        this.lengths = new int[capacity];
    }

    /** @return false if full or the message is longer than a slot */
    public boolean offer(final DirectBuffer source, final int offset, final int length) {
        if (this.size == this.capacity || length > this.slotLength) {
            return false;
        }
        final int index = (this.head + this.size) % this.capacity;
        this.slots.putBytes(index * this.slotLength, source, offset, length);
        this.lengths[index] = length;
        this.size++;
        return true;
    }

    public boolean isEmpty() {
        return this.size == 0;
    }

    public int size() {
        return this.size;
    }

    public int headOffset() {
        return this.head * this.slotLength;
    }

    public int headLength() {
        return this.lengths[this.head];
    }

    public void pop() {
        this.head = (this.head + 1) % this.capacity;
        this.size--;
    }

    public void clear() {
        this.head = 0;
        this.size = 0;
    }

    public UnsafeBuffer buffer() {
        return this.slots;
    }
}
