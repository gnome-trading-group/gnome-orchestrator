package group.gnometrading.orchestrator.marketdata;

import group.gnometrading.latency.LatencyGrid;
import group.gnometrading.latency.MinuteHistograms;
import group.gnometrading.metrics.MetricCounters;
import group.gnometrading.orchestrator.aeron.AeronReservedValues;
import java.util.List;

/**
 * What a {@link ShardFeedAgent} measures when its process asks it to (an order gateway; a session's reader measures
 * none of this), written on the feed thread alone, in nanoseconds on this host's wall clock:
 *
 * <ul>
 *   <li>{@code feed.network}: a record's arrival here (the feed's pass) minus its shard's send stamp: the network, both
 *       media drivers and this thread's polling. Shard and host clocks differ by their bounds (tens of µs with PHC).
 *   <li>{@code feed.shard_wait}: the send stamp minus the shard's receipt: its time inside the shard, as this reader saw
 *       it.
 * </ul>
 *
 * <p>Counters ({@link #counters}): passes and the longest gap between two, records, records with no stamp (a shard
 * from before stamps), offers the ring refused (the reader behind it full), stalled streams subscribed again, bad spells
 * by reason, and the most bytes any stream trailed its shard's publication. Per shard, on its {@link ShardLink}: the
 * records it sent and the longest network and in-shard times since the sampler last took them.
 */
public final class FeedMetrics {

    public static final int NETWORK = 201;
    public static final int SHARD_WAIT = 202;
    public static final String KIND = "latency.feed";

    private static final int[] NO_EXCHANGES = {};
    private static final String[] NO_NAMES = {};
    private static final String[] NO_LABEL = {""};
    private static final StreamFeed.BadReason[] REASONS = StreamFeed.BadReason.values();

    private final MinuteHistograms histograms = new MinuteHistograms(2 * LatencyGrid.size(0, 1, 1));
    private final LatencyGrid network =
            new LatencyGrid(histograms, NETWORK, "feed.network", NO_EXCHANGES, NO_NAMES, NO_LABEL, NO_LABEL);
    private final LatencyGrid shardWait =
            new LatencyGrid(histograms, SHARD_WAIT, "feed.shard_wait", NO_EXCHANGES, NO_NAMES, NO_LABEL, NO_LABEL);
    private final MetricCounters counters = new MetricCounters(8 + REASONS.length);
    private final int passes = counters.counter("passes");
    private final int passGapPeakNanos = counters.peak("passGapPeakNanos");
    private final int records = counters.counter("records");
    private final int unstamped = counters.counter("unstamped");
    private final int ringRefusals = counters.counter("ringRefusals");
    private final int resubscribes = counters.counter("resubscribes");
    private final int behindBytesPeak = counters.peak("behindBytesPeak");
    private final int[] badSpells = new int[REASONS.length];
    private long lastPassNanos;

    public FeedMetrics() {
        for (final StreamFeed.BadReason reason : REASONS) {
            badSpells[reason.ordinal()] = counters.counter("bad." + reason.name());
        }
    }

    void pass(final long nowNanos, final long wallNanos) {
        counters.increment(passes);
        if (lastPassNanos != 0) {
            counters.max(passGapPeakNanos, nowNanos - lastPassNanos);
        }
        lastPassNanos = nowNanos;
        histograms.roll(wallNanos);
    }

    /** A record passed on, from {@code link}, its frame's reserved value and receipt as given, here at {@code wall}. */
    void record(final ShardLink link, final long reservedValue, final long recvNanos, final long wallNanos) {
        counters.increment(records);
        link.received();
        final long sentNanos = AeronReservedValues.sentNanos(reservedValue);
        if (sentNanos == 0) {
            counters.increment(unstamped);
            return;
        }
        final long inNetwork = wallNanos - sentNanos;
        network.record(0, 0, 0, inNetwork, wallNanos);
        link.network(inNetwork);
        if (recvNanos > 0) {
            final long inShard = sentNanos - recvNanos;
            shardWait.record(0, 0, 0, inShard, wallNanos);
            link.shardWait(inShard);
        }
    }

    void ringRefused() {
        counters.increment(ringRefusals);
    }

    void resubscribed() {
        counters.increment(resubscribes);
    }

    void badSpell(final StreamFeed.BadReason reason) {
        counters.increment(badSpells[reason.ordinal()]);
    }

    void behind(final long bytes) {
        counters.max(behindBytesPeak, bytes);
    }

    public MetricCounters counters() {
        return counters;
    }

    public List<MinuteHistograms> sets() {
        return List.of(histograms);
    }

    public static List<String> kinds() {
        return List.of(KIND);
    }
}
