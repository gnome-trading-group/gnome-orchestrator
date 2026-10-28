package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.gateways.feed.SubjectState;
import group.gnometrading.logging.NullLogger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShardStatusWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StreamTestKit kit = new StreamTestKit();
    private final MarketDataCollectorAgent agent =
            new MarketDataCollectorAgent(new NullLogger(), System::nanoTime, () -> 0L);
    private final StreamTransitions transitions = new StreamTransitions();
    private List<ShardStream> streams = List.of();
    private List<CollectorHeartbeat.Connection> connections = List.of();
    private String processLine;

    private final ShardStatusWriter writer = new ShardStatusWriter(
            new NullLogger(),
            null,
            "gnome-market-data-metadata-dev",
            new ShardStatusWriter.Identity("use1-r-1", "task-9", "10.144.0.17", "1.23.0", 5),
            new ShardStatusWriter.Shard() {
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
                    return 7;
                }

                @Override
                public String processLine() {
                    return processLine;
                }
            },
            transitions,
            () -> 99);

    private JsonNode status() throws Exception {
        int length = writer.build();
        return MAPPER.readTree(new String(writer.bytes(), 0, length, StandardCharsets.UTF_8));
    }

    @Test
    void namesTheTaskAndHostAndRepeatsTheProcessLine() throws Exception {
        processLine = "{\"kind\":\"process\",\"streams\":0}";

        JsonNode status = status();

        assertEquals("use1-r-1", status.get("shard_id").asText());
        assertEquals("task-9", status.get("task_id").asText());
        assertEquals("10.144.0.17", status.get("private_ip").asText());
        assertEquals("1.23.0", status.get("version").asText());
        assertEquals(5, status.get("started_at_ns").asLong());
        assertEquals(99, status.get("written_at_ns").asLong());
        assertEquals(7, status.get("applied_generation").asLong());
        assertEquals("process", status.get("process").get("kind").asText());
        assertEquals("collection/shards/use1-r-1/status/task-9.json", ShardStatusWriter.keyFor("use1-r-1", "task-9"));
    }

    @Test
    void reportsEveryStreamAndConnection() throws Exception {
        ShardStream stream = kit.stream(12);
        agent.add(stream);
        streams = List.of(stream);
        StreamTestKit.publish(stream, 3);
        agent.doWork();
        connections = List.of(
                new CollectorHeartbeat.Connection("KALSHI-0", "KALSHI", "md-1", 1, false),
                new CollectorHeartbeat.Connection("LIGHTER-0", "LIGHTER", null, 4, true));

        JsonNode status = status();

        assertTrue(status.get("process").isNull(), "before the first heartbeat");
        JsonNode line = status.get("streams").get(0);
        assertEquals(12, line.get("id").asInt());
        assertEquals("SUBSCRIBING", line.get("state").asText());
        assertEquals(3, line.get("records").asLong());
        assertEquals(stream.lastEventNanos(), line.get("last_event_ns").asLong());
        assertFalse(line.has("failure"));
        assertEquals("md-1", status.get("connections").get(0).get("account").asText());
        assertTrue(status.get("connections").get(1).get("account").isNull(), "a public feed");
        assertTrue(status.get("connections").get(1).get("reconnecting").asBoolean());
    }

    @Test
    void aFailureIsEscaped() throws Exception {
        ShardStream stream = kit.stream(13, (sequence, templateId, buffer, length) -> {
            throw new IllegalStateException("bad \"record\" 32°C\n");
        });
        agent.add(stream);
        streams = List.of(stream);
        StreamTestKit.publish(stream, 1);
        agent.doWork();

        JsonNode line = status().get("streams").get(0);
        assertEquals("FAILED", line.get("state").asText());
        assertEquals(
                "IllegalStateException: bad \"record\" 32°C\n",
                line.get("failure").asText());
    }

    @Test
    void repeatsTheLatestTransitionsOldestFirst() throws Exception {
        byte live = StreamTransitions.code(SubjectState.LIVE);
        byte stale = StreamTransitions.code(SubjectState.STALE);
        for (int i = 0; i < StreamTransitions.CAPACITY + 2; i++) {
            transitions.record(i, live, stale, 1_000 + i);
        }

        JsonNode status = status();

        assertEquals(
                StreamTransitions.CAPACITY + 2,
                status.get("transitions_recorded").asLong());
        JsonNode kept = status.get("transitions");
        assertEquals(StreamTransitions.CAPACITY, kept.size(), "the oldest two dropped");
        assertEquals(2, kept.get(0).get(0).asInt());
        assertEquals("LIVE", kept.get(0).get(1).asText());
        assertEquals("STALE", kept.get(0).get(2).asText());
        assertEquals(1_002, kept.get(0).get(3).asLong());
        assertEquals(
                StreamTransitions.CAPACITY + 1,
                kept.get(StreamTransitions.CAPACITY - 1).get(0).asInt());
    }

    @Test
    void theTaskIdIsTheLastPartOfItsArn() throws Exception {
        assertEquals(
                "4f2c9e",
                ShardIdentity.taskIdOf(
                        MAPPER.readTree(
                                "{\"TaskARN\":\"arn:aws:ecs:us-east-1:443370708724:task/CollectionCluster-us-east-1/4f2c9e\"}")));
    }

    @Test
    void aStatusBiggerThanTheBufferGrowsItAndIsWrittenWhole() throws Exception {
        processLine = "{\"padding\":\"" + "x".repeat(100_000) + "\"}";

        JsonNode status = status();

        assertEquals(100_000, status.get("process").get("padding").asText().length());
        assertEquals(7, status.get("applied_generation").asLong(), "everything after it too");
    }
}
