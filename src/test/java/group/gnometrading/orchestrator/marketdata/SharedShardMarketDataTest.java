package group.gnometrading.orchestrator.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.AeronStreamIds;
import group.gnometrading.orchestrator.runtime.GatewayHealth;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.StreamHeader;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import io.aeron.Publication;
import io.aeron.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A process reading streams from shards for several sessions, over real Aeron on loopback, as {@link
 * ShardMarketDataTest} drives a session's: the "shards" are publications the test drives, the clock is the test's, and
 * the ingress agent and control steps run by hand in the order their threads would run them.
 */
class SharedShardMarketDataTest {

    private static final int STREAM_ID = 101;
    private static final int OTHER_STREAM_ID = 102;
    private static final int PORT_A = 40075;
    private static final int PORT_B = 40076;
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private final AeronRuntime shards =
            AeronRuntime.launch("md-shared-test-shards", AeronProfile.STANDARD, Throwable::printStackTrace);
    private final AeronRuntime process =
            AeronRuntime.launch("md-shared-test-process", AeronProfile.STANDARD, Throwable::printStackTrace);
    private final AtomicLong clock = new AtomicLong(1_000 * SECOND);
    private final AtomicReference<MarketDataLeases.Lease> leaseA = new AtomicReference<>();
    private final AtomicReference<MarketDataLeases.Lease> leaseB = new AtomicReference<>();
    private final SequencedRingBuffer<Mbp10Schema> ring =
            new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence(), 8_192);
    // Each record seen in the ring: {stream, sequence, bad data}.
    private final List<long[]> seen = new ArrayList<>();
    private final SequencedPoller reader = ring.createPoller((seq, templateId, buffer, length) -> seen.add(new long[] {
        StreamHeader.streamId(buffer, 0),
        StreamHeader.sequence(buffer, 0),
        StreamHeader.isSet(StreamHeader.flags(buffer, 0), StreamHeader.BAD_DATA_BIT) ? 1 : 0
    }));
    private final ShardMarketData marketData =
            ShardMarketData.shared(new NullLogger(), process, "localhost", ring, clock::get);
    private final ShardFeedAgent agent = marketData.agent();

    private final Publication streamA = publication(PORT_A, STREAM_ID);
    private final Publication otherA = publication(PORT_A, OTHER_STREAM_ID);
    private final Publication statusA = publication(PORT_A, AeronStreamIds.SHARD_STATUS);
    private final Mbp10Schema record = new Mbp10Schema();

    @AfterEach
    void close() {
        process.close();
        shards.close();
    }

    private Publication publication(final int port, final int streamId) {
        return shards.aeron().addPublication(AeronChannels.shardPublication("localhost", port), streamId);
    }

    private static MarketDataLeases.LeasedStream liveOn(
            final int streamId, final int port, final MarketDataLeases.Placement next) {
        return new MarketDataLeases.LeasedStream(
                streamId, "LIVE", "shard-" + port, new MarketDataLeases.Endpoint("localhost", port), next);
    }

    private static MarketDataLeases.Lease lease(
            final String sessionId, final MarketDataLeases.LeasedStream... streams) {
        return new MarketDataLeases.Lease(sessionId, "ACTIVE", List.of(streams));
    }

    private static MarketDataLeases.Placement nextOn(final int port) {
        return new MarketDataLeases.Placement(
                "shard-" + port, "WARMING", new MarketDataLeases.Endpoint("localhost", port));
    }

    private void step() throws Exception {
        agent.doWork();
        marketData.controlTick();
        reader.poll();
    }

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

    private void after(final long advanceNanos) throws Exception {
        clock.addAndGet(advanceNanos);
        step();
    }

    private long offer(final Publication publication, final int streamId, final long sequence) {
        record.encoder
                .streamId(streamId)
                .timestampEvent(sequence * 10)
                .timestampRecv(sequence * 10)
                .sequence(sequence);
        return publication.offer(record.buffer, 0, record.totalMessageSize());
    }

    private void send(final Publication publication, final int streamId, final long sequence) throws Exception {
        pump(() -> offer(publication, streamId, sequence) > 0, "offer " + sequence);
    }

    private List<Long> sequences() {
        return seen.stream().map(r -> r[1]).toList();
    }

    private StreamFeed reading(final int streamId) throws Exception {
        pump(
                () -> {
                    final StreamFeed feed = marketData.feed(streamId);
                    return feed != null && feed.current != null;
                },
                "stream " + streamId + " subscribed");
        return marketData.feed(streamId);
    }

    @Test
    void aSessionsStreamsAreSubscribedWhereItsLeaseHasThemAndDeliverIntoTheRing() throws Exception {
        leaseA.set(lease("a", liveOn(STREAM_ID, PORT_A, null), liveOn(OTHER_STREAM_ID, PORT_A, null)));
        marketData.addSession("a", leaseA::get, new int[] {STREAM_ID, OTHER_STREAM_ID});

        reading(STREAM_ID);
        reading(OTHER_STREAM_ID);
        pump(() -> streamA.isConnected() && otherA.isConnected(), "the process to join shard A");
        send(streamA, STREAM_ID, 1);
        send(otherA, OTHER_STREAM_ID, 2);
        pump(() -> seen.size() == 2, "both records");

        assertEquals(List.of(1L, 2L), sequences());
        assertEquals(
                List.of((long) STREAM_ID, (long) OTHER_STREAM_ID),
                seen.stream().map(r -> r[0]).toList());
        assertTrue(marketData.health().isEmpty(), "no listings of its own to report");
    }

    @Test
    void aStreamNotYetLiveIsSubscribedOnceTheLeaseHasItLive() throws Exception {
        leaseA.set(lease("a", new MarketDataLeases.LeasedStream(STREAM_ID, "WARMING", "shard-a", null, null)));
        marketData.addSession("a", leaseA::get, new int[] {STREAM_ID});
        step();
        assertNull(marketData.feed(STREAM_ID).current, "nothing to subscribe yet, and nothing waited for");

        leaseA.set(lease("a", liveOn(STREAM_ID, PORT_A, null)));
        after(ShardMarketData.LEASE_EVERY_NANOS);
        reading(STREAM_ID);
        pump(streamA::isConnected, "the process to join shard A");
        send(streamA, STREAM_ID, 1);
        pump(() -> seen.size() == 1, "the record");
    }

    @Test
    void twoSessionsNamingOneStreamShareOneSubscriptionUntilBothAreGone() throws Exception {
        leaseA.set(lease("a", liveOn(STREAM_ID, PORT_A, null)));
        leaseB.set(lease("b", liveOn(STREAM_ID, PORT_A, null)));
        marketData.addSession("a", leaseA::get, new int[] {STREAM_ID});
        marketData.addSession("b", leaseB::get, new int[] {STREAM_ID});
        final StreamFeed feed = reading(STREAM_ID);
        pump(streamA::isConnected, "the process to join shard A");
        final Subscription subscription = feed.current.subscription();
        final ShardLink link = feed.currentLink;

        send(streamA, STREAM_ID, 1);
        pump(() -> seen.size() == 1, "the record");
        after(ShardMarketData.LEASE_EVERY_NANOS);
        send(streamA, STREAM_ID, 2);
        pump(() -> seen.size() == 2, "the next record");
        assertEquals(List.of(1L, 2L), sequences(), "each record once: one subscription for both sessions");
        assertSame(subscription, feed.current.subscription(), "the second lease read moved nothing");

        marketData.removeSession("a");
        step();
        assertSame(feed, marketData.feed(STREAM_ID), "b still holds it");
        send(streamA, STREAM_ID, 3);
        pump(() -> seen.size() == 3, "a record after a went");
        assertTrue(!subscription.isClosed());

        marketData.removeSession("b");
        pump(subscription::isClosed, "the subscription closed");
        pump(link.status::isClosed, "the shard left");
        assertNull(marketData.feed(STREAM_ID));
        assertNull(feed.currentLink);
        offer(streamA, STREAM_ID, 4);
        for (int i = 0; i < 20; i++) {
            after(ShardFeedAgent.SILENT_NANOS);
            Thread.sleep(1);
        }
        assertEquals(List.of(1L, 2L, 3L), sequences(), "nothing read, and nothing marked bad, once dropped");
    }

    @Test
    void aStreamDroppedAndNamedAgainIsReadAfresh() throws Exception {
        leaseA.set(lease("a", liveOn(STREAM_ID, PORT_A, null)));
        marketData.addSession("a", leaseA::get, new int[] {STREAM_ID});
        final StreamFeed first = reading(STREAM_ID);
        marketData.removeSession("a");
        step();
        marketData.addSession("a", leaseA::get, new int[] {STREAM_ID});
        final StreamFeed second = reading(STREAM_ID);

        assertTrue(first != second);
        pump(streamA::isConnected, "the process to join shard A");
        send(streamA, STREAM_ID, 1);
        pump(() -> seen.size() == 1, "the record");
    }

    @Test
    void aLeaseThatMovesAStreamIsFollowed() throws Exception {
        leaseA.set(lease("a", liveOn(STREAM_ID, PORT_A, null)));
        marketData.addSession("a", leaseA::get, new int[] {STREAM_ID});
        final StreamFeed feed = reading(STREAM_ID);
        pump(streamA::isConnected, "the process to join shard A");
        final Publication streamB = publication(PORT_B, STREAM_ID);
        send(streamA, STREAM_ID, 1);
        pump(() -> seen.size() == 1, "the first record");

        leaseA.set(lease("a", liveOn(STREAM_ID, PORT_A, nextOn(PORT_B))));
        after(ShardMarketData.LEASE_EVERY_NANOS);
        pump(() -> streamB.isConnected() && feed.pendingLink != null, "the process to join shard B");
        send(streamB, STREAM_ID, 2);
        pump(() -> seen.size() == 2, "the switch to shard B");

        assertEquals(PORT_B, feed.currentLink.port);
        assertNull(feed.pendingLink);
    }

    @Test
    void theRemainingSessionsLeasePlacesAStreamOnceTheFirstGoes() throws Exception {
        leaseA.set(lease("a", liveOn(STREAM_ID, PORT_A, null)));
        leaseB.set(lease("b", liveOn(STREAM_ID, PORT_A, null)));
        marketData.addSession("a", leaseA::get, new int[] {STREAM_ID});
        marketData.addSession("b", leaseB::get, new int[] {STREAM_ID});
        final StreamFeed feed = reading(STREAM_ID);
        pump(streamA::isConnected, "the process to join shard A");
        final Publication streamB = publication(PORT_B, STREAM_ID);

        marketData.removeSession("a");
        leaseA.set(null);
        leaseB.set(lease("b", liveOn(STREAM_ID, PORT_A, nextOn(PORT_B))));
        after(ShardMarketData.LEASE_EVERY_NANOS);
        pump(() -> streamB.isConnected() && feed.pendingLink != null, "b's lease to move it to shard B");
        send(streamB, STREAM_ID, 1);
        pump(() -> seen.size() == 1, "the switch to shard B");
        assertEquals(PORT_B, feed.currentLink.port);
    }

    @Test
    void aSilentStreamIsMarkedBadWithItsLastRecordAgain() throws Exception {
        leaseA.set(lease("a", liveOn(STREAM_ID, PORT_A, null)));
        marketData.addSession("a", leaseA::get, new int[] {STREAM_ID});
        final StreamFeed feed = reading(STREAM_ID);
        pump(streamA::isConnected, "the process to join shard A");
        send(streamA, STREAM_ID, 7);
        pump(() -> seen.size() == 1, "the record");

        after(ShardFeedAgent.SILENT_NANOS + SECOND);

        assertEquals(2, seen.size());
        assertEquals(7, seen.get(1)[1], "the last record again");
        assertEquals(1, seen.get(1)[2], "flagged bad data");
        assertTrue(feed.isReconnecting());
        assertEquals(StreamFeed.BadReason.SILENT, feed.badReason());

        send(streamA, STREAM_ID, 8);
        pump(() -> seen.size() == 3, "the next record");
        assertEquals(0, seen.get(2)[2]);
        assertNotNull(feed.currentLink);
    }

    @Test
    void aSessionsOwnMarketDataTakesNoSessions() {
        final StreamFeed feed = new StreamFeed(STREAM_ID, "market data 1");
        final ShardMarketData own = new ShardMarketData(
                new NullLogger(),
                process,
                "localhost",
                () -> null,
                List.of(feed),
                List.<GatewayHealth>of(feed),
                new ShardFeedAgent(ring, List.of(feed), clock::get),
                0,
                clock::get);

        assertThrows(IllegalStateException.class, () -> own.addSession("a", () -> null, new int[] {STREAM_ID}));
        assertThrows(IllegalStateException.class, () -> own.removeSession("a"));
    }
}
