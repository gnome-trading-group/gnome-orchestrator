package group.gnometrading.orchestrator.runtime;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.util.List;

/**
 * How a process's rings kept up, as one sample every interval: per ring, the events published, the backlog (published,
 * not yet taken by its slowest gating reader) at the sample and at its worst, and the publishes that found it full and
 * waited (a producer held back by a reader). The backlog is looked at on every call, the sending thread's beat, so the
 * worst is that of the interval's looks, not of every event. Allocates nothing once constructed.
 */
public final class RingSampler {

    private final SampleBuffer samples;
    private final String kind;
    private final String[] names;
    private final SequencedRingBuffer<?>[] rings;
    private final long intervalMs;
    private final long[] previousPublished;
    private final long[] previousFullWaits;
    private final long[] backlogPeak;
    private boolean first = true;
    private long nextAtMs;

    public RingSampler(
            final SampleBuffer samples,
            final String kind,
            final List<String> names,
            final List<? extends SequencedRingBuffer<?>> rings,
            final long intervalMs) {
        if (names.size() != rings.size()) {
            throw new IllegalArgumentException("A name for each ring");
        }
        this.samples = samples;
        this.kind = kind;
        this.names = names.toArray(new String[0]);
        this.rings = rings.toArray(new SequencedRingBuffer<?>[0]);
        this.intervalMs = intervalMs;
        this.previousPublished = new long[this.rings.length];
        this.previousFullWaits = new long[this.rings.length];
        this.backlogPeak = new long[this.rings.length];
    }

    /** Looks at every ring's backlog, and takes a sample if one is due; @return whether it took one */
    public boolean sampleIfDue(final long nowMs) {
        for (int i = 0; i < rings.length; i++) {
            backlogPeak[i] = Math.max(backlogPeak[i], rings[i].backlog());
        }
        if (nowMs < nextAtMs) {
            return false;
        }
        nextAtMs = nowMs + intervalMs;
        sample(nowMs);
        return true;
    }

    private void sample(final long nowMs) {
        final JsonEncoder json = samples.begin(kind, nowMs);
        try {
            json.writeObjectEntry("intervalMs", intervalMs);
            for (int i = 0; i < rings.length; i++) {
                final SequencedRingBuffer<?> ring = rings[i];
                final long published = ring.published();
                final long fullWaits = ring.fullWaits();
                final long backlog = ring.backlog();
                json.writeComma()
                        .writeString(names[i])
                        .writeColon()
                        .writeObjectStart()
                        .writeObjectEntry("capacity", ring.capacity())
                        .writeComma()
                        .writeObjectEntry("backlog", backlog)
                        .writeComma()
                        .writeObjectEntry("backlogPeak", Math.max(backlogPeak[i], backlog));
                if (!first) {
                    json.writeComma()
                            .writeObjectEntry("published", published - previousPublished[i])
                            .writeComma()
                            .writeObjectEntry("fullWaits", fullWaits - previousFullWaits[i]);
                }
                json.writeObjectEnd();
                previousPublished[i] = published;
                previousFullWaits[i] = fullWaits;
                backlogPeak[i] = 0;
            }
            samples.commit();
        } catch (RuntimeException e) {
            samples.abandon();
            throw e;
        } finally {
            first = false;
        }
    }
}
