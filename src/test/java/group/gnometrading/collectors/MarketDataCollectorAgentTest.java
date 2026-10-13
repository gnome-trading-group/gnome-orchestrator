package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketDataCollectorAgentTest {

    @Test
    void drainsEveryListingInOnePass() throws Exception {
        SequencedRingBuffer<Mbp10Schema> first = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        SequencedRingBuffer<Mbp10Schema> second = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        List<String> received = new ArrayList<>();
        MarketDataCollectorAgent agent = new MarketDataCollectorAgent(List.of(
                first.createPoller((seq, templateId, buffer, length) -> received.add("first-" + seq)),
                second.createPoller((seq, templateId, buffer, length) -> received.add("second-" + seq))));

        publish(first, 2);
        publish(second, 1);

        assertEquals(3, agent.doWork());
        assertEquals(List.of("first-1", "first-2", "second-1"), received);
    }

    @Test
    void reportsNoWorkWhenIdleSoTheAgentCanSleep() throws Exception {
        SequencedRingBuffer<Mbp10Schema> buffer = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        MarketDataCollectorAgent agent =
                new MarketDataCollectorAgent(List.of(buffer.createPoller((seq, templateId, data, length) -> {})));

        publish(buffer, 1);
        agent.doWork();

        assertEquals(0, agent.doWork());
    }

    private static void publish(SequencedRingBuffer<Mbp10Schema> buffer, int count) {
        for (int i = 0; i < count; i++) {
            buffer.claim();
            buffer.publish();
        }
    }
}
