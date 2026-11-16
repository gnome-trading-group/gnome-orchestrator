package group.gnometrading.orchestrator.shard;

import group.gnometrading.orchestrator.shard.feeds.WebSocketFeedOpener;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.BitUtil;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RecordDescriptor;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

/**
 * One connection's raw frames on their way from its reader to {@link RawCaptureAgent}, which compresses them into
 * minute files. Compressing on the reader held a frame up to ~0.5 ms whenever a zstd block filled; here the reader only
 * copies the frame, already laid out as the file holds it (see {@link RawDataCollector}).
 *
 * <p>A frame longer than one ring message goes as a {@link #FRAME} message and {@link #CONTINUATION}s, written only
 * once the ring has room for all of them, so the agent never sees part of a frame.
 *
 * <p>When the agent falls behind, the reader waits up to {@code maxWaitNanos} for room and then drops the frame, and
 * keeps dropping without waiting until half the ring is free: a stuck agent costs the reader one wait, and costs
 * raw capture, never the books. Every wait and drop is counted, and the agent logs drops.
 */
final class RawCaptureQueue implements WebSocketFeedOpener.RawCapture {

    static final int FRAME = 1;
    static final int CONTINUATION = 2;

    private static final int OPEN = 0;
    private static final int CLOSING = 1;
    private static final int DONE = 2;
    private static final long FLUSH_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long FLUSH_CHECK_MILLIS = 10;
    private static final int SPINS_BEFORE_YIELD = 100;

    private final String connection;
    private final RawDataCollector collector;
    private final RawCaptureAgent agent;
    private final OneToOneRingBuffer ring;
    private final AtomicBuffer ringBuffer;
    private final int maxChunk;
    private final int maxRecord;
    private final long maxWaitNanos;
    private final MessageHandler handler = this::onMessage;
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile int state = OPEN;

    // Written by the reader only.
    private final AtomicLong waits = new AtomicLong();
    private final AtomicLong drops = new AtomicLong();
    private boolean shedding;

    // The agent's only.
    private boolean writing;
    private long frames;
    private long loggedDrops;

    RawCaptureQueue(
            final String connection,
            final RawDataCollector collector,
            final RawCaptureAgent agent,
            final int ringBytes,
            final long maxWaitNanos) {
        this.connection = connection;
        this.collector = collector;
        this.agent = agent;
        this.ring = new OneToOneRingBuffer(new UnsafeBuffer(ByteBuffer.allocateDirect(
                BitUtil.findNextPositivePowerOfTwo(ringBytes) + RingBufferDescriptor.TRAILER_LENGTH)));
        this.ringBuffer = ring.buffer();
        this.maxChunk = ring.maxMsgLength();
        this.maxRecord = recordLength(maxChunk);
        this.maxWaitNanos = maxWaitNanos;
    }

    String connection() {
        return connection;
    }

    /** The reader's: copies the frame in, or counts it dropped. Allocates nothing. */
    @Override
    public void capture(final long recvTimestamp, final ByteBuffer buffer) {
        final int payload = buffer.remaining();
        if (payload == 0) {
            return;
        }
        if (state != OPEN || agent.stopped()) {
            drop();
            return;
        }
        // A frame may wrap the ring's end, where the ring pads to the end first: one record's worth at most.
        final int needed = spaceFor(RawDataCollector.HEADER_SIZE + payload) + maxRecord;
        if (needed > ring.capacity()) {
            drop();
            return;
        }
        if (shedding) {
            if (free() < ring.capacity() / 2) {
                drop();
                return;
            }
            shedding = false;
        }
        if (free() < needed && !awaitRoom(needed)) {
            shedding = true;
            drop();
            return;
        }
        write(recvTimestamp, buffer, payload);
    }

    private boolean awaitRoom(final int needed) {
        waits.lazySet(waits.get() + 1);
        final long deadline = System.nanoTime() + maxWaitNanos;
        int spins = 0;
        while (free() < needed) {
            if (agent.stopped() || System.nanoTime() - deadline >= 0) {
                return false;
            }
            if (++spins < SPINS_BEFORE_YIELD) {
                Thread.onSpinWait();
            } else {
                Thread.yield();
            }
        }
        return true;
    }

