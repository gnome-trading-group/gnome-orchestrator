package group.gnometrading.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SchedulingLatencyProbeTest {

    private static SchedulingLatencyProbe probe() {
        return new SchedulingLatencyProbe(1_000_000);
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
}
