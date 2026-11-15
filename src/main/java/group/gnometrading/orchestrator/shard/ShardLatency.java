package group.gnometrading.orchestrator.shard;

import group.gnometrading.latency.LatencyGrid;
import group.gnometrading.latency.MinuteHistograms;
import java.util.List;
import org.agrona.concurrent.EpochNanoClock;

/**
 * A shard's latency histograms, a set per recording thread, in nanoseconds on the shard host's wall clock, each keyed by
 * the record's source (a shard's partition can mix sources):
 *
 * <ul>
 *   <li>{@code shard.venue_to_recv} (collector): a record's receipt minus the venue's event time, the venue's clock
 *       against ours, so its level carries the venue's clock error; a change in it is the network or the venue. A book
 *       published again rather than received (a stream joining a market, a connection roll) repeats an event time the
 *       stream already had, and isn't counted: its age is the market's quiet, not delay.
 *   <li>{@code shard.decode} (collector): the record's ring publish minus its receipt, the venue reader's work on it:
 *       capturing the raw frame, decoding it, updating the book and encoding the record, plus earlier frames from the
 *       same socket read.
 *   <li>{@code shard.wait} (publisher): the publisher's pass that took the record minus its ring publish: waiting for
 *       the pass and, when its publication was full, held in the ring for later passes.
 *   <li>{@code shard.send} (publisher): the moment its offer returned minus the pass's start, which is the send stamp
 *       every record of the pass carries; how far that stamp understates when a record actually went.
 *   <li>{@code shard.venue_hold} (collector): the venue's send time minus its event time, for a venue that says when
 *       it sent the message (Kalshi's {@code sending_ts_ms}; Lighter's frame time on its books): how long the venue
 *       held it. Kalshi's is one clock; Lighter's crosses its API server's and its sequencer's.
 *   <li>{@code shard.venue_network} (collector): the receipt minus the venue's send time: the network from the venue.
 *       Crosses clocks, the venue's to the millisecond.
 * </ul>
 *
 * <p>Where the venue says when it sent, {@code venue_to_recv} = {@code venue_hold} + {@code venue_network}.
 *
 * <p>A record's time in the shard is decode + wait + send. Metric codes name metrics in numeric records; never reuse
 * one (sessions' are below 100, gateways' from 200).
 */
final class ShardLatency {

    static final int VENUE_TO_RECV = 101;
    static final int WAIT = 102;
    static final int DECODE = 103;
    static final int SEND = 104;
    static final int VENUE_HOLD = 105;
    static final int VENUE_NETWORK = 106;
    static final String COLLECTOR_KIND = "latency.shard.collector";
    static final String PUBLISHER_KIND = "latency.shard.publisher";

    private static final String[] NO_LABEL = {""};

    private final EpochNanoClock clock;
    private final MinuteHistograms collector;
    private final MinuteHistograms publisher;
    private final LatencyGrid venueToRecv;
    private final LatencyGrid decode;
    private final LatencyGrid venueHold;
    private final LatencyGrid venueNetwork;
    private final LatencyGrid wait;
    private final LatencyGrid send;

    /**
     * @param clock read after each offer, for {@code shard.send}: the clock the send stamps come from
     * @param sourceIds the sources the shard may read, each a key; a record from any other goes under none
     */
    ShardLatency(final EpochNanoClock clock, final int[] sourceIds, final String[] sourceCodes) {
        this.clock = clock;
        final int keys = LatencyGrid.size(sourceIds.length, 1, 1);
        this.collector = new MinuteHistograms(4 * keys);
        this.publisher = new MinuteHistograms(2 * keys);
        this.venueToRecv = grid(collector, VENUE_TO_RECV, "shard.venue_to_recv", sourceIds, sourceCodes);
        this.decode = grid(collector, DECODE, "shard.decode", sourceIds, sourceCodes);
        this.venueHold = grid(collector, VENUE_HOLD, "shard.venue_hold", sourceIds, sourceCodes);
        this.venueNetwork = grid(collector, VENUE_NETWORK, "shard.venue_network", sourceIds, sourceCodes);
        this.wait = grid(publisher, WAIT, "shard.wait", sourceIds, sourceCodes);
        this.send = grid(publisher, SEND, "shard.send", sourceIds, sourceCodes);
    }

    private static LatencyGrid grid(
            final MinuteHistograms set, final int code, final String metric, final int[] ids, final String[] codes) {
        return new LatencyGrid(set, code, metric, ids, codes, NO_LABEL, NO_LABEL);
    }

    /**
     * Collector thread: a record of {@code sourceId}'s received at {@code recvNanos} and put in its ring at {@code
     * ringNanos}, for an event the venue stamped {@code eventNanos} and sent at {@code sentNanos} (0 where it doesn't
     * say).
     *
     * @param fresh whether the record carries a new venue event, so its venue time means something
     */
    void received(
            final int sourceId,
            final long eventNanos,
            final long sentNanos,
            final long recvNanos,
            final long ringNanos,
            final boolean fresh) {
        if (recvNanos <= 0) {
            return;
        }
        if (fresh && eventNanos > 0) {
            venueToRecv.record(sourceId, 0, 0, recvNanos - eventNanos, recvNanos);
            if (sentNanos >= eventNanos) {
                venueHold.record(sourceId, 0, 0, sentNanos - eventNanos, recvNanos);
            }
        }
        // Negative across the venue's clock and ours means nothing to add up; left out, as other cross-host spans are.
        if (fresh && sentNanos > 0 && recvNanos >= sentNanos) {
            venueNetwork.record(sourceId, 0, 0, recvNanos - sentNanos, recvNanos);
        }
        if (ringNanos > 0) {
            decode.record(sourceId, 0, 0, ringNanos - recvNanos, recvNanos);
        }
    }

    /** Collector thread, every second or so: closes a minute that ended with nothing recorded. */
    void rollCollector(final long wallNanos) {
        collector.roll(wallNanos);
    }

    /** Publisher thread: a record of {@code sourceId}'s put in its ring at {@code ringNanos}, sent in the pass begun at {@code passNanos}. */
    void sent(final int sourceId, final long ringNanos, final long passNanos) {
        wait.record(sourceId, 0, 0, passNanos - ringNanos, passNanos);
        send.record(sourceId, 0, 0, clock.nanoTime() - passNanos, passNanos);
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
