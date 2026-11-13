package group.gnometrading.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.metrics.SampleBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SamplePosterTest {

    private final SampleBuffer samples = new SampleBuffer(64 * 1024, 64, 8 * 1024);
    private final List<String> bodies = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000);
    private int status = 200;
    private int sampled;

    private final SamplePoster poster = new SamplePoster(
            new NullLogger(),
            "shard",
            "use1-abc",
            samples,
            List.of(nowMs -> {
                sampled++;
                samples.begin("shard.streams", nowMs).writeObjectEntry("n", sampled);
                samples.commit();
                return true;
            }),
            (body, length) -> {
                bodies.add(new String(body, 0, length, StandardCharsets.UTF_8));
                return status;
            },
            now::get,
            30_000);

    @Test
    void postsTheSubjectAndItsSamplesThenForgetsThem() throws Exception {
        poster.run();

        assertEquals(1, bodies.size());
        final JsonNode body = new ObjectMapper().readTree(bodies.get(0));
        assertEquals("shard", body.get("subjectKind").asText());
        assertEquals("use1-abc", body.get("subjectId").asText());
        assertEquals(1, body.get("samples").size());
        assertEquals("shard.streams", body.get("samples").get(0).get("kind").asText());
        assertEquals(1_000, body.get("samples").get(0).get("tsMs").asLong());
        assertEquals(0, samples.pending());
    }

    @Test
    void samplesEveryTickButPostsOnlyEachInterval() {
        poster.run();
        now.addAndGet(1_000);
        poster.run();
        now.addAndGet(1_000);
        poster.run();

        assertEquals(3, sampled);
        assertEquals(1, bodies.size());
        assertEquals(2, samples.pending());
    }

    @Test
    void keepsWhatAFailedPostCarriedAndSendsItAgain() throws Exception {
        status = 503;
        poster.run();
        assertEquals(1, samples.pending());
        assertEquals(1, poster.failedPosts());

        status = 200;
        now.addAndGet(30_000);
        poster.run();

        final JsonNode body = new ObjectMapper().readTree(bodies.get(1));
        assertEquals(2, body.get("samples").size(), "the first again, and the one taken since");
        assertEquals(0, samples.pending());
        assertEquals(2, poster.posted());
    }

    @Test
    void aBodyNeverPassesThePostLimit() throws Exception {
        final SampleBuffer big = new SampleBuffer(1024 * 1024, 1_024, 8 * 1024);
        final String filler = "x".repeat(4_000);
        for (int i = 0; i < 100; i++) {
            big.begin("runtime", i).writeObjectEntry("filler", filler);
            big.commit();
        }
        final SamplePoster bigPoster = new SamplePoster(
                new NullLogger(), "gateway", "paper:kalshi:main", big, List.of(), (b, l) -> 200, () -> 0, 1);

        final int carried = bigPoster.encode();

        assertTrue(bigPoster.body().position() <= SamplePoster.MAX_POST_BYTES);
        assertTrue(carried > 0 && carried < 100, "as many as fit: " + carried);
        final JsonNode body = new ObjectMapper()
                .readTree(
                        new String(bigPoster.body().array(), 0, bigPoster.body().position(), StandardCharsets.UTF_8));
        assertEquals(carried, body.get("samples").size());
    }
}
