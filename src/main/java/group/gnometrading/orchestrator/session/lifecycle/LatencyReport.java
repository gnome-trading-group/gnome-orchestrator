package group.gnometrading.orchestrator.session.lifecycle;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.latency.LatencyKey;
import group.gnometrading.latency.MinuteHistograms;
import group.gnometrading.latency.MinuteSummary;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.sequencer.metrics.MetricRecorder;
import group.gnometrading.sequencer.metrics.MetricStream;
import java.util.List;

/**
 * The session's latency, a closed minute at a time, read on the heartbeat thread from each recording thread's
 * {@link MinuteHistograms}. Each closed minute goes out once as a sample of its thread's kind ({@code latency.strategy},
 * {@code latency.oms}, {@code latency.orders}), and once to the journal's {@code _latency} stream (one row per key that
 * recorded anything), so the history outlives the samples' retention. Values in nanoseconds.
 */
public final class LatencyReport {

    /** The journal stream's name. */
    public static final String STREAM = "_latency";

    private static final long NANOS_PER_MILLI = 1_000_000;
    // About 230 bytes a key: a part stays near 12 KB.
    static final int KEYS_PER_SAMPLE = 50;

    private final MinuteHistograms[] sets;
    private final String[] kinds;
    private final SampleBuffer samples;
    private final MinuteSummary[] summaries;
    // The minute each set's summary holds; NONE until one has been read.
    private final long[] held;

    private final MetricStream rows;
    private final int minuteColumn;
    private final int metricColumn;
    private final int exchangeColumn;
    private final int actionColumn;
    private final int kindColumn;
    private final int countColumn;
    private final int p50Column;
    private final int p90Column;
    private final int p99Column;
    private final int p999Column;
    private final int maxColumn;

    /**
     * Declares the journal stream on {@code recorder}; at wiring, before the heartbeat thread starts.
     *
     * @param kinds each set's sample kind, in the same order
     */
    public LatencyReport(
            final List<MinuteHistograms> sets,
            final List<String> kinds,
            final MetricRecorder recorder,
            final SampleBuffer samples) {
        if (sets.size() != kinds.size()) {
            throw new IllegalArgumentException("A sample kind for each latency set");
        }
        this.sets = sets.toArray(new MinuteHistograms[0]);
        this.kinds = kinds.toArray(new String[0]);
        this.samples = samples;
        this.summaries = new MinuteSummary[this.sets.length];
        this.held = new long[this.sets.length];
        for (int i = 0; i < this.sets.length; i++) {
            this.summaries[i] = new MinuteSummary(this.sets[i].capacity());
            this.held[i] = MinuteHistograms.NONE;
        }
        this.rows = recorder.createStream(STREAM);
        this.minuteColumn = rows.addLongColumn("minute_start");
        this.metricColumn = rows.addIntColumn("metric");
        this.exchangeColumn = rows.addIntColumn("exchange_id");
        this.actionColumn = rows.addIntColumn("action");
        this.kindColumn = rows.addIntColumn("kind");
        this.countColumn = rows.addLongColumn("count");
        this.p50Column = rows.addLongColumn("p50");
        this.p90Column = rows.addLongColumn("p90");
        this.p99Column = rows.addLongColumn("p99");
        this.p999Column = rows.addLongColumn("p999");
        this.maxColumn = rows.addLongColumn("max");
        rows.freeze();
    }

    /** Reads each set's newly closed minute, journaling and sampling it; one that raced a flip is read next time. */
    public void sample() {
        for (int i = 0; i < sets.length; i++) {
            final long closed = sets[i].closedMinute();
            if (closed != MinuteHistograms.NONE && closed != held[i] && sets[i].summarize(summaries[i])) {
                held[i] = summaries[i].minute();
                journal(sets[i], summaries[i]);
                if (summaries[i].any()) {
                    sampleOf(i);
                }
            }
        }
    }

    private void journal(final MinuteHistograms set, final MinuteSummary summary) {
        for (int key = 0; key < summary.keys(); key++) {
            if (summary.count(key) == 0) {
                continue;
            }
            final LatencyKey label = set.label(key);
            rows.begin();
            rows.setLong(minuteColumn, summary.minute());
            rows.setInt(metricColumn, label.metricCode());
            rows.setInt(exchangeColumn, label.exchangeId());
            rows.setInt(actionColumn, label.actionCode());
            rows.setInt(kindColumn, label.kindCode());
            rows.setLong(countColumn, summary.count(key));
            rows.setLong(p50Column, summary.p50(key));
            rows.setLong(p90Column, summary.p90(key));
            rows.setLong(p99Column, summary.p99(key));
            rows.setLong(p999Column, summary.p999(key));
            rows.setLong(maxColumn, summary.max(key));
            rows.commit();
        }
    }

    // A minute's samples: its keys that recorded anything, in parts of at most KEYS_PER_SAMPLE, the first at the
    // minute's
    // start and each next a millisecond on (one sample per kind and moment is the registry's key), so a session on many
    // venues never outgrows a sample.
    private void sampleOf(final int set) {
        final MinuteSummary summary = summaries[set];
        int recorded = 0;
        for (int key = 0; key < summary.keys(); key++) {
            if (summary.count(key) > 0) {
                recorded++;
            }
        }
        final int parts = (recorded + KEYS_PER_SAMPLE - 1) / KEYS_PER_SAMPLE;
        int key = 0;
        for (int part = 0; part < parts; part++) {
            final JsonEncoder json = samples.begin(kinds[set], summary.minute() / NANOS_PER_MILLI + part);
            try {
                json.writeObjectEntry("part", part)
                        .writeComma()
                        .writeObjectEntry("parts", parts)
                        .writeComma()
                        .writeString("keys")
                        .writeColon()
                        .writeArrayStart();
                int written = 0;
                for (; key < summary.keys() && written < KEYS_PER_SAMPLE; key++) {
                    if (summary.count(key) == 0) {
                        continue;
                    }
                    if (written > 0) {
                        json.writeComma();
                    }
                    writeKey(json, sets[set].label(key), summary, key);
                    written++;
                }
                json.writeArrayEnd();
                samples.commit();
            } catch (RuntimeException e) {
                samples.abandon();
                throw e;
            }
        }
    }

    private static void writeKey(
            final JsonEncoder json, final LatencyKey label, final MinuteSummary summary, final int key) {
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
}
