package group.gnometrading.orchestrator.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DirectBufferPoolTest {

    private final DirectBufferPool pool = new DirectBufferPool();

    @Test
    void buffersAreDirectAndRoundedUpToAPowerOfTwo() {
        ByteBuffer buffer = pool.acquire(5_000);
        assertTrue(buffer.isDirect());
        assertEquals(8_192, buffer.capacity());
        assertEquals(1 << 16, pool.acquire(1 << 16).capacity(), "a power of two is not rounded up further");
    }

    @Test
    void smallRequestsGetTheSmallestBuffer() {
        assertEquals(4_096, pool.acquire(0).capacity());
        assertEquals(4_096, pool.acquire(1).capacity());
        assertEquals(4_096, pool.acquire(4_096).capacity());
    }

    @Test
    void aReleasedBufferIsReusedCleared() {
        ByteBuffer first = pool.acquire(10_000);
        first.put(new byte[100]).limit(200);
        pool.release(first);

        ByteBuffer second = pool.acquire(9_000);
        assertSame(first, second, "same capacity class");
        assertEquals(0, second.position());
        assertEquals(second.capacity(), second.limit());
    }

    @Test
    void aBufferGoesBackToItsOwnCapacity() {
        ByteBuffer large = pool.acquire(1 << 20);
        pool.release(large);

        ByteBuffer small = pool.acquire(4_096);
        assertNotSame(large, small);
        assertEquals(4_096, small.capacity());
        assertSame(large, pool.acquire(1 << 20));
    }

    @Test
    void aRequestBeyondTheLargestBufferIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> pool.acquire((1 << 30) + 1));
    }

    @Test
    void buffersReleasedOnAnotherThreadAreAllReused() throws Exception {
        // A minute is filled on the data thread and released on the uploader's.
        BlockingQueue<ByteBuffer> handedOff = new ArrayBlockingQueue<>(64);
        Thread uploader = new Thread(() -> {
            try {
                for (int i = 0; i < 1_000; i++) {
                    pool.release(handedOff.take());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        uploader.start();

        Set<ByteBuffer> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < 1_000; i++) {
            ByteBuffer buffer = pool.acquire(4_096);
            distinct.add(buffer);
            assertTrue(handedOff.offer(buffer, 10, TimeUnit.SECONDS));
        }
        uploader.join(TimeUnit.SECONDS.toMillis(10));

        // In flight at most: the queue's 64, the one the uploader has taken but not yet released, and the one being
        // acquired.
        assertTrue(distinct.size() <= 66, "buffers ever in use: " + distinct.size());
        List<ByteBuffer> drained = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i++) {
            drained.add(pool.acquire(4_096));
        }
        assertTrue(distinct.containsAll(drained), "every buffer came back to the pool");
    }
}
