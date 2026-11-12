package group.gnometrading.orchestrator.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.marketdata.MarketData;
import group.gnometrading.orchestrator.runtime.GatewayHealth;
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
 * it. Closing the strategy before its market data used to leave the market data's writer waiting forever on the
 * strategy's full buffer: the close never finished, and the ledger's final write never ran.
 */
class SessionAgentsCloseTest {

    @Test
    @Timeout(30)
    void aSessionClosesWhileItsMarketDataIsStillArriving() throws Exception {
        // As the session builds it: the strategy's buffer at its default size, written by the market data and read by
        // the strategy and by the OMS (its price writer).
        final SequencedRingBuffer<Mbp10Schema> strategyMd =
                new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        final GnomeAgentRunner strategy = start(polling("strategy", strategyMd.createPoller((s, t, b, l) -> {})));
        final GnomeAgentRunner oms = start(polling("oms", strategyMd.createPoller((s, t, b, l) -> {})));
        final GnomeAgentRunner feed = start(new Feed(strategyMd));
        // The order writers send their cancels as they close, one REST call at a time: seconds, during which a busy
        // feed fills the strategy's buffer many times over.
        final GnomeAgentRunner slowOrderWriter = start(new SlowToClose());
        Thread.sleep(300);

        final MarketData marketData = new MarketData() {
            @Override
            public void start(final org.agrona.ErrorHandler errorHandler) {}

            @Override
            public List<GatewayHealth> health() {
                return List.of();
            }

            @Override
            public void close() throws Exception {
                feed.close();
            }
        };
        final SessionAgents started =
                new SessionAgents(marketData, strategy, oms, List.of(slowOrderWriter), null, null, null);
        final Thread closing = new Thread(() -> SessionAgents.close(started, null, null, new NullLogger()));
        closing.setDaemon(true);
        closing.start();
        closing.join(TimeUnit.SECONDS.toMillis(15));

        assertTrue(!closing.isAlive(), "every agent closed");
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

    /** The session's market data, about a book a millisecond: a busy stream. */
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
