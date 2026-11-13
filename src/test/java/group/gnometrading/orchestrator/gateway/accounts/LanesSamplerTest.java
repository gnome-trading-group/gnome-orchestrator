package group.gnometrading.orchestrator.gateway.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.schemas.gateway.AdmissionAction;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class LanesSamplerTest {

    private final long[] now = {1_000};
    private final long[] refusals = {0};
    private final LaneAdmissions admissions = new LaneAdmissions(5, JournalRecorder.NONE, () -> this.now[0]);
    private final SampleBuffer samples = new SampleBuffer(64 * 1024, 16, 16 * 1024);
    private final LanesSampler sampler = new LanesSampler(
            samples,
            List.of(new LanesSampler.Lane(5, "kalshi-main", () -> refusals[0], admissions)),
            slot -> slot == 11 ? "session-a" : null,
            () -> this.now[0]);

    private JsonNode all() throws Exception {
        final ByteBuffer out = ByteBuffer.allocate(64 * 1024);
        out.put((byte) '[');
        samples.writeTo(out, 16);
        out.put((byte) ']');
        return new ObjectMapper().readTree(new String(out.array(), 0, out.position(), StandardCharsets.UTF_8));
    }

    @Test
    void eachClosedLaneMinuteGoesOutOnceWithItsSessionsSends() throws Exception {
        assertFalse(sampler.sampleIfDue(60_000), "no minute has closed");
        admissions.decided(11, 1, RequestClass.NEW, AdmissionAction.SENT, 0, 10);
        admissions.decided(12, 1, RequestClass.CANCEL, AdmissionAction.SENT, 0, 10);
        admissions.decided(12, 2, RequestClass.NEW, AdmissionAction.HELD, 0, 0);
        refusals[0] = 2;
        now[0] += LaneAdmissions.MINUTE_NANOS;
        admissions.connectionsBusy();
        now[0] += 5_000_000;

        assertTrue(sampler.sampleIfDue(120_000));
        assertFalse(sampler.sampleIfDue(121_000), "the same minute once");

        final JsonNode sample = all().get(0);
        assertEquals(LanesSampler.KIND, sample.get("kind").asText());
        assertEquals(119_995, sample.get("tsMs").asLong(), "when the minute ended");
        final JsonNode data = sample.get("data");
        assertEquals(5, data.get("accountId").asInt());
        assertEquals(1, data.get("orders").asLong());
        assertEquals(1, data.get("cancels").asLong());
        assertEquals(1, data.get("held").asLong());
        assertEquals(2, data.get("venueRefusals").asLong());
        assertEquals(2, data.get("sessions").size());
        for (final JsonNode session : data.get("sessions")) {
            if (session.get("slot").asLong() == 11) {
                assertEquals("session-a", session.get("sessionId").asText());
            } else {
                assertTrue(session.get("sessionId").isNull(), "a slot no session holds now");
            }
            assertEquals(1, session.get("sent").asLong());
        }
    }
}
