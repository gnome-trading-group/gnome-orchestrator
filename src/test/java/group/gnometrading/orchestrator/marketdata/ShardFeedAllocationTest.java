package group.gnometrading.orchestrator.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronReservedValues;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.AeronStreamIds;
import group.gnometrading.orchestrator.runtime.GatewayHealth;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.MessageHeaderEncoder;
import group.gnometrading.schemas.ShardStatusEncoder;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.utils.SampledEpochNanoClock;
import io.aeron.Publication;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Once warm, the thread that reads shard streams must not allocate, liveness checks, status frames and the
 * copy a comparison takes of each record included: a GC pause there delays every book the strategy sees.
 */
@Tag("allocation")
class ShardFeedAllocationTest {

    private static final int STREAM_ID = 101;
    private static final int PORT = 40080;
    private static final int RECORDS = 200_000;
    // FeedMetrics' counters in declaration order: passes, passGapPeakNanos, records, unstamped, ...
    private static final int UNSTAMPED = 3;
    private static final MessageHeaderEncoder HEADER = new MessageHeaderEncoder();
    private static final ShardStatusEncoder STATUS = new ShardStatusEncoder();
    private static final MarketDataLeases.Lease LEASE = new MarketDataLeases.Lease(
            "s-1",
            "ACTIVE",
            List.of(new MarketDataLeases.LeasedStream(
                    STREAM_ID, "LIVE", "shard-a", new MarketDataLeases.Endpoint("localhost", PORT), null)));

    @Test
    @Timeout(120)
    void theShardFeedThreadDoesNotAllocateOnceWarm() throws Exception {
        try (AeronRuntime shards =
                        AeronRuntime.launch("feed-alloc-shard", AeronProfile.STANDARD, Throwable::printStackTrace);
                AeronRuntime session =
                        AeronRuntime.launch("feed-alloc-session", AeronProfile.STANDARD, Throwable::printStackTrace)) {
            final SequencedRingBuffer<Mbp10Schema> ring =
                    new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
            final StreamFeed feed = new StreamFeed(STREAM_ID, "market data 1");
            final ShardFeedAgent agent = new ShardFeedAgent(ring, List.of(feed), System::nanoTime);
            final ShardMarketData marketData = new ShardMarketData(
                    new NullLogger(),
                    session,
                    "localhost",
                    () -> null,
                    List.of(feed),
                    List.<GatewayHealth>of(feed),
                    agent,
                    0,
                    System::nanoTime);
            assertNoAllocationOnceWarm(shards, ring, agent, () -> marketData.subscribe(LEASE), false);
        }
    }

    /** Reading for several sessions takes the same record path, whichever session's lease placed the stream. */
    @Test
    @Timeout(120)
    void theSharedShardFeedThreadDoesNotAllocateOnceWarm() throws Exception {
        try (AeronRuntime shards =
                        AeronRuntime.launch("feed-alloc-shard", AeronProfile.STANDARD, Throwable::printStackTrace);
                AeronRuntime process =
                        AeronRuntime.launch("feed-alloc-process", AeronProfile.STANDARD, Throwable::printStackTrace)) {
            final SequencedRingBuffer<Mbp10Schema> ring =
                    new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
            final ShardMarketData marketData =
                    ShardMarketData.shared(new NullLogger(), process, "localhost", ring, System::nanoTime);
            marketData.addSession("a", () -> LEASE, new int[] {STREAM_ID});
            marketData.addSession("b", () -> LEASE, new int[] {STREAM_ID});
            assertNoAllocationOnceWarm(shards, ring, marketData.agent(), marketData::controlTick, false);
        }
    }

    /**
     * A gateway's reader also measures each record (its arrival against the shard's send stamp, its time in the
     * shard) and every pass: still without allocating, stamps read from the frame and the wall clock once a pass.
     */
    @Test
    @Timeout(120)
    void aMeasuringShardFeedThreadDoesNotAllocateOnceWarm() throws Exception {
        try (AeronRuntime shards =
                        AeronRuntime.launch("feed-alloc-shard", AeronProfile.STANDARD, Throwable::printStackTrace);
                AeronRuntime process =
                        AeronRuntime.launch("feed-alloc-process", AeronProfile.STANDARD, Throwable::printStackTrace)) {
            final SequencedRingBuffer<Mbp10Schema> ring =
                    new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
            final FeedMetrics metrics = new FeedMetrics();
            final ShardMarketData marketData = ShardMarketData.shared(
                    new NullLogger(),
                    process,
                    "localhost",
                    ring,
                    System::nanoTime,
                    metrics,
                    SampledEpochNanoClock.INSTANCE);
            marketData.addSession("a", () -> LEASE, new int[] {STREAM_ID});
            assertNoAllocationOnceWarm(shards, ring, marketData.agent(), marketData::controlTick, true);
            final ShardLink[] links = marketData.agent().watched();
            assertEquals(1, links.length);
            assertEquals(RECORDS, links[0].records());
            assertTrue(links[0].takeNetworkPeakNanos() > 0, "each record's network time measured from its stamp");
            assertEquals(0, metrics.counters().get(UNSTAMPED), "every record carried the shard's send stamp");
        }
    }

