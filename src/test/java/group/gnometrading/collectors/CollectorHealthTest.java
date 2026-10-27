package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CollectorHealthTest {

    private long now;
    private long cycles;
    private boolean uploaderStuck;
    private long lastTick;
    private final CollectorHealth health =
            new CollectorHealth(() -> cycles, () -> lastTick, at -> uploaderStuck, () -> now);

    @Test
    void healthyWhileTheAgentKeepsCycling() {
        for (int i = 0; i < 10; i++) {
            now += TimeUnit.SECONDS.toNanos(20);
            cycles++;
            lastTick = now;
            assertTrue(health.getAsBoolean());
        }
    }

    @Test
    void unhealthyOnceTheAgentStopsCyclingForThirtySeconds() {
        assertTrue(health.getAsBoolean());

        now += CollectorHealth.AGENT_STALL_NANOS;
        assertTrue(health.getAsBoolean());
        now += 1;
        assertFalse(health.getAsBoolean());

        cycles++;
        assertTrue(health.getAsBoolean(), "recovers once the agent moves again");
    }

    @Test
    void unhealthyOnceTheRuntimeStopsTickingForFiveMinutes() {
        now = TimeUnit.HOURS.toNanos(1);
        lastTick = now;
        now += CollectorHealth.RUNTIME_STALL_NANOS;
        cycles++;
        assertTrue(health.getAsBoolean());

        now += 1;
        cycles++;
        assertFalse(health.getAsBoolean(), "a hung tick stops assignments, rolls and retries though the agent cycles");

        lastTick = now;
        assertTrue(health.getAsBoolean(), "recovers once a tick finishes");
    }

    @Test
    void aRuntimeThatHasNotTickedYetHasUntilFiveMinutesAfterStart() {
        now += CollectorHealth.RUNTIME_STALL_NANOS;
        cycles++;
        assertTrue(health.getAsBoolean());
        now += 1;
        cycles++;
        assertFalse(health.getAsBoolean());
    }

    @Test
    void unhealthyWhileAnUploadIsStuck() {
        uploaderStuck = true;

        assertFalse(health.getAsBoolean());
    }
}
