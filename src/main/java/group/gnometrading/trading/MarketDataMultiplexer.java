package group.gnometrading.trading;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.util.Collection;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Merges N per-exchange market data ring buffers into a single combined buffer for the strategy.
 *
 * <p>Polls each source buffer in round-robin and forwards every event to the target via
 * {@link SequencedRingBuffer#publishRaw}. Runs on its own thread; is the sole producer of
 * the target buffer.
 *
 * <p>Only instantiated for multi-listing sessions. Single-listing sessions connect the inbound
 * gateway's market data buffer directly to the strategy with no intermediate hop.
 */
public final class MarketDataMultiplexer implements GnomeAgent {

    private final List<SequencedPoller> sourcePollers;
    private final SequencedRingBuffer<?> target;

    public MarketDataMultiplexer(Collection<SequencedRingBuffer<?>> sources, SequencedRingBuffer<?> target) {
        this.target = target;
        this.sourcePollers =
                sources.stream().map(src -> src.createPoller(this::onEvent)).toList();
    }

    @Override
    public String roleName() {
        return "market-data-multiplexer";
    }

    @Override
    public void onStart() {}

    @Override
    public int doWork() throws Exception {
        int work = 0;
        for (SequencedPoller poller : sourcePollers) {
            work += poller.poll();
        }
        return work;
    }

    private void onEvent(long globalSeq, int templateId, UnsafeBuffer buf, int len) throws Exception {
        target.publishRaw(buf, templateId, len);
    }
}
