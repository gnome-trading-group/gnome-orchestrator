package group.gnometrading.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SecretCacheTest {

    private long now;
    private final AtomicInteger fetches = new AtomicInteger();
    private final SecretCache cache = new SecretCache(id -> id + "#" + fetches.incrementAndGet(), () -> now);

    @Test
    void aSecretIsFetchedOnceWhileItIsFresh() {
        assertEquals("kalshi#1", cache.get("kalshi"));
        now += SecretCache.TTL_NANOS - 1;
        assertEquals("kalshi#1", cache.get("kalshi"));
        assertEquals(1, fetches.get());
    }

    @Test
    void anExpiredSecretIsFetchedAgainSoARotatedKeyIsPickedUp() {
        cache.get("kalshi");
        now += SecretCache.TTL_NANOS;

        assertEquals("kalshi#2", cache.get("kalshi"));
    }

    @Test
    void eachSecretIsCachedOnItsOwn() {
        assertEquals("kalshi#1", cache.get("kalshi"));
        assertEquals("pm-us#2", cache.get("pm-us"));
    }

    @Test
    void streamsAskingAtOnceShareOneFetch() throws Exception {
        CountDownLatch inFetch = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger slowFetches = new AtomicInteger();
        SecretCache slow = new SecretCache(
                id -> {
                    slowFetches.incrementAndGet();
                    inFetch.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return "key";
                },
                System::nanoTime);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> slow.get("kalshi")));
        }
        inFetch.await(5, TimeUnit.SECONDS);
        release.countDown();

        for (Future<String> result : results) {
            assertEquals("key", result.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, slowFetches.get());
        pool.shutdown();
    }
}
