package group.gnometrading.orchestrator.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.utils.SampledEpochNanoClock;
import io.aeron.Publication;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.Test;

class ShardSamplerTest {

    /** Takes every offer, or refuses all as full. */
    private static final class Publishing implements StreamPublication {
        boolean full;

        @Override
        public long offer(final DirectBuffer buffer, final int offset, final int length) {
            return full ? Publication.BACK_PRESSURED : 1;
        }

        @Override
        public long resend(final DirectBuffer buffer, final int offset, final int length) {
            return offer(buffer, offset, length);
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
        public void close() {}
    }

    private final StreamTestKit kit = new StreamTestKit();
    private final ShardStream quick = kit.publishingStream(10);
    private final ShardStream blocked = kit.publishingStream(11);
    private final Publishing open = new Publishing();
    private final Publishing full = new Publishing();
    private long now = 1_000_000_000L;
    private final StreamPublisherAgent publisher = new StreamPublisherAgent(
            open, () -> 3, () -> now, () -> SampledEpochNanoClock.INSTANCE.nanoTime() + 2_000_000, null);
    private final SampleBuffer samples = new SampleBuffer(64 * 1024, 16, 16 * 1024);
    private final ShardSampler sampler = new ShardSampler(
            samples,
            new CollectorHeartbeat.Shard() {
                @Override
                public List<ShardStream> streams() {
                    return List.of(quick, blocked);
                }

                @Override
                public List<CollectorHeartbeat.Connection> connections() {
                    return List.of(new CollectorHeartbeat.Connection("kalshi-1", "KALSHI", "md-1", 2, true));
                }

                @Override
                public long generation() {
                    return 3;
                }

                @Override
                public long probes() {
                    return 0;
                }

                @Override
                public long probeMismatches() {
                    return 0;
                }
            },
            () -> 0,
            () -> 0,
            60_000);

    private static void book(final ShardStream stream, final long sequence) {
        final Mbp10Schema slot = stream.ring().claim();
        slot.encoder.streamId(stream.streamId()).sequence(sequence);
        stream.ring().publish();
    }

    private JsonNode last() throws Exception {
        final ByteBuffer out = ByteBuffer.allocate(64 * 1024);
        out.put((byte) '[');
        samples.writeTo(out, 16);
        out.put((byte) ']');
        final JsonNode all =
                new ObjectMapper().readTree(new String(out.array(), 0, out.position(), StandardCharsets.UTF_8));
        return all.get(all.size() - 1).get("data");
    }

    @Test
    void sumsWhatTheStreamsSentSinceTheLastSampleAndNamesTheWorst() throws Exception {
        full.full = true;
        quick.publication(open);
        blocked.publication(full);
        publisher.add(quick);
        publisher.add(blocked);
        book(quick, 0);
        publisher.doWork();
        assertFalse(sampler.sampleIfDue(0), "the first look sets where changes count from");

        book(quick, 1);
        book(quick, 2);
        book(blocked, 1);
        publisher.doWork();
        publisher.doWork();
        quick.poll(now);
        blocked.poll(now);
        assertTrue(sampler.sampleIfDue(60_000));

        final JsonNode data = last();
        assertEquals(3, data.get("generation").asLong());
        assertEquals(2, data.get("streams").asInt());
        assertEquals(1, data.get("reconnecting").asInt());
        final JsonNode publish = data.get("publish");
        assertEquals(2, publish.get("offered").asLong(), "the two records since, not the one before");
        assertEquals(2, publish.get("held").asLong(), "the blocked stream's record refused on each pass");
        assertEquals(1, publish.get("streamsHeld").asInt());
        assertEquals(2, publish.get("publications").asInt());
        assertTrue(publish.get("waitPeakNanos").asLong() >= 2_000_000, "its records' time in the shard");
        assertEquals(4, data.get("collect").get("records").asLong());
        assertEquals("stream 11", data.get("worstHeld").get(0).get("name").asText());
        assertEquals(1, data.get("worstHeld").size());
        assertEquals(10, data.get("worstWait").get(0).get("streamId").asInt());
    }
}
