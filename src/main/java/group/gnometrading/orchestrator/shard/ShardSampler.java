package group.gnometrading.orchestrator.shard;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.metrics.SampleBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * The shard's {@code shard.streams} sample, every interval: how many streams it runs and in which states, its
 * connections, and, summed over its streams since the last sample, what it sent sessions and what got in the way: offers
 * a full publication held back, records lapped (lost), re-sends; records collected, publishes that found a ring full
 * (which held a venue reader back, delaying its receive stamps) and the worst collector backlog. Then the streams that
 * waited longest in the shard and those held back most, at most {@link #WORST} each, by name: never every stream, as a
 * shard runs hundreds.
 *
 * <p>Runs on the sample poster's thread; each tick it also looks at every ring's backlog, so the peak is of a look a
 * second, not of every record. Allocates nothing once constructed.
 */
final class ShardSampler {

    static final String KIND = "shard.streams";
    static final int WORST = 10;
    private static final int STATES = 256;

    private final SampleBuffer samples;
    private final CollectorHeartbeat.Shard shard;
    private final IntSupplier uploadsQueued;
    private final LongSupplier uploadsFailed;
    private final long intervalMs;
    private final int[] states = new int[STATES];
    private final Worst worstWait = new Worst();
    private final Worst worstHeld = new Worst();
    private long backlogPeak;
    private long previousUploadsFailed;
    private long nextAtMs;

    /** The {@link #WORST} largest values seen since {@link #clear}, with their streams, largest first. */
    private static final class Worst {
        final ShardStream[] streams = new ShardStream[WORST];
        final long[] values = new long[WORST];
        int count;

        void clear() {
            for (int i = 0; i < count; i++) {
                streams[i] = null;
            }
            count = 0;
        }

        void offer(final ShardStream stream, final long value) {
            if (value <= 0) {
                return;
            }
            if (count < WORST) {
                count++;
            } else if (value <= values[WORST - 1]) {
                return;
            }
            int at = count - 1;
            while (at > 0 && values[at - 1] < value) {
                streams[at] = streams[at - 1];
                values[at] = values[at - 1];
                at--;
            }
            streams[at] = stream;
            values[at] = value;
        }
    }

    ShardSampler(
            final SampleBuffer samples,
            final CollectorHeartbeat.Shard shard,
            final IntSupplier uploadsQueued,
            final LongSupplier uploadsFailed,
            final long intervalMs) {
        this.samples = samples;
        this.shard = shard;
        this.uploadsQueued = uploadsQueued;
        this.uploadsFailed = uploadsFailed;
        this.intervalMs = intervalMs;
    }

    /** Looks at every ring's backlog, and samples if one is due; @return whether it did */
    boolean sampleIfDue(final long nowMs) {
        final List<ShardStream> streams = shard.streams();
        for (int i = 0; i < streams.size(); i++) {
            backlogPeak = Math.max(backlogPeak, streams.get(i).ring().backlog());
        }
        if (nowMs < nextAtMs) {
            return false;
        }
        final boolean first = nextAtMs == 0;
        nextAtMs = nowMs + intervalMs;
        if (!first) {
            sample(nowMs, streams);
        } else {
            // Nothing to compare with yet: the first sample's changes would be every stream's whole life.
            for (int i = 0; i < streams.size(); i++) {
                take(streams.get(i));
                streams.get(i).takeWaitPeakNanos();
            }
            previousUploadsFailed = uploadsFailed.getAsLong();
            backlogPeak = 0;
        }
        return !first;
    }

    private void sample(final long nowMs, final List<ShardStream> streams) {
        Arrays.fill(states, 0);
        worstWait.clear();
        worstHeld.clear();
        long publications = 0;
        long offered = 0;
        long held = 0;
        long lapped = 0;
        long resent = 0;
        long records = 0;
        long fullWaits = 0;
        long waitPeak = 0;
        int streamsHeld = 0;
        for (int i = 0; i < streams.size(); i++) {
            final ShardStream stream = streams.get(i);
            states[StreamTransitions.stateOf(stream) & 0xFF]++;
            if (stream.publication() != null) {
                publications++;
            }
            final long streamOffered = stream.offered() - stream.sampledOffered;
            final long streamHeld = stream.held() - stream.sampledHeld;
            final long streamLapped = stream.lapped() - stream.sampledLapped;
            final long streamResent = stream.resent() - stream.sampledResent;
            final long streamRecords = stream.records() - stream.sampledRecords;
            final long streamFullWaits = stream.ring().fullWaits() - stream.sampledFullWaits;
            final long streamWaitPeak = stream.takeWaitPeakNanos();
            take(stream);
            offered += streamOffered;
            held += streamHeld;
            lapped += streamLapped;
            resent += streamResent;
            records += streamRecords;
            fullWaits += streamFullWaits;
            waitPeak = Math.max(waitPeak, streamWaitPeak);
            if (streamHeld > 0) {
                streamsHeld++;
            }
            worstWait.offer(stream, streamWaitPeak);
            worstHeld.offer(stream, streamHeld + streamLapped);
        }
        final long failed = uploadsFailed.getAsLong();
        final List<CollectorHeartbeat.Connection> connections = shard.connections();
        int reconnecting = 0;
        for (int i = 0; i < connections.size(); i++) {
            if (connections.get(i).reconnecting()) {
                reconnecting++;
            }
        }

        final JsonEncoder json = samples.begin(KIND, nowMs);
        try {
            json.writeObjectEntry("intervalMs", intervalMs)
                    .writeComma()
                    .writeObjectEntry("generation", shard.generation())
                    .writeComma()
                    .writeObjectEntry("streams", streams.size())
                    .writeComma()
                    .writeString("states")
                    .writeColon()
                    .writeObjectStart();
            boolean firstState = true;
            for (int state = 0; state < STATES; state++) {
                if (states[state] == 0) {
                    continue;
                }
                if (!firstState) {
                    json.writeComma();
                }
                firstState = false;
                json.writeObjectEntry(StreamTransitions.name((byte) state), states[state]);
            }
            json.writeObjectEnd()
                    .writeComma()
                    .writeObjectEntry("connections", connections.size())
                    .writeComma()
                    .writeObjectEntry("reconnecting", reconnecting)
                    .writeComma()
                    .writeObjectEntry("probes", shard.probes())
                    .writeComma()
                    .writeObjectEntry("probeMismatches", shard.probeMismatches())
                    .writeComma()
                    .writeString("publish")
                    .writeColon()
                    .writeObjectStart()
                    .writeObjectEntry("publications", publications)
                    .writeComma()
                    .writeObjectEntry("offered", offered)
                    .writeComma()
                    .writeObjectEntry("held", held)
                    .writeComma()
                    .writeObjectEntry("streamsHeld", streamsHeld)
                    .writeComma()
                    .writeObjectEntry("lapped", lapped)
                    .writeComma()
                    .writeObjectEntry("resent", resent)
                    .writeComma()
                    .writeObjectEntry("waitPeakNanos", waitPeak)
                    .writeObjectEnd()
                    .writeComma()
                    .writeString("collect")
                    .writeColon()
                    .writeObjectStart()
                    .writeObjectEntry("records", records)
                    .writeComma()
                    .writeObjectEntry("ringFullWaits", fullWaits)
                    .writeComma()
                    .writeObjectEntry("backlogPeak", backlogPeak)
                    .writeComma()
                    .writeObjectEntry("uploadsQueued", uploadsQueued.getAsInt())
                    .writeComma()
                    .writeObjectEntry("uploadsFailed", failed - previousUploadsFailed)
                    .writeObjectEnd()
                    .writeComma();
            writeWorst(json, "worstWait", worstWait, "waitPeakNanos");
            json.writeComma();
            writeWorst(json, "worstHeld", worstHeld, "heldAndLapped");
            samples.commit();
        } catch (RuntimeException e) {
            samples.abandon();
            throw e;
        } finally {
            previousUploadsFailed = failed;
            backlogPeak = 0;
            worstWait.clear();
            worstHeld.clear();
        }
    }

    private static void take(final ShardStream stream) {
        stream.sampledOffered = stream.offered();
        stream.sampledHeld = stream.held();
        stream.sampledLapped = stream.lapped();
        stream.sampledResent = stream.resent();
        stream.sampledRecords = stream.records();
        stream.sampledFullWaits = stream.ring().fullWaits();
    }

    private static void writeWorst(final JsonEncoder json, final String name, final Worst worst, final String value) {
        json.writeString(name).writeColon().writeArrayStart();
        for (int i = 0; i < worst.count; i++) {
            if (i > 0) {
                json.writeComma();
            }
            json.writeObjectStart()
                    .writeObjectEntry("streamId", worst.streams[i].streamId())
                    .writeComma()
                    .writeObjectEntry("name", worst.streams[i].name())
                    .writeComma()
                    .writeObjectEntry(value, worst.values[i])
                    .writeObjectEnd();
        }
        json.writeArrayEnd();
    }
}
