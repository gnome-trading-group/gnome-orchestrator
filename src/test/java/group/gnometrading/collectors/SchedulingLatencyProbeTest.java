package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SchedulingLatencyProbeTest {

    private static SchedulingLatencyProbe probe() {
        return new SchedulingLatencyProbe(1_000_000);
    }

    @Test
    void everyValueFallsInsideItsBucket() {
        for (long micros = 0; micros < 5_000_000; micros += micros < 100 ? 1 : micros / 7) {
            int bucket = SchedulingLatencyProbe.bucketOf(micros);
            long upper = SchedulingLatencyProbe.upperMicros(bucket);
            long lower = bucket == 0 ? 0 : SchedulingLatencyProbe.upperMicros(bucket - 1);
            assertTrue(lower <= micros && micros < upper, micros + " not in [" + lower + ", " + upper + ")");
            assertTrue(upper - lower <= Math.max(1, lower / 8), "bucket " + bucket + " is wider than 12.5%");
        }
    }

    @Test
    void reportsTheTailOfAMostlyPromptDistribution() {
        SchedulingLatencyProbe probe = probe();
        for (int i = 0; i < 9_990; i++) {
            probe.record(50_000);
        }
        for (int i = 0; i < 10; i++) {
            probe.record(80_000_000);
        }

        assertEquals(52, probe.percentileMicros(0.5));
        assertEquals(52, probe.percentileMicros(0.99));
        assertEquals(52, probe.percentileMicros(0.999));
        SchedulingLatencyProbe.Window summary = probe.summarize(10_000_000_000L);
        assertEquals(10_000, summary.samples());
        assertEquals(80_000, summary.maxMicros());
        assertEquals(10, summary.over1ms());
        assertEquals(10, summary.over5ms());
    }

    @Test
    void aPercentileNeverExceedsTheLargestValueSeen() {
        SchedulingLatencyProbe probe = probe();
        probe.record(1_100_000);

        assertEquals(1_100, probe.percentileMicros(0.999));
    }

    @Test
    void summarizingStartsAFreshWindow() {
        SchedulingLatencyProbe probe = probe();
        probe.record(9_000_000);
        probe.summarize(10_000_000_000L);

        SchedulingLatencyProbe.Window next = probe.summarize(10_000_000_000L);
        assertEquals(0, next.samples());
        assertEquals(0, next.maxMicros());
        assertEquals(0, next.over1ms());
    }

    @Test
    void earlyWakeUpsCountAsOnTime() {
        SchedulingLatencyProbe probe = probe();
        probe.record(-20_000);

        assertEquals(0, probe.percentileMicros(0.5));
    }

    @Test
    void theHeartbeatReportsAWindowUnderSnakeCaseKeys() {
        SchedulingLatencyProbe probe = probe();
        probe.record(2_000_000);

        Map<String, Object> latency = CollectorHeartbeat.latency(probe.summarize(15_000_000_000L));

        assertEquals(15.0, latency.get("window_s"));
        assertEquals(1L, latency.get("samples"));
        assertEquals(2_000L, latency.get("max_us"));
        assertEquals(1L, latency.get("over1ms"));
    }
}
