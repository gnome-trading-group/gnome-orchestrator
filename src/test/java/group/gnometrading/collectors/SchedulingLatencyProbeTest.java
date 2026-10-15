package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.logging.NullLogger;
import org.junit.jupiter.api.Test;

class SchedulingLatencyProbeTest {

    private static SchedulingLatencyProbe probe() {
        return new SchedulingLatencyProbe(new NullLogger(), 1_000_000);
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
        String summary = probe.summarize(10_000_000_000L);
        assertTrue(summary.contains("samples=10000"), summary);
        assertTrue(summary.contains("max_us=80000"), summary);
        assertTrue(summary.contains("over1ms=10 over5ms=10"), summary);
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

        String next = probe.summarize(10_000_000_000L);
        assertTrue(next.contains("samples=0") && next.contains("max_us=0") && next.contains("over1ms=0"), next);
    }

    @Test
    void earlyWakeUpsCountAsOnTime() {
        SchedulingLatencyProbe probe = probe();
        probe.record(-20_000);

        assertEquals(0, probe.percentileMicros(0.5));
    }
}
