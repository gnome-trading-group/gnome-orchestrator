package group.gnometrading.feeds;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

class DynamoAssignmentTest {

    private Map<String, AttributeValue> item;
    private final List<GetItemRequest> requests = new ArrayList<>();

    private final DynamoDbClient dynamo = new DynamoDbClient() {
        @Override
        public GetItemResponse getItem(final GetItemRequest request) {
            requests.add(request);
            if (item == null) {
                return GetItemResponse.builder().build();
            }
            Map<String, AttributeValue> returned = item;
            if (request.projectionExpression() != null) {
                returned = Map.of(DynamoAssignment.GENERATION, item.get(DynamoAssignment.GENERATION));
            }
            return GetItemResponse.builder().item(returned).build();
        }

        @Override
        public String serviceName() {
            return "dynamodb";
        }

        @Override
        public void close() {}
    };

    private final DynamoAssignment assignment = new DynamoAssignment(dynamo, "collection-shards", "use1-r-1");

    private static Map<String, AttributeValue> item(
            final long generation, final int[] streams, final int[] draining, final Map<String, String> accounts) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(DynamoAssignment.KEY, AttributeValue.fromS("use1-r-1"));
        item.put(DynamoAssignment.GENERATION, AttributeValue.fromN(Long.toString(generation)));
        item.put(DynamoAssignment.STREAMS, AttributeValue.fromB(DynamoAssignment.pack(streams)));
        if (draining != null) {
            item.put(DynamoAssignment.DRAINING, AttributeValue.fromB(DynamoAssignment.pack(draining)));
        }
        Map<String, AttributeValue> labels = new HashMap<>();
        accounts.forEach((source, label) -> labels.put(source, AttributeValue.fromS(label)));
        item.put(DynamoAssignment.ACCOUNTS, AttributeValue.fromM(labels));
        return item;
    }

    @Test
    void readsStreamsDrainingAndAccountsFromTheShardsItem() {
        item = item(3, new int[] {10, 11}, new int[] {12}, Map.of("KALSHI", "md-2"));

        Assignment.Streams streams = assignment.read();

        assertEquals(3, streams.generation());
        assertArrayEquals(new int[] {10, 11, 12}, streams.streamIds(), "draining streams are still collected");
        assertEquals(Map.of("KALSHI", "md-2"), streams.accounts());
        GetItemRequest first = requests.get(0);
        assertEquals("collection-shards", first.tableName());
        assertEquals("use1-r-1", first.key().get(DynamoAssignment.KEY).s());
        assertEquals(Boolean.TRUE, first.consistentRead());
    }

    @Test
    void anUnchangedGenerationReadsOnlyTheGeneration() {
        item = item(3, new int[] {10}, null, Map.of());
        Assignment.Streams first = assignment.read();
        requests.clear();

        assertSame(first, assignment.read());
        assertEquals(1, requests.size(), "just the generation");
        assertEquals(DynamoAssignment.GENERATION, requests.get(0).projectionExpression());

        item = item(4, new int[] {10, 11}, null, Map.of());
        requests.clear();
        assertArrayEquals(new int[] {10, 11}, assignment.read().streamIds());
        assertEquals(2, requests.size(), "then the whole item once it changed");
    }

    @Test
    void readsTheStreamsSessionsLeaseForPublishing() {
        item = item(3, new int[] {10, 11}, new int[] {12}, Map.of());
        item.put(DynamoAssignment.PUBLISH, AttributeValue.fromB(DynamoAssignment.pack(11, 12)));

        assertArrayEquals(new int[] {11, 12}, assignment.read().publish());
    }

    @Test
    void anItemWithoutPublishPublishesNothing() {
        item = item(3, new int[] {10}, null, Map.of());

        assertEquals(0, assignment.read().publish().length);
    }

    @Test
    void noItemReadsAsNothingAssigned() {
        Assignment.Streams streams = assignment.read();

        assertEquals(0, streams.generation());
        assertEquals(0, streams.streamIds().length);
        assertEquals(Map.of(), streams.accounts());
    }

    @Test
    void streamIdsArePackedBigEndianInt32s() {
        SdkBytes packed = DynamoAssignment.pack(1, 256, Integer.MAX_VALUE);
        assertArrayEquals(
                new byte[] {0, 0, 0, 1, 0, 0, 1, 0, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff}, packed.asByteArray());

        Map<String, AttributeValue> torn = item(1, new int[] {1}, null, Map.of());
        torn.put(DynamoAssignment.STREAMS, AttributeValue.fromB(SdkBytes.fromByteArray(new byte[] {0, 0, 1})));
        assertThrows(IllegalArgumentException.class, () -> DynamoAssignment.parse(torn));
    }

    @Test
    void cutsBoundTheTakingShardFromAndTheGivingShardUntilTheCutMinute() {
        int cutMinute = 29_000_000;
        item = item(5, new int[] {10, 11}, new int[] {12}, Map.of());
        item.put(
                DynamoAssignment.CUTS,
                AttributeValue.fromB(
                        DynamoAssignment.pack(10, cutMinute, 11, DynamoAssignment.WARMING, 12, cutMinute)));

        Assignment.Streams streams = assignment.read();

        long cutNanos = TimeUnit.MINUTES.toNanos(cutMinute);
        assertEquals(cutNanos, streams.writeFromNanos(10), "taking over: writes from the cut");
        assertEquals(Long.MAX_VALUE, streams.writeFromNanos(11), "warming: writes nothing yet");
        assertEquals(Long.MAX_VALUE, streams.writeUntilNanos(10));
        assertEquals(cutNanos, streams.writeUntilNanos(12), "giving up: writes up to the cut");
        assertEquals(Long.MIN_VALUE, streams.writeFromNanos(12));
        assertEquals(Long.MIN_VALUE, streams.writeFromNanos(99), "no cut: every minute");
    }
}
