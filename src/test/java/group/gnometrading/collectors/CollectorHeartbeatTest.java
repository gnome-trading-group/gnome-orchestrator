package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.host.HostReadings;
import group.gnometrading.logging.NullLogger;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CollectorHeartbeatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private final StreamTestKit kit = new StreamTestKit();
    private final MarketDataCollectorAgent agent =
            new MarketDataCollectorAgent(new NullLogger(), System::nanoTime, () -> 0L);
    private List<ShardStream> streams = List.of();
    private List<CollectorHeartbeat.Connection> connections = List.of();

    private final CollectorHeartbeat heartbeat = new CollectorHeartbeat(
            new NullLogger(),
            MAPPER,
            new CollectorHeartbeat.Shard() {
                @Override
                public List<ShardStream> streams() {
                    return streams;
                }

                @Override
                public List<CollectorHeartbeat.Connection> connections() {
                    return connections;
                }

                @Override
                public long generation() {
                    return 4;
                }

                @Override
                public long probes() {
                    return 12;
                }

                @Override
                public long probeMismatches() {
                    return 1;
                }
            },
            kit.uploader,
            HostReadings.ofThisProcess(new File("/")),
            null,
            0);

    private static List<JsonNode> parse(final List<String> lines) throws Exception {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : lines) {
            nodes.add(MAPPER.readTree(line));
        }
        return nodes;
    }

    @Test
    void reportsTheProcessThenEachConnectionThenEachStream() throws Exception {
        ShardStream stream = kit.stream(7);
        agent.add(stream);
        streams = List.of(stream);
        connections = List.of(new CollectorHeartbeat.Connection("KALSHI-0", "KALSHI", "md-1", 1, true));

        StreamTestKit.publish(stream, 30);
        agent.doWork();
        long wall = stream.lastEventNanos() + 2 * SECOND;
        List<JsonNode> lines = parse(heartbeat.lines(15 * SECOND, wall));

        assertEquals(3, lines.size());
        JsonNode process = lines.get(0);
        assertEquals("process", process.get("kind").asText());
        assertEquals(4, process.get("generation").asLong());
        assertEquals(1, process.get("streams").asInt());
        assertEquals(1, process.get("states").get("SUBSCRIBING").asInt(), "no market placed yet");
        assertEquals(1, process.get("connections").asInt());
        assertEquals(1, process.get("reconnecting").asInt());
        assertEquals(12, process.get("probes").asLong());
        assertTrue(process.get("heap_used_mb").asLong() > 0);
        assertTrue(process.get("sched_latency").isNull());

        JsonNode connection = lines.get(1);
        assertEquals("connection", connection.get("kind").asText());
        assertEquals("KALSHI-0", connection.get("name").asText());
        assertEquals("md-1", connection.get("account").asText(), "the market-data account it spends");
        assertEquals(1, connection.get("markets").asInt());

        JsonNode line = lines.get(2);
        assertEquals("stream", line.get("kind").asText());
        assertEquals(7, line.get("stream_id").asInt());
        assertEquals(2.0, line.get("last_event_age_s").asDouble());
        assertEquals(0.0, line.get("msgs_per_s").asDouble(), "no rate before a second sample");
        assertEquals(30, line.get("records").asLong());
        assertFalse(line.has("failure"));

        assertEquals(
                lines.get(0).toString(),
                MAPPER.readTree(heartbeat.processLine()).toString(),
                "kept for the status");
    }

    @Test
    void aStreamIsLoggedAgainOnlyWhenItsStateChanges() throws Exception {
        ShardStream stream = kit.stream(7);
        agent.add(stream);
        streams = List.of(stream);
        long wall = System.currentTimeMillis() * 1_000_000;

        assertEquals(2, heartbeat.lines(15 * SECOND, wall).size(), "the process and the new stream");
        assertEquals(1, heartbeat.lines(30 * SECOND, wall).size(), "unchanged: the process alone");
        assertEquals(
                1,
                MAPPER.readTree(heartbeat.lines(45 * SECOND, wall).get(0))
                        .get("states")
                        .get("SUBSCRIBING")
                        .asInt(),
                "still counted by state");
    }

    @Test
    void aStreamThatDroppedRecordsForSessionsIsLoggedWithWhy() throws Exception {
        ShardStream stream = kit.publishingStream(7);
        agent.add(stream);
        streams = List.of(stream);
        long wall = System.currentTimeMillis() * 1_000_000;
        heartbeat.lines(15 * SECOND, wall);

        // The publisher reads only after the ring of 64 has gone round three times.
        for (int i = 0; i < 200; i++) {
            StreamTestKit.publish(stream, 1);
            stream.poll(0);
        }
        stream.publish(0);
        List<JsonNode> lines = parse(heartbeat.lines(30 * SECOND, wall));

        assertEquals(2, lines.size(), "the process, and the drop; the stream's state didn't change");
        JsonNode drop = lines.get(1);
        assertEquals("publish", drop.get("kind").asText());
        assertEquals(7, drop.get("stream_id").asInt());
        assertEquals(stream.dropped(), drop.get("dropped").asLong());
        assertEquals(stream.dropped(), drop.get("lapped_total").asLong());
        assertEquals(0, drop.get("back_pressured_total").asLong());
        assertEquals(1, heartbeat.lines(45 * SECOND, wall).size(), "nothing dropped since: no line");
    }

    @Test
    void aFailedStreamShowsWhy() throws Exception {
        ShardStream stream = kit.stream(9, (sequence, templateId, buffer, length) -> {
            throw new IllegalStateException("bad record");
        });
        agent.add(stream);
        streams = List.of(stream);
        StreamTestKit.publish(stream, 1);
        agent.doWork();

        List<JsonNode> lines = parse(heartbeat.lines(15 * SECOND, System.currentTimeMillis() * 1_000_000));
        assertEquals(
                1, lines.get(0).get("states").get(CollectorHeartbeat.FAILED).asInt());
        assertEquals(CollectorHeartbeat.FAILED, lines.get(1).get("state").asText());
        assertEquals(
                "IllegalStateException: bad record", lines.get(1).get("failure").asText());
    }
}
