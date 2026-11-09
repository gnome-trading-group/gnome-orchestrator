package group.gnometrading.orchestrator.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.logging.NullLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WatchdogTest {

    private final AtomicLong now = new AtomicLong();
    private final AtomicLong lane = new AtomicLong();
    private final AtomicLong reader = new AtomicLong();
    private final AtomicBoolean readerPaused = new AtomicBoolean();
    private final List<String> exits = new ArrayList<>();
    private final Watchdog watchdog = new Watchdog(
            new NullLogger(),
            List.of(
                    new Watchdog.CheckIn("lane", this.lane::get, () -> false, 100),
                    new Watchdog.CheckIn("reader", this.reader::get, this.readerPaused::get, 100)),
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
}