    private void write(final long recvTimestamp, final ByteBuffer buffer, final int payload) {
        int position = buffer.position();
        int remaining = RawDataCollector.HEADER_SIZE + payload;
        boolean first = true;
        while (remaining > 0) {
            final int chunk = Math.min(maxChunk, remaining);
            final int index = ring.tryClaim(first ? FRAME : CONTINUATION, chunk);
            if (index < 0) {
                throw new IllegalStateException("Raw capture ring for " + connection + " refused room it had");
            }
            int at = index;
            int bytes = chunk;
            if (first) {
                ringBuffer.putLong(at, recvTimestamp, ByteOrder.LITTLE_ENDIAN);
                ringBuffer.putInt(at + Long.BYTES, payload, ByteOrder.LITTLE_ENDIAN);
                at += RawDataCollector.HEADER_SIZE;
                bytes -= RawDataCollector.HEADER_SIZE;
                first = false;
            }
            // Absolute: the gateway parses the message from this same position next.
            ringBuffer.putBytes(at, buffer, position, bytes);
            ring.commit(index);
            position += bytes;
            remaining -= chunk;
        }
    }

    private void drop() {
        drops.lazySet(drops.get() + 1);
    }

    private int free() {
        return ring.capacity() - ring.size();
    }

    private int spaceFor(final int total) {
        final int rest = total % maxChunk;
        return (total / maxChunk) * maxRecord + (rest == 0 ? 0 : recordLength(rest));
    }

    private static int recordLength(final int messageLength) {
        return BitUtil.align(messageLength + RecordDescriptor.HEADER_LENGTH, RecordDescriptor.ALIGNMENT);
    }

    /** The agent's: writes up to {@code limit} messages into the minute files. */
    int drain(final int limit) {
        return ring.read(handler, limit);
    }

    private void onMessage(final int type, final MutableDirectBuffer buffer, final int index, final int length) {
        if (type == FRAME) {
            frames++;
            writing = collector.startFrame(buffer.getLong(index, ByteOrder.LITTLE_ENDIAN));
        }
        if (writing) {
            collector.append(buffer, index, length);
        }
    }

    /** The agent's: how full the ring is, in thousandths. */
    long fillPermille() {
        return (long) ring.size() * 1_000 / ring.capacity();
    }

    /** The agent's: whether its reader has stopped and everything it wrote is in the files. */
    boolean drained() {
        return state == CLOSING && ring.size() == 0;
    }

    /** The agent's, or the flushing thread's once the agent has stopped: hands the minutes over; nothing follows. */
    void finish() {
        try {
            collector.flush();
        } finally {
            state = DONE;
            done.countDown();
        }
    }

    /** The agent's, when writing failed: nothing more is captured here, and the reader drops without waiting. */
    void abandon() {
        state = DONE;
        try {
            collector.flush();
        } finally {
            done.countDown();
        }
    }

    long frames() {
        return frames;
    }

    long waits() {
        return waits.get();
    }

    long drops() {
        return drops.get();
    }

    /** The agent's: drops since it last asked, to log. */
    long newDrops() {
        final long total = drops.get();
        final long fresh = total - loggedDrops;
        loggedDrops = total;
        return fresh;
    }

    /**
     * Once the reader has stopped: waits for the agent to write everything queued and hand the minutes over, or does it
     * here if the agent has stopped too.
     */
    @Override
    public void flush() {
        if (state == OPEN) {
            state = CLOSING;
        }
        final long deadline = System.nanoTime() + FLUSH_TIMEOUT_NANOS;
        try {
            while (!done.await(FLUSH_CHECK_MILLIS, TimeUnit.MILLISECONDS)) {
                if (agent.stopped()) {
                    // Its thread has finished its last pass, so nothing else touches the ring or the files.
                    finishHere();
                    return;
                }
                if (System.nanoTime() - deadline >= 0) {
                    agent.flushTimedOut(this);
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void finishHere() {
        if (state == DONE) {
            return;
        }
        int drained;
        do {
            drained = drain(Integer.MAX_VALUE);
        } while (drained > 0);
        finish();
    }
}
