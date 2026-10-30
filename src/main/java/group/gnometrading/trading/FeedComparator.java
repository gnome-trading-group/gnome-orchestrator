package group.gnometrading.trading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.StreamHeader;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.sequencer.LappingPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.utils.LatencyHistogram;
import group.gnometrading.utils.SchedulingLatencyProbe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.agrona.DirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Times the shard path against the session's own venue connections for the same streams: every record that arrives
 * both ways is paired, and the difference in when it reached this session is the shard hop. Both sides are timed on
 * this host (direct records by their reader on the wall clock, shard records by {@link ShardArrivals} on the monotonic
 * clock, moved onto the wall clock by an offset sampled every second), so no clock sync between hosts enters it.
 *
 * <p>Venues stamp in milliseconds and carry no sequence, so a record is identified by its bytes less
 * {@code timestampRecv} and the flags (which each path sets for itself), and the k-th record with one identity on one
 * side pairs with the k-th on the other. Records flagged bad data are counted, not paired. A record the other side
 * hasn't matched within {@link #EXPIRE_AFTER_NANOS} counts as seen on one side only: a snapshot each connection took
 * at its own time, a shard re-send dropped as a repeat, or a record one path missed.
 *
 * <p>A venue sends to each connection on its own schedule, so the hop also carries that jitter (on some venues
 * hundreds of milliseconds either way). The transit, arrival here less the shard's own receive stamp (which
 * {@link ShardArrivals} keeps in {@code timestampSent}), is the shard pipeline alone: reader, publisher, Aeron and
 * this session's ingress. It compares two hosts' wall clocks, so it carries their sync error; a negative minimum shows
 * that error.
 *
 * <p>Logs one {@code feed_compare} line per stream every {@link #REPORT_EVERY_NANOS} and a {@code feed_compare_total}
 * line per stream when closed. Reads both rings without holding their producers back.
 */
final class FeedComparator implements GnomeAgent {

    static final long REPORT_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1);
    static final long EXPIRE_AFTER_NANOS = TimeUnit.SECONDS.toNanos(10);
    static final int PENDING_CAPACITY = 1 << 13;
    private static final long EXPIRE_EVERY_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int SHARD = 0;
    private static final int DIRECT = 1;
    private static final int RECV_FROM = SbeMessage.HEADER_LENGTH + StreamHeader.TIMESTAMP_RECV_OFFSET;
    private static final int RECV_TO = RECV_FROM + Long.BYTES;
    private static final int FLAGS_AT = SbeMessage.HEADER_LENGTH + StreamHeader.FLAGS_OFFSET;
    private static final int SENT_FROM = ShardArrivals.SENT_OFFSET;
    private static final int SENT_TO = SENT_FROM + Long.BYTES;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final Logger logger;
    private final EpochNanoClock clock;
    private final LongSupplier nanoTime;
    private final SchedulingLatencyProbe probe;
    private final ObjectMapper mapper;
    private final LappingPoller shardPoller;
    private final List<LappingPoller> directPollers = new ArrayList<>();
    private final Int2ObjectHashMap<Pairing> byStream = new Int2ObjectHashMap<>();
    private final List<Pairing> pairings = new ArrayList<>();
    private long nextExpireNanos;
    private long nextReportNanos;
    private long windowStartNanos;
    private long startNanos;
    // Wall clock less monotonic clock, to move shard arrivals onto the readers' clock.
    private long wallOffsetNanos;
    private long shardLappedReported;
    private long directLappedReported;

    /**
     * @param streamIds the streams compared
     * @param clock the wall clock the direct feeds' readers stamp with
     * @param nanoTime the monotonic clock {@code shard} is stamped with
     * @param direct the session's own feeds' rings, stamped by their readers on {@code clock}
     * @param shard every record taken from shards, stamped on arrival on {@code nanoTime}
     * @param probe this host's scheduling lateness, reported with each window; null for none
     */
    FeedComparator(
            final Logger logger,
            final EpochNanoClock clock,
            final LongSupplier nanoTime,
            final int[] streamIds,
            final List<SequencedRingBuffer<?>> direct,
            final SequencedRingBuffer<?> shard,
            final SchedulingLatencyProbe probe,
            final ObjectMapper mapper) {
        this.logger = logger;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.probe = probe;
        this.mapper = mapper;
        for (final int streamId : streamIds) {
            final Pairing pairing = new Pairing(streamId);
            byStream.put(streamId, pairing);
            pairings.add(pairing);
        }
        this.shardPoller = shard.createLappingPoller(
                (sequence, templateId, buffer, length) -> onRecord(SHARD, templateId, buffer, length));
        for (final SequencedRingBuffer<?> ring : direct) {
            directPollers.add(ring.createLappingPoller(
                    (sequence, templateId, buffer, length) -> onRecord(DIRECT, templateId, buffer, length)));
        }
    }

    @Override
    public void onStart() {
        sampleWallOffset();
        startNanos = clock.nanoTime();
        windowStartNanos = startNanos;
        nextReportNanos = startNanos + REPORT_EVERY_NANOS;
    }

    @Override
    public int doWork() throws Exception {
        int work = shardPoller.poll();
        for (int i = 0; i < directPollers.size(); i++) {
            work += directPollers.get(i).poll();
        }
        final long now = clock.nanoTime();
        if (now >= nextExpireNanos) {
            nextExpireNanos = now + EXPIRE_EVERY_NANOS;
            sampleWallOffset();
            expire(now - EXPIRE_AFTER_NANOS);
        }
        if (now >= nextReportNanos) {
            nextReportNanos = now + REPORT_EVERY_NANOS;
            for (final Map<String, Object> line : report(now)) {
                log(line);
            }
        }
        return work;
    }

    @Override
    public void onClose() {
        try {
            for (final Map<String, Object> line : totals(clock.nanoTime())) {
                log(line);
            }
        } catch (RuntimeException error) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Feed compare: final report failed: %s", error);
        }
    }

    private void onRecord(final int side, final int templateId, final DirectBuffer buffer, final int length) {
        if (length < FLAGS_AT + 1) {
            return;
        }
        final Pairing pairing = byStream.get(StreamHeader.streamId(buffer, 0));
        if (pairing == null) {
            return;
        }
        final int flags = StreamHeader.flags(buffer, 0);
        if (StreamHeader.isSet(flags, StreamHeader.BAD_DATA_BIT)) {
            pairing.window.bad[side]++;
            pairing.total.bad[side]++;
            return;
        }
        final long arrival = StreamHeader.timestampRecv(buffer, 0) + (side == SHARD ? wallOffsetNanos : 0);
        if (side == SHARD && templateId == Mbp10Encoder.TEMPLATE_ID && length >= SENT_TO) {
            final long transit = arrival - buffer.getLong(SENT_FROM, Statics.BYTE_ORDER);
            pairing.window.transit.record(transit);
            pairing.total.transit.record(transit);
        }
        pairing.onRecord(
                side,
                identity(buffer, length),
                arrival,
                arrival - StreamHeader.timestampEvent(buffer, 0),
                StreamHeader.isSet(flags, StreamHeader.SNAPSHOT_BIT));
    }

    private void sampleWallOffset() {
        wallOffsetNanos = clock.nanoTime() - nanoTime.getAsLong();
    }

    /** FNV-1a over the record less {@code timestampRecv}, {@code timestampSent} and the flags byte. */
    static long identity(final DirectBuffer buffer, final int length) {
        long hash = FNV_OFFSET;
        for (int i = 0; i < length; i++) {
            if ((i >= RECV_FROM && i < RECV_TO) || (i >= SENT_FROM && i < SENT_TO) || i == FLAGS_AT) {
                continue;
            }
            hash = (hash ^ (buffer.getByte(i) & 0xFF)) * FNV_PRIME;
        }
        return hash;
    }

    private void expire(final long before) {
        for (int i = 0; i < pairings.size(); i++) {
            pairings.get(i).expire(before);
        }
    }

    /** One line per stream for the window ending now, then a host line; starts a new window. */
    List<Map<String, Object>> report(final long now) {
        final double seconds = (now - windowStartNanos) / 1e9;
        windowStartNanos = now;
        final List<Map<String, Object>> lines = new ArrayList<>();
        for (final Pairing pairing : pairings) {
            lines.add(line("feed_compare", pairing.streamId, seconds, pairing.window));
            pairing.window.reset();
        }
        final Map<String, Object> host = new LinkedHashMap<>();
        host.put("kind", "feed_compare_host");
        host.put("window_s", seconds);
        final long shardLapped = shardPoller.lapped();
        long directLapped = 0;
        for (final LappingPoller poller : directPollers) {
            directLapped += poller.lapped();
        }
        host.put("shard_lapped", shardLapped - shardLappedReported);
        host.put("direct_lapped", directLapped - directLappedReported);
        shardLappedReported = shardLapped;
        directLappedReported = directLapped;
        final SchedulingLatencyProbe.Window sched = probe == null ? null : probe.latest();
        if (sched != null) {
            host.put("sched_p99_us", sched.p99Micros());
            host.put("sched_p999_us", sched.p999Micros());
            host.put("sched_max_us", sched.maxMicros());
            host.put("sched_over1ms", sched.over1ms());
        }
        lines.add(host);
        return lines;
    }

    /** One line per stream for the whole run; what is still unpaired is reported as pending. */
    List<Map<String, Object>> totals(final long now) {
        final List<Map<String, Object>> lines = new ArrayList<>();
        for (final Pairing pairing : pairings) {
            final Map<String, Object> line =
                    line("feed_compare_total", pairing.streamId, (now - startNanos) / 1e9, pairing.total);
            line.put("shard_pending", pairing.pending[SHARD].size());
            line.put("direct_pending", pairing.pending[DIRECT].size());
            lines.add(line);
        }
        return lines;
    }

    private static Map<String, Object> line(
            final String kind, final int streamId, final double seconds, final Stats stats) {
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("kind", kind);
        line.put("stream_id", streamId);
        line.put("window_s", seconds);
        line.put("matched", stats.hop.count());
        line.put("shard_only", stats.only[SHARD]);
        line.put("direct_only", stats.only[DIRECT]);
        line.put("shard_only_snapshots", stats.onlySnapshots[SHARD]);
        line.put("direct_only_snapshots", stats.onlySnapshots[DIRECT]);
        line.put("shard_bad", stats.bad[SHARD]);
        line.put("direct_bad", stats.bad[DIRECT]);
        line.put("hop_p50_us", stats.hop.percentileMicros(0.5));
        line.put("hop_p90_us", stats.hop.percentileMicros(0.9));
        line.put("hop_p99_us", stats.hop.percentileMicros(0.99));
        line.put("hop_p999_us", stats.hop.percentileMicros(0.999));
        line.put("hop_max_us", stats.hop.maxMicros());
        line.put("hop_min_us", stats.hop.minMicros());
        line.put("transit_p50_us", stats.transit.percentileMicros(0.5));
        line.put("transit_p90_us", stats.transit.percentileMicros(0.9));
        line.put("transit_p99_us", stats.transit.percentileMicros(0.99));
        line.put("transit_p999_us", stats.transit.percentileMicros(0.999));
        line.put("transit_max_us", stats.transit.maxMicros());
        line.put("transit_min_us", stats.transit.minMicros());
        line.put("shard_venue_lag_p50_us", stats.venueLag[SHARD].percentileMicros(0.5));
        line.put("shard_venue_lag_p99_us", stats.venueLag[SHARD].percentileMicros(0.99));
        line.put("direct_venue_lag_p50_us", stats.venueLag[DIRECT].percentileMicros(0.5));
        line.put("direct_venue_lag_p99_us", stats.venueLag[DIRECT].percentileMicros(0.99));
        return line;
    }

    private void log(final Map<String, Object> line) {
        try {
            logger.logf(LogMessage.DEBUG, "FEED_COMPARE %s", mapper.writeValueAsString(line));
        } catch (JsonProcessingException e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Feed compare: couldn't write a report line: %s", e);
        }
    }

    @Override
    public String roleName() {
        return "feed-compare";
    }

    /** One stream's unpaired records on each side, and its counts. */
    private static final class Pairing {
        final int streamId;
        final Pending[] pending = {new Pending(), new Pending()};
        final Stats window = new Stats();
        final Stats total = new Stats();

        Pairing(final int streamId) {
            this.streamId = streamId;
        }

        void onRecord(
                final int side, final long identity, final long arrival, final long venueLag, final boolean snapshot) {
            window.venueLag[side].record(venueLag / 1_000);
            total.venueLag[side].record(venueLag / 1_000);
            final long other = pending[1 - side].take(identity);
            if (other != Pending.NONE) {
                final long hop = side == SHARD ? arrival - other : other - arrival;
                window.hop.record(hop);
                total.hop.record(hop);
                return;
            }
            if (pending[side].isFull()) {
                countOnly(side, pending[side].dropOldest());
            }
            pending[side].add(identity, arrival, snapshot);
        }

        void expire(final long before) {
            for (int side = SHARD; side <= DIRECT; side++) {
                while (pending[side].oldestBefore(before)) {
                    countOnly(side, pending[side].dropOldest());
                }
            }
        }

        private void countOnly(final int side, final boolean snapshot) {
            window.only[side]++;
            total.only[side]++;
            if (snapshot) {
                window.onlySnapshots[side]++;
                total.onlySnapshots[side]++;
            }
        }
    }

    /** One side's unpaired records, oldest first; a record paired out of order leaves a hole, skipped later. */
    static final class Pending {
        static final long NONE = Long.MIN_VALUE;
        private static final int MASK = PENDING_CAPACITY - 1;

        private final long[] identities = new long[PENDING_CAPACITY];
        private final long[] arrivals = new long[PENDING_CAPACITY];
        private final boolean[] snapshots = new boolean[PENDING_CAPACITY];
        private final boolean[] taken = new boolean[PENDING_CAPACITY];
        private long head;
        private long tail;
        private int live;

        int size() {
            return live;
        }

        boolean isFull() {
            return tail - head == PENDING_CAPACITY;
        }

        void add(final long identity, final long arrival, final boolean snapshot) {
            final int at = (int) (tail++ & MASK);
            identities[at] = identity;
            arrivals[at] = arrival;
            snapshots[at] = snapshot;
            taken[at] = false;
            live++;
        }

        /** The arrival of the oldest unpaired record with this identity, now paired; {@link #NONE} if there's none. */
        long take(final long identity) {
            for (long i = head; i < tail; i++) {
                final int at = (int) (i & MASK);
                if (!taken[at] && identities[at] == identity) {
                    taken[at] = true;
                    live--;
                    skipTaken();
                    return arrivals[at];
                }
            }
            return NONE;
        }

        boolean oldestBefore(final long before) {
            return live > 0 && arrivals[(int) (head & MASK)] < before;
        }

        /** Removes the oldest unpaired record; whether it was a snapshot. */
        boolean dropOldest() {
            final int at = (int) (head++ & MASK);
            live--;
            skipTaken();
            return snapshots[at];
        }

        private void skipTaken() {
            while (head < tail && taken[(int) (head & MASK)]) {
                head++;
            }
        }
    }

    private static final class Stats {
        final SignedLatency hop = new SignedLatency();
        final SignedLatency transit = new SignedLatency();
        final LatencyHistogram[] venueLag = {new LatencyHistogram(), new LatencyHistogram()};
        final long[] only = new long[2];
        final long[] onlySnapshots = new long[2];
        final long[] bad = new long[2];

        void reset() {
            hop.reset();
            transit.reset();
            venueLag[SHARD].reset();
            venueLag[DIRECT].reset();
            only[SHARD] = 0;
            only[DIRECT] = 0;
            onlySnapshots[SHARD] = 0;
            onlySnapshots[DIRECT] = 0;
            bad[SHARD] = 0;
            bad[DIRECT] = 0;
        }
    }

    /** Hops either way: the shard path is usually later, but can be earlier when its venue connection is faster. */
    static final class SignedLatency {
        private final LatencyHistogram later = new LatencyHistogram();
        private final LatencyHistogram earlier = new LatencyHistogram();
        private long minNanos = Long.MAX_VALUE;
        private long maxNanos = Long.MIN_VALUE;

        void record(final long nanos) {
            if (nanos >= 0) {
                later.record(nanos / 1_000);
            } else {
                earlier.record(-nanos / 1_000);
            }
            minNanos = Math.min(minNanos, nanos);
            maxNanos = Math.max(maxNanos, nanos);
        }

        long count() {
            return later.count() + earlier.count();
        }

        long percentileMicros(final double quantile) {
            final long count = count();
            if (count == 0) {
                return 0;
            }
            final long rank = (long) Math.ceil(quantile * count);
            final long early = earlier.count();
            return rank <= early ? -earlier.atRank(early - rank + 1) : later.atRank(rank - early);
        }

        long maxMicros() {
            return count() == 0 ? 0 : maxNanos / 1_000;
        }

        long minMicros() {
            return count() == 0 ? 0 : minNanos / 1_000;
        }

        void reset() {
            later.reset();
            earlier.reset();
            minNanos = Long.MAX_VALUE;
            maxNanos = Long.MIN_VALUE;
        }
    }
}
