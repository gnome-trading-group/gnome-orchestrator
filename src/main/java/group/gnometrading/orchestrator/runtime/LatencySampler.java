package group.gnometrading.orchestrator.runtime;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.latency.LatencyKey;
import group.gnometrading.latency.MinuteHistograms;
import group.gnometrading.latency.MinuteSummary;
import group.gnometrading.metrics.SampleBuffer;
import java.util.List;

/**
 * A process's latency histograms as samples, a closed minute at a time: each {@link MinuteHistograms} (one per
 * recording thread) goes out once per minute as a sample of its kind, {@code {"keys": [{m, ex, exchangeId, act, kind,
 * n, p50, p90, p99, p999, max}]}} with only the keys that recorded anything, ts the minute's start. The same shape as a
 * session's latency samples, without the session's journal. Values in nanoseconds. Allocates nothing once constructed.
 */
public final class LatencySampler {

    private static final long NANOS_PER_MILLI = 1_000_000;

    private final SampleBuffer samples;
    private final MinuteHistograms[] sets;
    private final String[] kinds;
    private final MinuteSummary[] summaries;
    private final long[] held;

    /** @param kinds each set's sample kind, in the same order */
    public LatencySampler(final SampleBuffer samples, final List<MinuteHistograms> sets, final List<String> kinds) {
        if (sets.size() != kinds.size()) {
            throw new IllegalArgumentException("A sample kind for each latency set");
        }
        this.samples = samples;
        this.sets = sets.toArray(new MinuteHistograms[0]);
        this.kinds = kinds.toArray(new String[0]);
        this.summaries = new MinuteSummary[this.sets.length];
        this.held = new long[this.sets.length];
        for (int i = 0; i < this.sets.length; i++) {
            this.summaries[i] = new MinuteSummary(this.sets[i].capacity());
            this.held[i] = MinuteHistograms.NONE;
        }
    }

    /** Samples each set's newly closed minute; one that raced a flip is read next time. @return whether any was */
    public boolean sampleIfDue(final long nowMs) {
        boolean sampled = false;
        for (int i = 0; i < sets.length; i++) {
            final long closed = sets[i].closedMinute();
            if (closed != MinuteHistograms.NONE && closed != held[i] && sets[i].summarize(summaries[i])) {
                held[i] = summaries[i].minute();
                if (summaries[i].any()) {
                    write(i);
                    sampled = true;
                }
            }
        }
        return sampled;
    }

    private void write(final int set) {
        final MinuteSummary summary = summaries[set];
        final JsonEncoder json = samples.begin(kinds[set], summary.minute() / NANOS_PER_MILLI);
        try {
            json.writeString("keys").writeColon().writeArrayStart();
            boolean first = true;
            for (int key = 0; key < summary.keys(); key++) {
                if (summary.count(key) == 0) {
                    continue;
                }
                if (!first) {
                    json.writeComma();
                }
                first = false;
                final LatencyKey label = sets[set].label(key);
                json.writeObjectStart()
                        .writeObjectEntry("m", label.metric())
                        .writeComma()
                        .writeObjectEntry("ex", label.exchange())
                        .writeComma()
                        .writeObjectEntry("exchangeId", label.exchangeId())
                        .writeComma()
                        .writeObjectEntry("act", label.action())
                        .writeComma()
                        .writeObjectEntry("kind", label.kind())
                        .writeComma()
                        .writeObjectEntry("n", summary.count(key))
                        .writeComma()
                        .writeObjectEntry("p50", summary.p50(key))
                        .writeComma()
                        .writeObjectEntry("p90", summary.p90(key))
                        .writeComma()
                        .writeObjectEntry("p99", summary.p99(key))
                        .writeComma()
                        .writeObjectEntry("p999", summary.p999(key))
                        .writeComma()
                        .writeObjectEntry("max", summary.max(key))
                        .writeObjectEnd();
            }
            json.writeArrayEnd();
            samples.commit();
        } catch (RuntimeException e) {
            samples.abandon();
            throw e;
        }
    }
}
