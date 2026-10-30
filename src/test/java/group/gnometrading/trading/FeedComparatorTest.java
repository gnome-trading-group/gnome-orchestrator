package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.StreamHeader;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedComparatorTest {

    private static final int STREAM_ID = 425_462;
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long SHARD_TRANSIT = 700_000;

    private final AtomicLong clock = new AtomicLong(1_000 * MS);
    private final SequencedRingBuffer<Mbp10Schema> direct =
            new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
    private final ShardArrivals arrivals = new ShardArrivals(clock::get);
    private final FeedComparator comparator = new FeedComparator(
            new NullLogger(),
            clock::get,
            clock::get,
            new int[] {STREAM_ID},
            List.of(direct),
            arrivals.ring(),
            null,
            new ObjectMapper());
    private final Mbp10Schema record = new Mbp10Schema();

    @BeforeEach
    void start() {
        comparator.onStart();
    }

    private void build(final long event, final long bid, final boolean snapshot, final boolean bad) {
        record.encoder
                .streamId(STREAM_ID)
                .timestampEvent(event)
                .timestampRecv(clock.get())
                .bidPrice0(bid);
        record.encoder.flags().snapshot(snapshot).badData(bad);
    }

    /** As the session's own reader delivers it: stamped by the reader on arrival. */
    private void viaDirect(final long event, final long bid) {
        viaDirect(event, bid, false, false);
    }

    private void viaDirect(final long event, final long bid, final boolean snapshot, final boolean bad) {
        build(event, bid, snapshot, bad);
        direct.publishRaw(record.buffer, Mbp10Encoder.TEMPLATE_ID, record.totalMessageSize());
    }

    /** As the shard path delivers it: received by the shard {@link #SHARD_TRANSIT} before it arrives here. */
    private void viaShard(final long event, final long bid) {
        viaShard(event, bid, false, false);
    }

    private void viaShard(final long event, final long bid, final boolean snapshot, final boolean bad) {
        build(event, bid, snapshot, bad);
        record.encoder.timestampRecv(clock.get() - SHARD_TRANSIT);
        arrivals.record(record.buffer, 0, Mbp10Encoder.TEMPLATE_ID, record.totalMessageSize());
    }

    private Map<String, Object> window() throws Exception {
        comparator.doWork();
        return comparator.report(clock.get()).get(0);
    }

    @Test
    void theShardCopyIsStampedOnArrivalKeepsTheShardsStampAndIsTheSameRecord() throws Exception {
        final long[] seen = new long[4];
        final var poller = arrivals.ring().createLappingPoller((sequence, templateId, buffer, length) -> {
            seen[0] = StreamHeader.timestampRecv(buffer, 0);
            seen[1] = StreamHeader.timestampEvent(buffer, 0);
            seen[2] = FeedComparator.identity(buffer, length);
            seen[3] = buffer.getLong(ShardArrivals.SENT_OFFSET, Statics.BYTE_ORDER);
        });
        record.encoder.streamId(STREAM_ID).timestampEvent(7).timestampRecv(1).bidPrice0(42);
        clock.set(5_000 * MS);

        arrivals.record(record.buffer, 0, Mbp10Encoder.TEMPLATE_ID, record.totalMessageSize());
        poller.poll();

        assertEquals(5_000 * MS, seen[0]);
        assertEquals(7, seen[1]);
        assertEquals(FeedComparator.identity(record.buffer, record.totalMessageSize()), seen[2]);
        assertEquals(1, seen[3]);
    }

    @Test
    void theSameRecordFromBothPathsIsPairedAndTheHopTimed() throws Exception {
        viaDirect(990 * MS, 100);
        clock.addAndGet(2 * MS);
        viaShard(990 * MS, 100, true, false);

        final Map<String, Object> window = window();

        assertEquals(1L, window.get("matched"));
        assertEquals(2_000L, window.get("hop_p50_us"));
        assertEquals(700L, window.get("transit_p50_us"));
        assertEquals(0L, window.get("shard_only"));
        assertEquals(0L, window.get("direct_only"));
    }

    @Test
    void repeatsPairInTheOrderTheyArrived() throws Exception {
        viaDirect(990 * MS, 100);
        clock.addAndGet(MS);
        viaDirect(990 * MS, 100);
        clock.addAndGet(3 * MS);
        viaShard(990 * MS, 100);
        clock.addAndGet(5 * MS);
        viaShard(990 * MS, 100);

        final Map<String, Object> window = window();

        assertEquals(2L, window.get("matched"));
        assertEquals(4_000L, window.get("hop_min_us"));
        assertEquals(8_000L, window.get("hop_max_us"));
    }

    @Test
    void aShardRecordThatArrivesFirstIsANegativeHop() throws Exception {
        viaShard(990 * MS, 100);
        clock.addAndGet(3 * MS);
        viaDirect(990 * MS, 100);

        final Map<String, Object> window = window();

        assertEquals(1L, window.get("matched"));
        assertEquals(-3_000L, window.get("hop_p50_us"));
    }

    @Test
    void whatTheOtherSideNeverDeliversExpiresAsOneSided() throws Exception {
        viaDirect(990 * MS, 100, true, false);
        viaShard(991 * MS, 101);
        comparator.doWork();
        clock.addAndGet(FeedComparator.EXPIRE_AFTER_NANOS + 2_000 * MS);

        final Map<String, Object> window = window();

        assertEquals(0L, window.get("matched"));
        assertEquals(1L, window.get("direct_only"));
        assertEquals(1L, window.get("direct_only_snapshots"));
        assertEquals(1L, window.get("shard_only"));
        assertEquals(0L, window.get("shard_only_snapshots"));
    }

    @Test
    void badDataIsCountedNotPaired() throws Exception {
        viaDirect(990 * MS, 100, false, true);
        viaShard(990 * MS, 100, false, true);

        final Map<String, Object> window = window();

        assertEquals(0L, window.get("matched"));
        assertEquals(1L, window.get("direct_bad"));
        assertEquals(1L, window.get("shard_bad"));
    }

    @Test
    void eachWindowStartsAfreshWhileTheTotalsKeepEverything() throws Exception {
        viaDirect(990 * MS, 100);
        viaShard(990 * MS, 100);
        window();
        viaDirect(991 * MS, 101);

        final Map<String, Object> next = window();
        final Map<String, Object> total = comparator.totals(clock.get()).get(0);

        assertEquals(0L, next.get("matched"));
        assertEquals("feed_compare_total", total.get("kind"));
        assertEquals(1L, total.get("matched"));
        assertEquals(1, total.get("direct_pending"));
    }

    @Test
    void shardArrivalsAreMovedOntoTheReadersWallClock() throws Exception {
        final AtomicLong monotonic = new AtomicLong(3 * MS);
        final ShardArrivals onMonotonic = new ShardArrivals(monotonic::get);
        final FeedComparator offset = new FeedComparator(
                new NullLogger(),
                clock::get,
                monotonic::get,
                new int[] {STREAM_ID},
                List.of(direct),
                onMonotonic.ring(),
                null,
                new ObjectMapper());
        offset.onStart();
        viaDirect(990 * MS, 100);
        clock.addAndGet(2 * MS);
        monotonic.addAndGet(2 * MS);
        build(990 * MS, 100, false, false);
        onMonotonic.record(record.buffer, 0, Mbp10Encoder.TEMPLATE_ID, record.totalMessageSize());

        offset.doWork();
        final Map<String, Object> window = offset.report(clock.get()).get(0);

        assertEquals(1L, window.get("matched"));
        assertEquals(2_000L, window.get("hop_p50_us"));
        assertEquals(0L, window.get("transit_p50_us"));
    }
}
