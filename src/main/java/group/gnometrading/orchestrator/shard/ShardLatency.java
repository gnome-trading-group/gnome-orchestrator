package group.gnometrading.orchestrator.shard;

import group.gnometrading.latency.LatencyGrid;
import group.gnometrading.latency.MinuteHistograms;
import java.util.List;

/**
 * A shard's latency histograms, a set per recording thread, in nanoseconds on the shard host's wall clock:
 *
 * <ul>
 *   <li>{@code shard.venue_to_recv} (collector): a record's receipt minus the venue's event time, the venue's clock
 *       against ours, so its level carries the venue's clock error; a change in it is the network or the venue.
 *   <li>{@code shard.wait} (publisher): a record's send minus its receipt, everything it spent inside the shard: waiting
 *       for the publisher's pass and, when its publication was full, held in the ring for later passes.
 * </ul>
 *
 * <p>A shard collects one venue's markets, so neither is split by exchange. Metric codes name metrics in numeric
 * records; never reuse one (sessions' are below 100, gateways' from 200).
 */
final class ShardLatency {

    static final int VENUE_TO_RECV = 101;
    static final int WAIT = 102;
    static final String COLLECTOR_KIND = "latency.shard.collector";
    static final String PUBLISHER_KIND = "latency.shard.publisher";

    private static final int[] NO_EXCHANGES = {};
    private static final String[] NO_NAMES = {};
    private static final String[] NO_LABEL = {""};

    private final MinuteHistograms collector = new MinuteHistograms(LatencyGrid.size(0, 1, 1));
    private final MinuteHistograms publisher = new MinuteHistograms(LatencyGrid.size(0, 1, 1));
    private final LatencyGrid venueToRecv = grid(collector, VENUE_TO_RECV, "shard.venue_to_recv");
    private final LatencyGrid wait = grid(publisher, WAIT, "shard.wait");

    private static LatencyGrid grid(final MinuteHistograms set, final int code, final String metric) {
        return new LatencyGrid(set, code, metric, NO_EXCHANGES, NO_NAMES, NO_LABEL, NO_LABEL);
    }

    /** Collector thread: a record received at {@code recvNanos} for an event the venue stamped {@code eventNanos}. */
    void received(final long eventNanos, final long recvNanos) {
        if (eventNanos > 0 && recvNanos > 0) {
            venueToRecv.record(0, 0, 0, recvNanos - eventNanos, recvNanos);
        }
    }

    /** Collector thread, every second or so: closes a minute that ended with nothing recorded. */
    void rollCollector(final long wallNanos) {
        collector.roll(wallNanos);
    }

    /** Publisher thread: a record sent at {@code sentNanos} that the shard received at {@code recvNanos}. */
    void sent(final long recvNanos, final long sentNanos) {
        wait.record(0, 0, 0, sentNanos - recvNanos, sentNanos);
    }

    /** Publisher thread, each pass. */
    void rollPublisher(final long wallNanos) {
        publisher.roll(wallNanos);
    }

    List<MinuteHistograms> sets() {
        return List.of(collector, publisher);
    }

    static List<String> kinds() {
        return List.of(COLLECTOR_KIND, PUBLISHER_KIND);
    }
}
