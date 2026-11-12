package group.gnometrading.orchestrator.shard;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Direct buffers kept for reuse by a collector's minute files, so a steady stream of minutes allocates nothing once
 * the pool has grown to its peak. Capacities are powers of two; a buffer goes back to the queue of its own capacity.
 *
 * <p>Thread-safe: a minute is filled on the thread receiving data and released on the uploader's thread.
 */
public final class DirectBufferPool {

    private static final int MIN_CAPACITY_BITS = 12;
    private static final int MAX_CAPACITY_BITS = 30;

    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedQueue<ByteBuffer>[] free = new ConcurrentLinkedQueue[MAX_CAPACITY_BITS + 1];

    public DirectBufferPool() {
        for (int bits = MIN_CAPACITY_BITS; bits <= MAX_CAPACITY_BITS; bits++) {
            free[bits] = new ConcurrentLinkedQueue<>();
        }
    }

    /** A cleared direct buffer of at least {@code minCapacity} bytes. */
    ByteBuffer acquire(final int minCapacity) {
        final int bits = capacityBits(minCapacity);
        final ByteBuffer pooled = free[bits].poll();
        if (pooled != null) {
            return pooled.clear();
        }
        return ByteBuffer.allocateDirect(1 << bits);
    }

    /** Returns a buffer from {@link #acquire}; the caller must not use it afterwards. */
    void release(final ByteBuffer buffer) {
        free[capacityBits(buffer.capacity())].offer(buffer);
    }

    private static int capacityBits(final int capacity) {
        final int bits = Math.max(MIN_CAPACITY_BITS, 32 - Integer.numberOfLeadingZeros(Math.max(1, capacity) - 1));
        if (bits > MAX_CAPACITY_BITS) {
            throw new IllegalArgumentException("Buffer of " + capacity + " bytes is larger than the pool serves");
        }
        return bits;
    }
}
