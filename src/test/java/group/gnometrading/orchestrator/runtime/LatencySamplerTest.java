package group.gnometrading.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.latency.LatencyGrid;
import group.gnometrading.latency.MinuteHistograms;
import group.gnometrading.metrics.SampleBuffer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class LatencySamplerTest {

    private static final long MINUTE = MinuteHistograms.MINUTE_NANOS;
    private static final String[] NO_LABEL = {""};

    private final SampleBuffer samples = new SampleBuffer(64 * 1024, 16, 8 * 1024);
    private final MinuteHistograms histograms = new MinuteHistograms(2 * LatencyGrid.size(0, 1, 1));
    private final LatencyGrid wait =
            new LatencyGrid(histograms, 102, "shard.wait", new int[0], new String[0], NO_LABEL, NO_LABEL);
    private final LatencyGrid unused =
            new LatencyGrid(histograms, 103, "shard.unused", new int[0], new String[0], NO_LABEL, NO_LABEL);
    private final LatencySampler sampler =
            new LatencySampler(samples, List.of(histograms), List.of("latency.shard.publisher"));

    private JsonNode all() throws Exception {
        final ByteBuffer out = ByteBuffer.allocate(64 * 1024);
        out.put((byte) '[');
        samples.writeTo(out, 16);
        out.put((byte) ']');
        return new ObjectMapper().readTree(new String(out.array(), 0, out.position(), StandardCharsets.UTF_8));
    }

    @Test
    void aClosedMinuteGoesOutOnceWithOnlyTheKeysThatRecorded() throws Exception {
        final long minute = 100 * MINUTE;
        for (int i = 1; i <= 100; i++) {
            wait.record(0, 0, 0, i * 1_000L, minute + i);
        }
        assertFalse(sampler.sampleIfDue(0), "nothing closed yet");

        histograms.roll(minute + MINUTE);
        assertTrue(sampler.sampleIfDue(0));
        assertFalse(sampler.sampleIfDue(0), "the same minute once");

        final JsonNode sample = all().get(0);
        assertEquals("latency.shard.publisher", sample.get("kind").asText());
        assertEquals(minute / 1_000_000, sample.get("tsMs").asLong());
        final JsonNode keys = sample.get("data").get("keys");
        assertEquals(1, keys.size());
        assertEquals("shard.wait", keys.get(0).get("m").asText());
        assertEquals(100, keys.get(0).get("n").asLong());
        assertEquals(100_000, keys.get(0).get("max").asLong());
    }

    @Test
    void aMinuteWithNothingRecordedSendsNothing() {
        histograms.roll(100 * MINUTE);
        histograms.roll(101 * MINUTE);

        assertFalse(sampler.sampleIfDue(0));
        assertEquals(0, samples.pending());
    }
}
