package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.SecurityMaster;
import group.gnometrading.feeds.Assignment;
import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.feed.ConnectionGroup;
import group.gnometrading.gateways.feed.FeedConnection;
import group.gnometrading.gateways.feed.Feeds;
import group.gnometrading.gateways.feed.RateBudget;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectRef;
import group.gnometrading.gateways.feed.VenueProtocol;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.gateways.feed.transport.FrameWriter;
import group.gnometrading.gateways.feed.transport.Transport;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.md.Source;
import group.gnometrading.md.SourceKind;
import group.gnometrading.md.Stream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ShardRuntimeTest {

    private static final long POLL = ShardRuntime.ASSIGNMENT_POLL_NANOS;

    /** A connection that never connects: markets are placed on it and wait, and nothing runs on another thread. */
    private static final class Idle implements Transport<Object>, VenueProtocol<Object> {
        private final FrameWriter writer = new FrameWriter(64) {
            @Override
            protected void send(final ByteBuffer frame) {}

            @Override
            protected boolean isOpen() {
                return false;
            }
        };

        @Override
        public Object channel() {
            return this;
        }

        @Override
        public FrameWriter writer() {
            return writer;
        }

        @Override
        public void configure(final GatewayConfig config) {}

        @Override
        public void open() {}

        @Override
        public void close() {}

        @Override
        public ByteBuffer read() {
            return null;
        }

        @Override
        public boolean sawTraffic() {
            return false;
        }

        @Override
        public Books books() {
            return Books.CHANGES;
        }

        @Override
        public int framesPerCommand() {
            return 1;
        }

        @Override
        public int maxSubjectsPerCommand() {
            return 10;
        }

        @Override
        public void onConnected() {}

        @Override
        public void subscribe(final Object channel, final List<Subject> subjects) {}

        @Override
        public void unsubscribe(final Object channel, final List<Subject> subjects) {}

        @Override
        public void resync(final Object channel, final List<Subject> subjects) {}

        @Override
        public void keepAlive(final Object channel) {}

        @Override
        public void onFrame(final ByteBuffer frame, final long receiveNanos) {}
    }

    private final StreamTestKit kit = new StreamTestKit();
    private final SecurityMaster securityMaster = mock(SecurityMaster.class);
    private final AtomicReference<Assignment.Streams> assigned =
            new AtomicReference<>(new Assignment.Streams(0, new int[0]));
    private final Feeds feeds = new Feeds(new NullLogger(), null);
    private final List<String> sourcesAdded = new ArrayList<>();
    // The runtime's clock starts at 0 in these tests; the agent's must agree, or a failure looks hours old and its
    // connection due a roll.
    private final MarketDataCollectorAgent agent =
            new MarketDataCollectorAgent(new NullLogger(), () -> 1_000, () -> 0L);
    private boolean failNextCollector;
    // The next connection the feeds open throws, as when its credentials can't be loaded.
    private boolean failNextOpen;
    private final ShardAccounts accounts = new ShardAccounts();
    private final StreamTransitions transitions = new StreamTransitions();
    private long wallNanos = 1_000_000;

    /** A publication that records whether it was closed. */
    private static final class FakePublication implements StreamPublication {
        boolean closed;

        @Override
        public long offer(final org.agrona.DirectBuffer buffer, final int offset, final int length) {
            return 1;
        }

        @Override
        public long position() {
            return 0;
        }

        @Override
        public int sessionId() {
            return 1;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final List<Integer> opened = new ArrayList<>();
    private final List<ShardStream> publisherStreams = new ArrayList<>();
    private final StreamPublishing publishing = new StreamPublishing() {
        @Override
        public void add(final ShardStream stream) {
            publisherStreams.add(stream);
        }

        @Override
        public void retire(final ShardStream stream) {
            publisherStreams.remove(stream);
        }

        @Override
        public StreamPublication open(final int streamId) {
            opened.add(streamId);
            return new FakePublication();
        }
    };
    private final ShardRuntime runtime = new ShardRuntime(
            new NullLogger(),
            securityMaster,
            () -> {
                final Assignment.Streams streams = assigned.get();
                if (streams == null) {
                    throw new IllegalStateException("S3 is down");
                }
                return streams;
            },
            feeds,
            source -> {
                sourcesAdded.add(source.code());
                if (!feeds.hasSource(source.code())) {
                    feeds.addSource(
                            new Feeds.Source(
                                    source.code(),
                                    id -> new SubjectRef(id, Projection.IDENTITY),
                                    16,
                                    null,
                                    RateBudget.UNLIMITED),
                            source.maxPerConnection(),
                            (name, table, listener) -> {
                                if (failNextOpen) {
                                    failNextOpen = false;
                                    throw new IllegalStateException("no credentials");
                                }
                                return idle(name, table, listener);
                            });
                }
            },
            agent,
            (streamId, name) -> {
                if (failNextCollector) {
                    failNextCollector = false;
                    return kit.stream(streamId, (sequence, templateId, buffer, length) -> {
                        throw new IllegalStateException("bad record");
                    });
                }
                return kit.stream(streamId);
            },
            source -> null,
            accounts,
            transitions,
            () -> wallNanos,
            publishing);

    ShardRuntimeTest() {
        when(securityMaster.getSource(1))
                .thenReturn(new Source(
                        1,
                        "KALSHI",
                        SourceKind.VENUE,
                        1,
                        "us-east-1",
                        "KALSHI",
                        new String[] {"book"},
                        true,
                        250,
                        "KEY",
                        180,
                        0));
        for (int streamId = 10; streamId < 14; streamId++) {
            when(securityMaster.getStream(streamId))
                    .thenReturn(new Stream(
                            streamId,
                            1,
                            "KALSHI",
                            "LISTING",
                            streamId,
                            "T" + streamId,
                            "book",
                            "us-east-1",
                            0,
                            0,
                            0,
                            true));
        }
    }

    private static ConnectionGroup.Open<Object> idle(
            final String name,
            final group.gnometrading.gateways.feed.SubjectTable table,
            final group.gnometrading.gateways.feed.FeedListener listener) {
        Idle idle = new Idle();
        return new ConnectionGroup.Open<>(
                new FeedConnection<>(
                        name,
                        new NullLogger(),
                        idle,
                        idle,
                        table,
                        new GatewayConfig.Builder().build(),
                        System::currentTimeMillis,
                        System::nanoTime,
                        ConnectLimiter.UNLIMITED,
                        RateBudget.UNLIMITED,
                        listener),
                () -> {});
    }

    private ShardStream running(final int streamId) {
        return runtime.streams().stream()
                .filter(s -> s.streamId() == streamId)
                .findFirst()
                .orElse(null);
    }

    @Test
    void assignedStreamsStartOnTheirSourcesConnections() {
        assigned.set(new Assignment.Streams(1, new int[] {10, 11}));
        runtime.tick(0);

        assertEquals(1, runtime.generation());
        assertEquals(2, runtime.streams().size());
        assertSame(feeds.subject(10), running(10).subject());
        assertEquals("T10", running(10).subject().key());
        assertEquals(List.of("KALSHI", "KALSHI"), sourcesAdded);
        assertEquals(1, runtime.connections().size(), "both on one connection");
    }

    @Test
    void everyStartedStreamIsHandedToThePublisherAndLeasedOnesArePublished() {
        assigned.set(new Assignment.Streams(1, new int[] {10, 11}, Map.of(), Map.of(), Map.of(), new int[] {10}));
        runtime.tick(0);

        assertEquals(
                List.of(10, 11),
                publisherStreams.stream().map(ShardStream::streamId).sorted().toList());
        assertEquals(List.of(10), opened);
        assertNotNull(running(10).publication());
        assertNull(running(11).publication());
    }

    @Test
    void aLeaseThatEndsClosesThePublicationAndAStoppedStreamClosesItsOwn() {
        assigned.set(new Assignment.Streams(1, new int[] {10, 11}, Map.of(), Map.of(), Map.of(), new int[] {10, 11}));
        runtime.tick(0);
        final FakePublication ten = (FakePublication) running(10).publication();
        final FakePublication eleven = (FakePublication) running(11).publication();

        assigned.set(new Assignment.Streams(2, new int[] {11}, Map.of(), Map.of(), Map.of(), new int[0]));
        runtime.tick(POLL);

        assertTrue(ten.closed, "its stream stopped");
        assertTrue(eleven.closed, "no longer leased");
        assertNull(running(11).publication());
        assertEquals(
                List.of(11),
                publisherStreams.stream().map(ShardStream::streamId).toList());
        assertEquals(List.of(10, 11), opened, "nothing opened twice");
    }

    @Test
    void aStillLeasedStreamKeepsItsPublicationAcrossPolls() {
        assigned.set(new Assignment.Streams(1, new int[] {10}, Map.of(), Map.of(), Map.of(), new int[] {10}));
        runtime.tick(0);
        final StreamPublication first = running(10).publication();

        runtime.tick(POLL);

        assertSame(first, running(10).publication());
        assertEquals(List.of(10), opened);
    }

    @Test
    void aChangedAssignmentStartsAndStopsOnlyWhatChanged() {
        assigned.set(new Assignment.Streams(1, new int[] {10, 11}));
        runtime.tick(0);
        ShardStream kept = running(11);

        assigned.set(new Assignment.Streams(2, new int[] {11, 12}));
        runtime.tick(POLL - 1);
        assertNotNull(running(10), "polled every ten seconds");
        runtime.tick(POLL);

        assertNull(running(10));
        assertNull(feeds.subject(10));
        assertSame(kept, running(11));
        assertNotNull(running(12));
        assertEquals(2, runtime.generation());
    }

    @Test
    void aStreamThatCantStartIsRetriedLater() {
        assigned.set(new Assignment.Streams(1, new int[] {10, 99}));
        runtime.tick(0);
        assertNull(running(99));

        Stream thirteen = securityMaster.getStream(13);
        when(securityMaster.getStream(99)).thenReturn(thirteen);
        runtime.tick(POLL);
        assertNull(running(99), "not before the retry delay");
        runtime.tick(ShardRuntime.RETRY_NANOS);
        assertNotNull(running(99));
    }

    @Test
    void anUnreadableAssignmentKeepsWhatIsRunning() {
        assigned.set(new Assignment.Streams(1, new int[] {10}));
        runtime.tick(0);
        assigned.set(null);
        runtime.tick(POLL);

        assertNotNull(running(10));
        assertEquals(1, runtime.generation());
    }

    @Test
    void aStreamWhoseCollectorFailedStartsOverAfterTheRetryDelay() throws Exception {
        failNextCollector = true;
        assigned.set(new Assignment.Streams(1, new int[] {10}));
        runtime.tick(0);
        ShardStream failing = running(10);
        StreamTestKit.publish(failing, 1);
        agent.doWork();
        assertNotNull(failing.failure());

        runtime.tick(failing.failedAtNanos() + ShardRuntime.RETRY_NANOS);
        assertNotSame(failing, running(10));
        assertNull(running(10).failure());
    }

    @Test
    void aStreamWhoseConnectionFailedToOpenIsOnAConnectionOnceRetried() {
        failNextOpen = true;
        assigned.set(new Assignment.Streams(1, new int[] {10}));
        runtime.tick(0);
        assertNull(running(10));

        runtime.tick(ShardRuntime.RETRY_NANOS);
        assertNotNull(running(10));
        assertNotNull(running(10).subject().host(), "placed on a connection, not left subscribing forever");
        assertEquals(1, runtime.connections().size());
    }

    @Test
    void aTickAfterStopAllStartsNothingAgain() {
        assigned.set(new Assignment.Streams(1, new int[] {10, 11}));
        runtime.tick(5);
        runtime.stopAll(new CountDownLatch(2));

        runtime.tick(POLL + 5);
        assertTrue(runtime.streams().isEmpty());
        assertTrue(runtime.connections().isEmpty());
        assertEquals(5, runtime.lastTickNanos(), "no tick ran after the stop");
    }

    @Test
    void aFailedAssignmentReadStillRestartsFailedStreams() throws Exception {
        failNextCollector = true;
        assigned.set(new Assignment.Streams(1, new int[] {10}));
        runtime.tick(0);
        ShardStream failing = running(10);
        StreamTestKit.publish(failing, 1);
        agent.doWork();

        assigned.set(null);
        runtime.tick(failing.failedAtNanos() + ShardRuntime.RETRY_NANOS);
        assertNotSame(failing, running(10));
        assertEquals(failing.failedAtNanos() + ShardRuntime.RETRY_NANOS, runtime.lastTickNanos());
    }

    @Test
    void anEmptyAssignmentStopsEverything() {
        assigned.set(new Assignment.Streams(1, new int[] {10, 11}));
        runtime.tick(0);

        // What a missing assignment file reads as.
        assigned.set(new Assignment.Streams(0, new int[0]));
        runtime.tick(POLL);
        assertTrue(runtime.streams().isEmpty());
        assertEquals(0, runtime.generation());
    }

    @Test
    void theAssignmentBindsEachSourcesAccountBeforeAnyStreamStarts() {
        assigned.set(new Assignment.Streams(1, new int[] {10}, Map.of("KALSHI", "md-2")));
        runtime.tick(0);

        assertEquals("md-2", accounts.label("KALSHI"));
        assertEquals("md-1", accounts.label("POLYMARKET_US"), "a source not named uses md-1");
    }

    @Test
    void startsStateChangesAndStopsAreRecordedWithTheirWallClockTime() {
        assigned.set(new Assignment.Streams(1, new int[] {10}));
        runtime.tick(0);
        wallNanos = 2_000_000;
        assigned.set(new Assignment.Streams(2, new int[0]));
        runtime.tick(POLL);

        int[] ids = new int[StreamTransitions.CAPACITY];
        byte[] from = new byte[StreamTransitions.CAPACITY];
        byte[] to = new byte[StreamTransitions.CAPACITY];
        long[] at = new long[StreamTransitions.CAPACITY];
        assertEquals(2, transitions.copyTo(ids, from, to, at));
        assertEquals(10, ids[0]);
        assertEquals("NONE", StreamTransitions.name(from[0]));
        assertEquals("SUBSCRIBING", StreamTransitions.name(to[0]), "placed on a connection that never connects");
        assertEquals(1_000_000, at[0]);
        assertEquals("SUBSCRIBING", StreamTransitions.name(from[1]));
        assertEquals("NONE", StreamTransitions.name(to[1]), "stopped");
        assertEquals(2_000_000, at[1]);
    }
}