    private static void assertNoAllocationOnceWarm(
            final AeronRuntime shards,
            final SequencedRingBuffer<Mbp10Schema> ring,
            final ShardFeedAgent agent,
            final Runnable subscribe,
            final boolean stamped)
            throws Exception {
        final AeronReservedValues.SentStamp stamp = stamped ? new AeronReservedValues.SentStamp() : null;
        final String channel = AeronChannels.shardPublication("localhost", PORT);
        final Publication stream = shards.aeron().addPublication(channel, STREAM_ID);
        final Publication status = shards.aeron().addPublication(channel, AeronStreamIds.SHARD_STATUS);
        final AtomicLong strategySeen = new AtomicLong();
        final SequencedPoller strategy =
                ring.createPoller((seq, templateId, buffer, length) -> strategySeen.lazySet(seq));
        subscribe.run();
        final AtomicLong feedThread = new AtomicLong();
        final GnomeAgentRunner runner = new GnomeAgentRunner(
                new GnomeAgent() {
                    @Override
                    public int doWork() {
                        feedThread.compareAndSet(0, Thread.currentThread().getId());
                        return agent.doWork();
                    }
                },
                Throwable::printStackTrace);
        GnomeAgentRunner.startOnThread(runner);
        final AtomicBoolean running = new AtomicBoolean(true);
        final Thread strategyThread = new Thread(() -> {
            try {
                while (running.get()) {
                    strategy.poll();
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        strategyThread.start();
        try {
            while (!stream.isConnected() || !status.isConnected()) {
                Thread.sleep(1);
            }
            final Mbp10Schema record = new Mbp10Schema();
            final UnsafeBuffer frame = new UnsafeBuffer(new byte[256]);
            publish(stream, status, record, frame, stamp, 0, RECORDS / 2);
            awaitSeen(strategySeen, RECORDS / 2);

            final com.sun.management.ThreadMXBean threads =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            final long before = threads.getThreadAllocatedBytes(feedThread.get());
            publish(stream, status, record, frame, stamp, RECORDS / 2, RECORDS);
            awaitSeen(strategySeen, RECORDS);
            final long allocated = threads.getThreadAllocatedBytes(feedThread.get()) - before;

            System.out.println(
                    "#### shard feed thread allocated " + allocated + "B over " + (RECORDS / 2) + " records");
            assertTrue(allocated < 16_384, "the shard feed thread allocated " + allocated + " bytes");
        } finally {
            running.set(false);
            strategyThread.join();
            runner.close();
        }
    }

    /** Records with a status frame every thousand, as a shard sends them; the status keeps the stream's position. */
    private static void publish(
            final Publication stream,
            final Publication status,
            final Mbp10Schema record,
            final UnsafeBuffer frame,
            final AeronReservedValues.SentStamp stamp,
            final int from,
            final int to) {
        for (int i = from; i < to; i++) {
            record.encoder.streamId(STREAM_ID).timestampEvent(i).sequence(i);
            if (stamp != null) {
                record.encoder.timestampRecv(SampledEpochNanoClock.INSTANCE.nanoTime());
                stamp.set(SampledEpochNanoClock.INSTANCE.nanoTime());
            }
            while ((stamp == null
                            ? stream.offer(record.buffer, 0, record.totalMessageSize())
                            : stream.offer(record.buffer, 0, record.totalMessageSize(), stamp))
                    < 0) {
                Thread.onSpinWait();
            }
            if (i % 1_000 == 0) {
                STATUS.wrapAndApplyHeader(frame, 0, HEADER)
                        .sentNanos(i)
                        .generation(1)
                        .streamsCount(1)
                        .next()
                        .streamId(STREAM_ID)
                        .state((short) 1)
                        .position(stream.position())
                        .publicationSessionId(stream.sessionId())
                        .dropped(0)
                        .reconnecting((short) 0);
                while (status.offer(frame, 0, MessageHeaderEncoder.ENCODED_LENGTH + STATUS.encodedLength()) < 0) {
                    Thread.onSpinWait();
                }
            }
        }
    }

    private static void awaitSeen(final AtomicLong seen, final long count) throws InterruptedException {
        while (seen.get() < count) {
            Thread.sleep(5);
        }
    }
}
