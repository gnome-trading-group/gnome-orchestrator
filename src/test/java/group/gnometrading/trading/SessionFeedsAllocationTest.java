package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.SecurityMaster;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.feeds.FeedVenues;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.md.Source;
import group.gnometrading.md.SourceKind;
import group.gnometrading.md.Stream;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.drafts.RFC6455;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Intent;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A two-listing session's market data, as {@link TradingOrchestrator} builds it: {@link SessionFeeds} on the real
 * connection and its threads, replaying venue frames, through the {@link MarketDataMultiplexer} into the strategy's
 * buffer, read on a thread as the strategy would. Once warm, none of the threads it starts may allocate: a GC pause on
 * any of them stalls the market data the strategy trades on.
 */
@Tag("allocation")
class SessionFeedsAllocationTest {

    private static final int FRAMES = 400_000;
    private static final long INTERVAL_NANOS = 2_000;

    /** Whole Hyperliquid books for two coins, alternating, handed out one every INTERVAL_NANOS. */
    static final class ReplayClient extends WebSocketClient {
        private final ByteBuffer[] frames = new ByteBuffer[FRAMES];
        private final Response response = new Response();
        final AtomicLong served = new AtomicLong();
        private long nextDue;

        ReplayClient() throws IOException {
            super(URI.create("ws://localhost:1/"), new RFC6455(), new NativeSocketFactory(), 1 << 20, 1 << 16);
            for (int i = 0; i < FRAMES; i++) {
                final StringBuilder bids = new StringBuilder();
                final StringBuilder asks = new StringBuilder();
                for (int level = 0; level < 20; level++) {
                    bids.append(level == 0 ? "" : ",")
                            .append("{\"px\":\"")
                            .append(2300 - level)
                            .append(".5\",\"sz\":\"")
                            .append(1 + (i + level) % 7)
                            .append(".25\",\"n\":3}");
                    asks.append(level == 0 ? "" : ",")
                            .append("{\"px\":\"")
                            .append(2301 + level)
                            .append(".5\",\"sz\":\"")
                            .append(1 + (i + level) % 5)
                            .append(".75\",\"n\":2}");
                }
                frames[i] = ByteBuffer.wrap(("{\"channel\":\"l2Book\",\"data\":{\"coin\":\"C" + (i % 2) + "\",\"time\":"
                                + (1700000000000L + i) + ",\"levels\":[[" + bids + "],[" + asks + "]]}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override
        public void connect() {}

        @Override
        public void close() {}

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean writeBuffer(final ByteBuffer frame) {
            frame.position(frame.limit());
            return true;
        }

        @Override
        public WebSocketResponse read() {
            final long i = served.get();
            final long now = System.nanoTime();
            if (i == FRAMES || now < nextDue) {
                return response.update(true, Opcode.TEXT, null, false);
            }
            nextDue = now + INTERVAL_NANOS;
            final ByteBuffer frame = frames[(int) i];
            frame.position(0);
            served.lazySet(i + 1);
            return response.update(true, Opcode.TEXT, frame, false);
        }
    }

    static final class Response extends WebSocketResponse {}

    private static Listing listing(final int id, final String coin) {
        return new Listing(
                id,
                new Exchange(3, "HYPERLIQUID", "Hyperliquid", "global"),
                new Security(id, coin, null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                coin,
                coin);
    }

    @Test
    @Timeout(120)
    void noThreadOfASessionsMarketDataAllocatesOnceWarm() throws Exception {
        final SecurityMaster securityMaster = mock(SecurityMaster.class);
        for (int id = 1; id <= 2; id++) {
            when(securityMaster.streamForListing(id))
                    .thenReturn(new Stream(
                            100 + id,
                            9,
                            "HYPERLIQUID",
                            "LISTING",
                            id,
                            "C" + (id - 1),
                            "book",
                            "global",
                            0,
                            0,
                            0,
                            true));
        }
        when(securityMaster.getSource(9))
                .thenReturn(new Source(
                        9,
                        "HYPERLIQUID",
                        SourceKind.VENUE,
                        3,
                        "ap-northeast-1",
                        "HYPERLIQUID",
                        new String[] {"book"},
                        true,
                        50,
                        "IP",
                        8,
                        0));
        final ReplayClient client = new ReplayClient();
        final Set<Long> before = threadIds();
        final SessionFeeds feeds = new SessionFeeds(
                new NullLogger(),
                securityMaster,
                new FeedVenues.Context(
                        new NullLogger(), new Properties("orchestrator.dev.properties"), null, System::nanoTime),
                List.of(listing(1, "C0"), listing(2, "C1")),
                null,
                false,
                (status, cause) -> {},
                System::nanoTime,
                (uri, readBufferBytes) -> client);
        final SequencedRingBuffer<Intent> strategyMarketData =
                new SequencedRingBuffer<>(Intent::new, new GlobalSequence());
        final MarketDataMultiplexer mux = new MarketDataMultiplexer(feeds.rings(), strategyMarketData);
        final AtomicLong strategySeen = new AtomicLong();
        final SequencedPoller strategy =
                strategyMarketData.createPoller((seq, templateId, buffer, length) -> strategySeen.lazySet(seq));
        final GnomeAgentRunner muxRunner = new GnomeAgentRunner(mux, Throwable::printStackTrace);
        GnomeAgentRunner.startOnThread(muxRunner);
        final AtomicBoolean running = new AtomicBoolean(true);
        final Thread strategyThread = new Thread(
                () -> {
                    try {
                        while (running.get()) {
                            strategy.poll();
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                "strategy");
        strategyThread.start();
        feeds.start(Throwable::printStackTrace);
        try {
            awaitServed(client, FRAMES / 2);
            final com.sun.management.ThreadMXBean threads =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            final Map<Long, Long> start = new HashMap<>();
            for (long id : threadIds()) {
                if (!before.contains(id)) {
                    start.put(id, threads.getThreadAllocatedBytes(id));
                }
            }
            final long seenAtStart = strategySeen.get();
            awaitServed(client, FRAMES);
            final StringBuilder report = new StringBuilder();
            long worst = 0;
            for (Map.Entry<Long, Long> entry : start.entrySet()) {
                final long allocated = threads.getThreadAllocatedBytes(entry.getKey()) - entry.getValue();
                final String name = threads.getThreadInfo(entry.getKey()).getThreadName();
                report.append(name).append('=').append(allocated).append("B ");
                worst = Math.max(worst, allocated);
            }
            System.out.println("#### per-thread allocation over " + (FRAMES / 2) + " books: " + report);
            assertTrue(strategySeen.get() - seenAtStart > FRAMES / 4, "the strategy kept receiving books");
            assertTrue(worst < 16_384, "a market data thread allocated " + worst + " bytes: " + report);
        } finally {
            running.set(false);
            strategyThread.join();
            feeds.close();
            muxRunner.close();
        }
    }

    private static void awaitServed(final ReplayClient client, final long count) throws InterruptedException {
        while (client.served.get() < count) {
            Thread.sleep(10);
        }
    }

    private static Set<Long> threadIds() {
        final Set<Long> ids = new HashSet<>();
        for (long id : ManagementFactory.getThreadMXBean().getAllThreadIds()) {
            ids.add(id);
        }
        return ids;
    }
}
