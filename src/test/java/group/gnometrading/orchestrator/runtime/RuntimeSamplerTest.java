package group.gnometrading.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.ThreadMXBean;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.host.HostReadings;
import group.gnometrading.host.JvmReadings;
import group.gnometrading.metrics.SampleBuffer;
import io.aeron.driver.status.SystemCounterDescriptor;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeSamplerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private final SampleBuffer samples = new SampleBuffer(256 * 1024, 64, 32 * 1024);
    private final CountersManager counters = new CountersManager(
            new UnsafeBuffer(ByteBuffer.allocateDirect(1 << 20)), new UnsafeBuffer(ByteBuffer.allocateDirect(1 << 16)));
    private GnomeAgentRunner agent;
    private HostReadings host;

    /** Spins, so its loop passes and CPU time grow. */
    private static final class Spinner implements GnomeAgent {
        @Override
        public int doWork() {
            return 1;
        }

        @Override
        public String roleName() {
            return "spinner";
        }
    }

    @BeforeEach
    void start() throws Exception {
        // The driver's system counters take ids 0 upwards in declaration order, as here.
        for (SystemCounterDescriptor descriptor : SystemCounterDescriptor.values()) {
            assertEquals(descriptor.id(), counters.allocate(descriptor.label()));
        }
        Files.writeString(
                dir.resolve("clock.status"),
                "written_ms 1\nsource PHC\nstratum 1\noffset_ns 12\nerror_bound_ns 34\nphc_error_bound_ns 20\n");
        agent = new GnomeAgentRunner(new Spinner(), Throwable::printStackTrace);
        GnomeAgentRunner.startOnThread(agent);
        host = HostReadings.ofThisProcess(dir.toFile());
    }

    @AfterEach
    void stop() throws Exception {
        agent.close();
        host.close();
    }

    private RuntimeSampler sampler(SchedulingLatencyProbe probe) {
        return new RuntimeSampler(
                samples,
                host,
                new JvmReadings(),
                new ClockReadings(dir.resolve("clock.status"), () -> 1_000L),
                probe,
                counters,
                List.of(agent),
                10_000);
    }

    private JsonNode sent(int index) throws Exception {
        ByteBuffer out = ByteBuffer.allocate(256 * 1024);
        out.put((byte) '[');
        samples.writeTo(out, 64);
        out.put((byte) ']');
        return MAPPER.readTree(new String(out.array(), 0, out.position(), StandardCharsets.UTF_8))
                .get(index);
    }

    @Test
    void theFirstSampleHasOnlyGaugesAndTheNextTheChangeSince() throws Exception {
        RuntimeSampler sampler = sampler(null);
        sampler.sample(1_000);
        counters.setCounterValue(SystemCounterDescriptor.NAK_MESSAGES_SENT.id(), 7);
        counters.setCounterValue(SystemCounterDescriptor.SENDER_MAX_CYCLE_TIME.id(), 250_000);
        Thread.sleep(50);
        sampler.sample(11_000);

        JsonNode first = sent(0);
        assertEquals("runtime", first.get("kind").asText());
        JsonNode firstData = first.get("data");
        assertFalse(firstData.get("host").has("netRxBytes"), "no previous sample to take a change from");
        assertFalse(firstData.get("threads").get(0).has("cycles"));
        assertFalse(firstData.get("aeron").has("naksSent"));
        assertEquals("PHC", firstData.get("clock").get("source").asText());
        assertEquals(34, firstData.get("clock").get("errorBoundNanos").asLong());

        JsonNode data = sent(1).get("data");
        assertEquals(10_000, data.get("intervalMs").asLong());
        JsonNode thread = data.get("threads").get(0);
        assertEquals("spinner", thread.get("name").asText());
        assertTrue(thread.get("cycles").asLong() > 0, "a running agent's loop moved");
        assertTrue(thread.get("cpuNanos").asLong() > 0, "a spinning agent used CPU");
        assertEquals(7, data.get("aeron").get("naksSent").asLong());
        assertEquals(250_000, data.get("aeron").get("senderMaxCycleNs").asLong());
        assertTrue(data.get("jvm").get("heapUsedBytes").asLong() > 0);
        assertTrue(data.get("jvm").get("gc").get(0).has("collections"));
        assertTrue(data.get("scheduling").isNull(), "no probe");
        assertTrue(data.get("host").has("tcpRetransmits"));
    }

    @Test
    void itSamplesOncePerInterval() {
        RuntimeSampler sampler = sampler(null);
        assertTrue(sampler.sampleIfDue(1_000));
        assertFalse(sampler.sampleIfDue(10_999));
        assertTrue(sampler.sampleIfDue(11_000));
        assertEquals(2, samples.pending());
    }

    @Test
    void theProbesLatestWindowIsReported() throws Exception {
        SchedulingLatencyProbe probe = new SchedulingLatencyProbe(1_000_000);
        probe.record(2_000_000);
        RuntimeSampler sampler = sampler(probe);
        sampler.sample(1_000);
        assertTrue(sent(0).get("data").get("scheduling").isNull(), "no window finished yet");
    }

    @Test
    void samplingAllocatesNothing() {
        RuntimeSampler sampler = sampler(null);
        ByteBuffer out = ByteBuffer.allocate(256 * 1024);
        long now = 0;
        // The clock status parser needs as long as ClockReadingsTest gives it before it stops allocating.
        for (int i = 0; i < 60_000; i++) {
            now += 10_000;
            sampler.sample(now);
            out.clear();
            samples.acknowledge(samples.writeTo(out, 64));
        }
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 2_000; i++) {
            now += 10_000;
            sampler.sample(now);
            out.clear();
            samples.acknowledge(samples.writeTo(out, 64));
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(allocated < 4_096, "allocated " + allocated + " bytes");
    }
}
