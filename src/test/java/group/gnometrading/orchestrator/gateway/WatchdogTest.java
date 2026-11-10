package group.gnometrading.orchestrator.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.logging.NullLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WatchdogTest {

    private final AtomicLong now = new AtomicLong();
    private final AtomicLong lane = new AtomicLong();
    private final AtomicLong reader = new AtomicLong();
    private final AtomicBoolean readerPaused = new AtomicBoolean();
    private final List<String> exits = new ArrayList<>();
    private final List<Throwable> causes = new ArrayList<>();
    private final Watchdog watchdog = new Watchdog(
            new NullLogger(),
            List.of(
                    new Watchdog.CheckIn("lane", null, this.lane::get, () -> false, 100),
                    new Watchdog.CheckIn("reader", null, this.reader::get, this.readerPaused::get, 100)),
            this.now::get,
            (status, cause) -> this.exits.add(cause.getMessage()));

    @Test
    void threadsThatKeepMovingAreLeftAlone() {
        for (int i = 0; i < 50; i++) {
            this.lane.incrementAndGet();
            this.reader.incrementAndGet();
            this.now.addAndGet(50);
            this.watchdog.check();
        }
        assertTrue(this.exits.isEmpty());
    }

    @Test
    void aThreadStuckPastTheStallTimeExitsTheProcessOnce() {
        this.watchdog.check();
        this.now.addAndGet(100);
        this.reader.incrementAndGet();
        this.watchdog.check();
        assertTrue(this.exits.isEmpty(), "not past the stall time yet");
        this.now.addAndGet(1);
        this.reader.incrementAndGet();
        this.watchdog.check();
        this.now.addAndGet(500);
        this.watchdog.check();
        assertEquals(1, this.exits.size());
        assertTrue(this.exits.get(0).startsWith("lane made no progress"), this.exits.get(0));
    }

    @Test
    void aReaderPausedForAReconnectIsExcused() {
        this.readerPaused.set(true);
        for (int i = 0; i < 10; i++) {
            this.lane.incrementAndGet();
            this.now.addAndGet(50);
            this.watchdog.check();
        }
        assertTrue(this.exits.isEmpty());
        this.readerPaused.set(false);
        this.now.addAndGet(101);
        this.lane.incrementAndGet();
        this.watchdog.check();
        assertEquals(1, this.exits.size(), "stuck once its pause is over");
    }

    @Test
    void aStalledThreadsExitSaysWhereItIsStuck() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch waiting = new CountDownLatch(1);
        final Thread stuck = new Thread(() -> waitInTheRegistry(waiting, release), "watchdog-test-stuck");
        stuck.start();
        waiting.await();
        try {
            final AtomicLong passes = new AtomicLong();
            final Watchdog named = new Watchdog(
                    new NullLogger(),
                    List.of(new Watchdog.CheckIn("control", "watchdog-test-stuck", passes::get, () -> false, 100)),
                    this.now::get,
                    (status, cause) -> this.causes.add(cause));
            named.check();
            this.now.addAndGet(101);
            named.check();

            assertEquals(1, this.causes.size());
            assertTrue(
                    this.causes.get(0).getMessage().contains("waitInTheRegistry"),
                    this.causes.get(0).getMessage());
            assertTrue(this.causes.get(0).getStackTrace().length > 0, "the stuck thread's stack, not the watchdog's");
        } finally {
            release.countDown();
            stuck.join();
        }
    }

    private static void waitInTheRegistry(final CountDownLatch waiting, final CountDownLatch release) {
        waiting.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
