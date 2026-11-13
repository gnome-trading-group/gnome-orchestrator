package group.gnometrading.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.schemas.Intent;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class RingSamplerTest {

    private final SampleBuffer samples = new SampleBuffer(64 * 1024, 16, 8 * 1024);
    private final SequencedRingBuffer<Intent> intents =
            new SequencedRingBuffer<>(Intent::new, new GlobalSequence(), 16);
    private final SequencedPoller oms = intents.createPoller((seq, template, buffer, length) -> {});
    private final RingSampler sampler =
            new RingSampler(samples, "session.rings", List.of("intents"), List.of(intents), 60_000);

    private void publish(int count) {
        for (int i = 0; i < count; i++) {
            intents.claim();
            intents.publish();
        }
    }

    private JsonNode last() throws Exception {
        ByteBuffer out = ByteBuffer.allocate(64 * 1024);
        out.put((byte) '[');
        samples.writeTo(out, 16);
        out.put((byte) ']');
        JsonNode all = new ObjectMapper().readTree(new String(out.array(), 0, out.position(), StandardCharsets.UTF_8));
        return all.get(all.size() - 1).get("data").get("intents");
    }

    @Test
    void reportsTheIntervalsWorstBacklogNotJustTheLast() throws Exception {
        assertTrue(sampler.sampleIfDue(0));
        publish(6);
        assertFalse(sampler.sampleIfDue(1_000), "a look, not a sample");
        oms.poll();
        publish(2);
        assertTrue(sampler.sampleIfDue(60_000));

        JsonNode ring = last();
        assertEquals(16, ring.get("capacity").asLong());
        assertEquals(2, ring.get("backlog").asLong());
        assertEquals(6, ring.get("backlogPeak").asLong());
        assertEquals(8, ring.get("published").asLong());
        assertEquals(0, ring.get("fullWaits").asLong());
    }

    @Test
    void thePeakStartsAgainEachInterval() throws Exception {
        sampler.sampleIfDue(0);
        publish(5);
        sampler.sampleIfDue(1_000);
        oms.poll();
        sampler.sampleIfDue(60_000);
        sampler.sampleIfDue(120_000);

        assertEquals(0, last().get("backlogPeak").asLong());
    }
}
