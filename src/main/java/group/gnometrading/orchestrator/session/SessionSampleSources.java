package group.gnometrading.orchestrator.session;

import group.gnometrading.metrics.MetricCounters;
import group.gnometrading.oms.position.PositionView;
import java.util.List;
import org.agrona.concurrent.status.CountersReader;

/**
 * What the session's samples are read from: its latency histograms, a position view of the heartbeat's own, its rings, the agents' counters (by block name)
 * and the media driver's counters (null without Aeron), and how often the runtime sample is taken.
 */
record SessionSampleSources(
        SessionLatency latency,
        PositionView positions,
        SessionBuffers buffers,
        List<String> activityNames,
        List<MetricCounters> activity,
        CountersReader aeronCounters,
        long runtimeIntervalMs) {}
