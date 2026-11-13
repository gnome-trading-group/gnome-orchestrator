package group.gnometrading.orchestrator.marketdata;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.metrics.SampleBuffer;

/**
 * The {@code feed.shards} sample, every interval, from a measuring {@link ShardFeedAgent} ({@link FeedMetrics}): how many
 * streams it reads and how many are bad now; per shard it reads, the records it sent and, since the last sample, the
 * longest network time, in-shard time and gap between its statuses; and the streams that trailed their shard's
 * publication furthest, at most {@link #WORST}. Runs on the sample poster's thread; allocates nothing once
 * constructed.
 */
public final class FeedSampler {

    public static final String KIND = "feed.shards";
    static final int WORST = 10;

    private final SampleBuffer samples;
    private final ShardFeedAgent agent;
    private final long intervalMs;
    private final StreamFeed[] worst = new StreamFeed[WORST];
    private final long[] worstBytes = new long[WORST];
    private long nextAtMs;

    FeedSampler(final SampleBuffer samples, final ShardFeedAgent agent, final long intervalMs) {
        this.samples = samples;
        this.agent = agent;
        this.intervalMs = intervalMs;
    }

    /** @return whether it sampled */
    public boolean sampleIfDue(final long nowMs) {
        if (nowMs < nextAtMs) {
            return false;
        }
        nextAtMs = nowMs + intervalMs;
        final StreamFeed[] feeds = agent.reading();
        final ShardLink[] links = agent.watched();
        int bad = 0;
        int worstCount = 0;
        for (final StreamFeed feed : feeds) {
            if (feed.bad()) {
                bad++;
            }
            worstCount = offer(worstCount, feed, feed.takeBehindPeakBytes());
        }
        final JsonEncoder json = samples.begin(KIND, nowMs);
        try {
            json.writeObjectEntry("intervalMs", intervalMs)
                    .writeComma()
                    .writeObjectEntry("streams", feeds.length)
                    .writeComma()
                    .writeObjectEntry("bad", bad)
                    .writeComma()
                    .writeString("shards")
                    .writeColon()
                    .writeArrayStart();
            for (int i = 0; i < links.length; i++) {
                final ShardLink link = links[i];
                final long records = link.records();
                if (i > 0) {
                    json.writeComma();
                }
                json.writeObjectStart()
                        .writeObjectEntry("shardId", link.shardId)
                        .writeComma()
                        .writeObjectEntry("records", records - link.sampledRecords)
                        .writeComma()
                        .writeObjectEntry("networkPeakNanos", link.takeNetworkPeakNanos())
                        .writeComma()
                        .writeObjectEntry("shardWaitPeakNanos", link.takeShardWaitPeakNanos())
                        .writeComma()
                        .writeObjectEntry("statusGapPeakNanos", link.takeStatusGapPeakNanos())
                        .writeObjectEnd();
                link.sampledRecords = records;
            }
            json.writeArrayEnd()
                    .writeComma()
                    .writeString("worstBehind")
                    .writeColon()
                    .writeArrayStart();
            for (int i = 0; i < worstCount; i++) {
                if (i > 0) {
                    json.writeComma();
                }
                json.writeObjectStart()
                        .writeObjectEntry("streamId", worst[i].streamId)
                        .writeComma()
                        .writeObjectEntry("name", worst[i].name())
                        .writeComma()
                        .writeObjectEntry("behindPeakBytes", worstBytes[i])
                        .writeObjectEnd();
            }
            json.writeArrayEnd();
            samples.commit();
        } catch (RuntimeException e) {
            samples.abandon();
            throw e;
        } finally {
            for (int i = 0; i < WORST; i++) {
                worst[i] = null;
            }
        }
        return true;
    }

    /** Keeps the {@link #WORST} largest, largest first; returns how many are kept. */
    private int offer(final int count, final StreamFeed feed, final long bytes) {
        if (bytes <= 0 || (count == WORST && bytes <= worstBytes[WORST - 1])) {
            return count;
        }
        final int kept = Math.min(count + 1, WORST);
        int at = kept - 1;
        while (at > 0 && worstBytes[at - 1] < bytes) {
            worst[at] = worst[at - 1];
            worstBytes[at] = worstBytes[at - 1];
            at--;
        }
        worst[at] = feed;
        worstBytes[at] = bytes;
        return kept;
    }
}
