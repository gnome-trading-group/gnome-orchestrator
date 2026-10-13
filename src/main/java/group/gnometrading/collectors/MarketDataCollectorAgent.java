package group.gnometrading.collectors;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.sequencer.SequencedPoller;
import java.util.List;

/**
 * Drains every listing's market data buffer into its collector on one thread.
 *
 * <p>Receive timestamps are stamped by each listing's socket reader, not here, so this agent can sleep when idle
 * without delaying them. It only has to keep up well enough that no buffer fills and blocks its reader.
 */
public final class MarketDataCollectorAgent implements GnomeAgent {

    private final SequencedPoller[] pollers;

    public MarketDataCollectorAgent(List<SequencedPoller> pollers) {
        this.pollers = pollers.toArray(new SequencedPoller[0]);
    }

    @Override
    public int doWork() throws Exception {
        int work = 0;
        for (SequencedPoller poller : pollers) {
            work += poller.poll();
        }
        return work;
    }

    @Override
    public String roleName() {
        return "market-data-collector";
    }
}
