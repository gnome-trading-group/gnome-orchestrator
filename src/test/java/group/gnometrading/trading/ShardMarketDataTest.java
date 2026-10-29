package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.aeron.AeronChannels;
import group.gnometrading.aeron.AeronProfile;
import group.gnometrading.aeron.AeronRuntime;
import group.gnometrading.aeron.AeronStreamIds;
import group.gnometrading.gateways.GatewayHealth;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.MessageHeaderEncoder;
import group.gnometrading.schemas.ShardStatusEncoder;
import group.gnometrading.schemas.StreamHeader;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import io.aeron.Publication;
import io.aeron.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A session reading a stream from shards over real Aeron on loopback. Each "shard" is a pair of publications (the
 * stream and its status) the test drives, so silences, positions and drops are the test's to choose; the session's
 * clock is the test's too, so its liveness checks run without waiting. The ingress agent and the control steps are
 * driven by hand, in the order their threads would run them.
 */
class ShardMarketDataTest {

    private static final int STREAM_ID = 101;
    private static final int PORT_A = 40070;
    private static final int PORT_B = 40071;
    private static final int PORT_NOWHERE = 40072;
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private final AeronRuntime shards =
            AeronRuntime.launch("md-test-shards", AeronProfile.STANDARD, Throwable::printStackTrace);
    private final AeronRuntime session =
            AeronRuntime.launch("md-test-session", AeronProfile.STANDARD, Throwable::printStackTrace);
    private final AtomicLong clock = new AtomicLong(1_000 * SECOND);
    private final AtomicReference<MarketDataLeases.Lease> lease = new AtomicReference<>();
    private final SequencedRingBuffer<Mbp10Schema> ring =
            new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence(), 8_192);
    // Each record the strategy saw: {sequence, bid, bad data}.
    private final List<long[]> seen = new ArrayList<>();
    private final Mbp10Schema decoded = new Mbp10Schema();
    private final SequencedPoller strategy = ring.createPoller((seq, templateId, buffer, length) -> {
        decoded.buffer.putBytes(0, buffer, 0, length);
        seen.add(new long[] {
            StreamHeader.sequence(buffer, 0),
            decoded.decoder.bidPrice0(),
            StreamHeader.isSet(StreamHeader.flags(buffer, 0), StreamHeader.BAD_DATA_BIT) ? 1 : 0
        });
    });
    private final StreamFeed feed = new StreamFeed(STREAM_ID, "market data 1");
    private final ShardFeedAgent agent = new ShardFeedAgent(ring, List.of(feed), clock::get);
    private final ShardMarketData marketData = new ShardMarketData(
            new NullLogger(),
            session,
            "localhost",
            lease::get,
            List.of(feed),
            List.<GatewayHealth>of(feed),
            agent,
            0,
            clock::get);

    private final Publication streamA = publication(PORT_A, STREAM_ID);
    private final Publication statusA = publication(PORT_A, AeronStreamIds.SHARD_STATUS);
    private final Mbp10Schema record = new Mbp10Schema();
    private final UnsafeBuffer frame = new UnsafeBuffer(new byte[256]);
    private final MessageHeaderEncoder header = new MessageHeaderEncoder();
    private final ShardStatusEncoder statusEncoder = new ShardStatusEncoder();
    private final List<AutoCloseable> extra = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (final AutoCloseable closeable : extra) {
            closeable.close();
        }
        session.close();
        shards.close();
    }

    private Publication publication(final int port, final int streamId) {
        return shards.aeron().addPublication(AeronChannels.shardPublication("localhost", port), streamId);
    }

    private static MarketDataLeases.Lease leaseOn(final int port, final MarketDataLeases.Placement next) {
        return new MarketDataLeases.Lease(
                "s-1",
                "ACTIVE",
                List.of(new MarketDataLeases.LeasedStream(
                        STREAM_ID, "LIVE", "shard-" + port, new MarketDataLeases.Endpoint("localhost", port), next)));
    }

    private static MarketDataLeases.Placement nextOn(final int port) {
        return new MarketDataLeases.Placement(
                "shard-" + port, "WARMING", new MarketDataLeases.Endpoint("localhost", port));
    }

    /** One pass of each thread: the ingress agent, the control thread, then the strategy reading. */
    private void step() throws Exception {
        agent.doWork();
        marketData.controlTick();
        strategy.poll();
    }

    /** Steps until {@code condition}, in real time: Aeron's I/O is real. */
    private void pump(final BooleanSupplier condition, final String what) throws Exception {
        final long deadline = System.nanoTime() + 10 * SECOND;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            step();
            Thread.sleep(1);
        }
    }

    /** One pass at {@code advanceNanos} later on the session's clock. */
    private void after(final long advanceNanos) throws Exception {
        clock.addAndGet(advanceNanos);
        step();
    }

    private long offer(final Publication publication, final long sequence, final long timestamp, final long bid) {
        record.encoder
                .streamId(STREAM_ID)
                .timestampEvent(timestamp)
                .timestampRecv(timestamp)
                .sequence(sequence)
                .bidPrice0(bid);
        return publication.offer(record.buffer, 0, record.totalMessageSize());
    }

    private void send(final Publication publication, final long sequence) throws Exception {
        pump(() -> offer(publication, sequence, sequence * 10, sequence) > 0, "offer " + sequence);
    }

    private void sendFlagged(final Publication publication, final long sequence) throws Exception {
        record.encoder.flags().badData(true);
        try {
            send(publication, sequence);
        } finally {
            record.encoder.flags().badData(false);
        }
    }

    private void status(
            final Publication status,
            final Publication stream,
            final long position,
            final long dropped,
            final boolean reconnecting)
            throws Exception {
        statusEncoder
                .wrapAndApplyHeader(frame, 0, header)
                .sentNanos(1)
                .generation(1)
                .streamsCount(1)
                .next()
                .streamId(STREAM_ID)
                .state((short) 1)
                .position(position)
                .publicationSessionId(stream.sessionId())
                .dropped(dropped)
                .reconnecting(reconnecting ? (short) 1 : (short) 0);
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + statusEncoder.encodedLength();
        pump(() -> status.offer(frame, 0, length) > 0, "status");
    }

    private void join() throws Exception {
        lease.set(leaseOn(PORT_A, null));
        marketData.subscribe(lease.get());
        pump(() -> streamA.isConnected() && statusA.isConnected(), "the session to join shard A");
    }

    private long last(final int field) {
        return seen.get(seen.size() - 1)[field];
    }

    @Test
    void recordsReachTheStrategyAndAShardsResendsDoNot() throws Exception {
        join();

        send(streamA, 1);
        send(streamA, 2);
        send(streamA, 2);
        send(streamA, 3);
        pump(() -> seen.size() >= 3, "three records");
        after(0);

        assertEquals(List.of(1L, 2L, 3L), seen.stream().map(r -> r[0]).toList());
        assertFalse(feed.isReconnecting());
    }

    @Test
    void twoUpdatesWithTheSameTimestampAndNoSequenceBothReachTheStrategy() throws Exception {
        join();

        pump(() -> offer(streamA, 0, 5_000, 100) > 0, "the first update");
        pump(() -> offer(streamA, 0, 5_000, 101) > 0, "the second, same millisecond");
        pump(() -> seen.size() == 2, "both updates");

        assertEquals(List.of(100L, 101L), seen.stream().map(r -> r[1]).toList());
    }

    @Test
    void aShardThatFallsSilentMarksTheStreamBadOnceAndItsNextRecordClearsIt() throws Exception {
        join();
        status(statusA, streamA, 0, 0, false);
        send(streamA, 7);
        pump(() -> seen.size() == 1 && feed.currentLink.statusSeen, "a record and a status");

        after(ShardFeedAgent.SHARD_SILENT_NANOS - SECOND);
        assertEquals(1, seen.size(), "not silent long enough");
        after(2 * SECOND);
        after(SECOND);

        assertEquals(2, seen.size(), "one bad mark, not one per check");
        assertEquals(7, last(0), "the last record again");
        assertEquals(1, last(2), "flagged bad data");
        assertTrue(feed.isReconnecting());
        assertEquals(1, feed.bookResyncs());

        status(statusA, streamA, 0, 0, false);
        send(streamA, 8);
        pump(() -> seen.size() == 3, "the next record");
        assertEquals(0, last(2));
        assertFalse(feed.isReconnecting());
    }

    @Test
    void recordsTheShardCouldntSendMarkTheStreamBadUntilTheNextOne() throws Exception {
        join();
        send(streamA, 1);
        status(statusA, streamA, 0, 4, false);
        pump(() -> seen.size() == 1 && feed.statusDropped == 4, "a record and the first count");
        after(ShardFeedAgent.CHECK_EVERY_NANOS);
        assertFalse(feed.isReconnecting(), "the first count is only a baseline");

        status(statusA, streamA, 0, 6, false);
        pump(() -> feed.statusDropped == 6, "a higher count");
        after(ShardFeedAgent.CHECK_EVERY_NANOS);
        assertTrue(feed.isReconnecting());

        send(streamA, 2);
        pump(() -> last(0) == 2, "the next record");
        assertFalse(feed.isReconnecting());
    }

    @Test
    void aStreamTheShardReportsReconnectingIsMarkedBad() throws Exception {
        join();
        send(streamA, 1);
        status(statusA, streamA, 0, 0, true);
        pump(() -> seen.size() == 1 && feed.statusReconnecting, "a record and the report");

        after(ShardFeedAgent.CHECK_EVERY_NANOS);

        assertTrue(feed.isReconnecting());
    }

    @Test
    void aStreamThatNeverReceivesAnythingIsMarkedBad() throws Exception {
        lease.set(leaseOn(PORT_NOWHERE, null));
        marketData.subscribe(lease.get());
        step();
        after(ShardFeedAgent.SILENT_NANOS - SECOND);
        assertFalse(feed.isReconnecting());

        after(2 * SECOND);

        assertTrue(feed.isReconnecting(), "nothing has arrived since it subscribed");
    }

    @Test
    void aSessionLappedByTheShardIsMarkedBadAndRecoversBySubscribingAgain() throws Exception {
        // Another session keeps up, so the shard's publication follows it and laps this one.
        final AeronRuntime other =
                AeronRuntime.launch("md-test-other", AeronProfile.STANDARD, Throwable::printStackTrace);
        extra.add(other);
        final Subscription fast = other.aeron()
                .addSubscription(
                        "aeron:udp?endpoint=localhost:40985|control=localhost:" + PORT_A + "|control-mode=dynamic",
                        STREAM_ID);
        final AtomicBoolean reading = new AtomicBoolean(true);
        final Thread fastReader = new Thread(() -> {
            while (reading.get()) {
                fast.poll((buffer, offset, length, h) -> {}, 100);
            }
        });
        fastReader.start();
        try {
            join();
            pump(() -> fast.imageCount() == 1, "the other session to join");
            send(streamA, 1);
            pump(() -> seen.size() == 1, "the first record");

            // This session stops reading while the shard sends far more than its term holds.
            for (int i = 2; i <= 6_000; i++) {
                final long sequence = i;
                while (offer(streamA, sequence, sequence * 10, sequence) < 0) {
                    Thread.onSpinWait();
                }
            }
            final long shardPosition = streamA.position();
            status(statusA, streamA, shardPosition, 0, false);
            pump(() -> feed.statusPosition == shardPosition, "the shard's position");

            after(ShardFeedAgent.CHECK_EVERY_NANOS);
            after(ShardFeedAgent.STALLED_NANOS + ShardFeedAgent.CHECK_EVERY_NANOS);
            assertTrue(feed.isReconnecting(), "stalled behind the shard");

            pump(() -> !feed.resubscribing && feed.current != null, "a new subscription");
            status(statusA, streamA, streamA.position(), 0, false);
            pump(() -> offer(streamA, 7_000, 70_000, 7_000) > 0 && last(0) == 7_000, "a record at the live position");
            assertFalse(feed.isReconnecting());
        } finally {
            reading.set(false);
            fastReader.join();
        }
    }

    @Test
    void aStreamMovingShardsSwitchesOnItsFirstCleanRecordThereAndIgnoresTheOldOne() throws Exception {
        join();
        final Publication streamB = publication(PORT_B, STREAM_ID);
        send(streamA, 1);
        pump(() -> seen.size() == 1, "the first record");

        lease.set(leaseOn(PORT_A, nextOn(PORT_B)));
        after(ShardMarketData.LEASE_EVERY_NANOS);
        pump(() -> streamB.isConnected() && feed.pendingLink != null, "the session to join shard B");

        send(streamA, 2);
        pump(() -> seen.size() == 2, "shard A still delivering");
        sendFlagged(streamB, 3);
        send(streamA, 4);
        pump(() -> seen.size() == 3, "shard A, as B's first record was flagged");
        send(streamB, 5);
        pump(() -> seen.size() == 4, "the switch to shard B");
        // The session has let shard A go, so A may have nobody left to send to: offered once, never waited on.
        offer(streamA, 6, 60, 6);
        send(streamB, 7);
        pump(() -> seen.size() == 5, "shard B's next record");

        assertEquals(List.of(1L, 2L, 4L, 5L, 7L), seen.stream().map(r -> r[0]).toList());
        assertEquals(PORT_B, feed.currentLink.port);
        assertNull(feed.pendingLink);
    }

    @Test
    void aMoveThatNeverDeliversIsGivenUpAndTheNextOneFollowed() throws Exception {
        join();
        final Publication streamB = publication(PORT_B, STREAM_ID);
        send(streamA, 1);
        pump(() -> seen.size() == 1, "the first record");

        lease.set(leaseOn(PORT_A, nextOn(PORT_NOWHERE)));
        after(ShardMarketData.LEASE_EVERY_NANOS);
        pump(() -> feed.pendingLink != null, "a pending move to nowhere");

        lease.set(leaseOn(PORT_A, nextOn(PORT_B)));
        after(ShardMarketData.PENDING_TIMEOUT_NANOS);
        pump(() -> feed.pendingLink == null, "the stuck move given up");
        after(ShardMarketData.LEASE_EVERY_NANOS);
        pump(() -> feed.pendingLink != null && feed.pendingLink.port == PORT_B && streamB.isConnected(), "B");

        send(streamB, 2);
        pump(() -> feed.currentLink.port == PORT_B, "the switch to shard B");
        assertEquals(2, last(0));
    }

    @Test
    void aSessionWhoseStreamsNeverGoLiveFailsToStartSayingWhy() {
        lease.set(new MarketDataLeases.Lease(
                "s-1",
                "ACTIVE",
                List.of(new MarketDataLeases.LeasedStream(STREAM_ID, "WARMING", "shard-a", null, null))));
        final ShardMarketData waiting = new ShardMarketData(
                new NullLogger(),
                session,
                "localhost",
                lease::get,
                List.of(feed),
                List.<GatewayHealth>of(feed),
                agent,
                TimeUnit.MILLISECONDS.toNanos(100),
                System::nanoTime);

        final IllegalStateException failed =
                assertThrows(IllegalStateException.class, () -> waiting.start(Throwable::printStackTrace));
        assertTrue(failed.getMessage().contains(STREAM_ID + " WARMING"), failed.getMessage());
    }
}
