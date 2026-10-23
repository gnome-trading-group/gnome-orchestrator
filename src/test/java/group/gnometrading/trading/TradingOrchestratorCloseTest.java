package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.GatewayRunners;
import group.gnometrading.gateways.ReaderPauseControl;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A producer waits while the buffer it writes to is full, so the session must never close a reader before what feeds
 * it. Closing the strategy before its market data used to leave the multiplexer waiting forever on the strategy's full
 * buffer: the close never finished, and the ledger's final write never ran.
 */
class TradingOrchestratorCloseTest {

    @Test
    @Timeout(30)
    void aMultiListingSessionClosesWhileItsMarketDataIsStillArriving() throws Exception {
        final SequencedRingBuffer<Mbp10Schema> listingA =
                new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        final SequencedRingBuffer<Mbp10Schema> listingB =
                new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        // As the session builds it: the strategy's buffer at its default size, read by the strategy and price writer.
        final SequencedRingBuffer<?> strategyMd = new SequencedRingBuffer<>(Intent::new, new GlobalSequence());
        final MarketDataMultiplexer mux = new MarketDataMultiplexer(List.of(listingA, listingB), strategyMd);
        final GnomeAgentRunner strategy = start(polling("strategy", strategyMd.createPoller((s, t, b, l) -> {})));
        final GnomeAgentRunner priceWriter =
                start(polling("price-writer", strategyMd.createPoller((s, t, b, l) -> {})));
        final GatewayRunners gatewayA = marketDataGateway(listingA);
        final GatewayRunners gatewayB = marketDataGateway(listingB);
        final GnomeAgentRunner muxRunner = start(mux);
        // The order writers send their cancels as they close, one REST call at a time: seconds, during which a busy
        // feed fills the strategy's buffer many times over.
        final GnomeAgentRunner slowOrderWriter = start(new SlowToClose());
        Thread.sleep(300);

        final TradingOrchestrator.AgentRunners started = new TradingOrchestrator.AgentRunners(
                List.of(gatewayA, gatewayB),
                strategy,
                null,
                List.of(slowOrderWriter),
                muxRunner,
                null,
                null,
                priceWriter,
                null);
        final Thread closing =
                new Thread(() -> TradingOrchestrator.closeAgents(started, List.of(), null, null, new NullLogger()));
        closing.setDaemon(true);
        closing.start();
        closing.join(TimeUnit.SECONDS.toMillis(15));

        assertTrue(!closing.isAlive(), "every agent closed");
    }

    private static GatewayRunners marketDataGateway(final SequencedRingBuffer<Mbp10Schema> ring) {
        return GatewayRunners.marketData(
                        1,
                        new ReaderPauseControl(),
                        new GnomeAgentRunner(new Feed(ring), Throwable::printStackTrace),
                        null,
                        new GnomeAgentRunner(polling("supervisor", () -> 0), Throwable::printStackTrace))
                .start();
    }

    private static GnomeAgentRunner start(final GnomeAgent agent) {
        final GnomeAgentRunner runner = new GnomeAgentRunner(agent, Throwable::printStackTrace);
        GnomeAgentRunner.startOnThread(runner);
        return runner;
    }

    private interface Work {
        int poll() throws Exception;
    }

    private static GnomeAgent polling(final String name, final SequencedPoller poller) {
        return polling(name, poller::poll);
    }

    private static GnomeAgent polling(final String name, final Work work) {
        return new GnomeAgent() {
            @Override
            public int doWork() throws Exception {
                return work.poll();
            }

            @Override
            public String roleName() {
                return name;
            }
        };
    }

    /** A listing's market data, about a book a millisecond: a busy feed. */
    private static final class Feed implements GnomeAgent {
        private final SequencedRingBuffer<Mbp10Schema> ring;

        Feed(final SequencedRingBuffer<Mbp10Schema> ring) {
            this.ring = ring;
        }

        @Override
        public int doWork() throws Exception {
            final Mbp10Schema schema = this.ring.claim();
            schema.encoder.bidPrice0(1);
            this.ring.publish();
            Thread.sleep(1);
            return 1;
        }

        @Override
        public String roleName() {
            return "feed";
        }
    }

    private static final class SlowToClose implements GnomeAgent {
        @Override
        public int doWork() {
            return 0;
        }

        @Override
        public void onClose() {
            try {
                Thread.sleep(2_000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public String roleName() {
            return "order-writer";
        }
    }
}
