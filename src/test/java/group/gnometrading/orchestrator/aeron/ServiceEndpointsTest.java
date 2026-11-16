package group.gnometrading.orchestrator.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

class ServiceEndpointsTest {

    private static final long WRITTEN = TimeUnit.SECONDS.toNanos(1_793_000_000L);

    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    private final AtomicReference<Map<String, AttributeValue>> stored = new AtomicReference<>();
    private final ServiceEndpoints endpoints = new ServiceEndpoints(this.dynamo);

    private final ServiceEndpoint shard = new ServiceEndpoint(
            ServiceEndpoint.Kind.SHARD,
            "use1-e89064",
            "task-1",
            "us-east-1",
            "10.144.1.7",
            AeronPorts.SHARD_CONTROL,
            "ACTIVE",
            "1.24.0",
            WRITTEN,
            0,
            0);

    ServiceEndpointsTest() {
        when(this.dynamo.putItem(any(PutItemRequest.class))).thenAnswer(call -> {
            final PutItemRequest request = call.getArgument(0);
            this.stored.set(request.item());
            return PutItemResponse.builder().build();
        });
        when(this.dynamo.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            final GetItemRequest request = call.getArgument(0);
            final Map<String, AttributeValue> item = this.stored.get();
            final boolean match = item != null
                    && item.get(ServiceEndpoints.KEY).equals(request.key().get(ServiceEndpoints.KEY));
            return GetItemResponse.builder().item(match ? item : Map.of()).build();
        });
    }

    @Test
    void anEndpointReadsBackAsItWasWritten() {
        this.endpoints.put(this.shard);

        assertEquals(
                "shard#use1-e89064", this.stored.get().get(ServiceEndpoints.KEY).s());
        assertEquals(this.shard, this.endpoints.get(ServiceEndpoint.Kind.SHARD, "use1-e89064", WRITTEN));
    }

    @Test
    void aGatewaysEndpointSaysWhetherItCanHandOverAndWhetherItIsHandingOver() {
        final ServiceEndpoint gateway = new ServiceEndpoint(
                ServiceEndpoint.Kind.ORDER_GATEWAY,
                "live:kalshi:main",
                "task-2",
                "us-east-1",
                "10.144.1.8",
                40010,
                "STOPPING",
                "2.2.0",
                WRITTEN,
                3,
                12,
                true,
                true);
        this.endpoints.put(gateway);

        assertTrue(this.stored.get().get(ServiceEndpoints.CAN_HAND_OVER).bool());
        assertTrue(this.stored.get().get(ServiceEndpoints.HANDOVER).bool());
        assertEquals(gateway, this.endpoints.get(ServiceEndpoint.Kind.ORDER_GATEWAY, "live:kalshi:main", WRITTEN));

        this.endpoints.put(this.shard);
        assertFalse(this.stored.get().containsKey(ServiceEndpoints.CAN_HAND_OVER), "a shard has no say in rolls");
    }

    @Test
    void aPaperGatewaysEndpointCarriesItsLoadAgainstItsCapsAndWhenItsProcessStarted() {
        final ServiceEndpoint paper = new ServiceEndpoint(
                ServiceEndpoint.Kind.ORDER_GATEWAY,
                "paper:kalshi:2",
                "task-3",
                "us-east-2",
                "10.144.1.9",
                40010,
                "READY",
                "2.3.0",
                WRITTEN,
                40,
                7,
                false,
                false,
                1_793_000_000_123L,
                new ServiceEndpoint.PaperLoad(410, 96, 1_850, 800, 150));
        this.endpoints.put(paper);

        assertEquals("410", this.stored.get().get(ServiceEndpoints.PAPER_BOOKS).n());
        assertEquals(
                "150", this.stored.get().get(ServiceEndpoints.PAPER_MAX_STREAMS).n());
        assertEquals(paper, this.endpoints.get(ServiceEndpoint.Kind.ORDER_GATEWAY, "paper:kalshi:2", WRITTEN));
    }

    @Test
    void anEndpointWithoutALoadOrStartReadsBackWithout() {
        this.endpoints.put(this.shard);

        assertFalse(this.stored.get().containsKey(ServiceEndpoints.PAPER_BOOKS));
        assertFalse(this.stored.get().containsKey(ServiceEndpoints.STARTED_AT_MS));
        final ServiceEndpoint read = this.endpoints.get(ServiceEndpoint.Kind.SHARD, "use1-e89064", WRITTEN);
        assertEquals(0, read.startedAtMs());
        assertEquals(null, read.paper());
    }

    @Test
    void aGatewaysRollItemIsReadByItsOwnKeyOrIsEmpty() {
        assertEquals(Map.of(), this.endpoints.roll("live:kalshi:main", Duration.ofSeconds(2)));
        final Map<String, AttributeValue> roll = Map.of(
                ServiceEndpoints.KEY,
                AttributeValue.fromS("order-gateway#live:kalshi:main#roll"),
                "phase",
                AttributeValue.fromS("stopping"));
        this.stored.set(roll);

        assertEquals(roll, this.endpoints.roll("live:kalshi:main", Duration.ofSeconds(2)));
    }

    @Test
    void aGatewaysMinutesGoInTheirOwnItemBesideItsEndpointAndOutliveAStopByTenMinutes() {
        this.endpoints.putMinutes("live:kalshi:main", "{\"accounts\":[]}", WRITTEN);

        final Map<String, AttributeValue> item = this.stored.get();
        assertEquals(
                "order-gateway#live:kalshi:main#minutes",
                item.get(ServiceEndpoints.KEY).s());
        assertEquals("{\"accounts\":[]}", item.get(ServiceEndpoints.MINUTES).s());
        assertEquals(
                Long.toString(TimeUnit.NANOSECONDS.toSeconds(WRITTEN) + ServiceEndpoints.MINUTES_TTL_SECONDS),
                item.get(ServiceEndpoints.EXPIRES_AT).n());
    }

    @Test
    void anItemExpiresAMinuteAfterItsLastWrite() {
        this.endpoints.put(this.shard);

        final long expiresAt = TimeUnit.NANOSECONDS.toSeconds(WRITTEN) + ServiceEndpoints.TTL_SECONDS;
        assertEquals(
                Long.toString(expiresAt),
                this.stored.get().get(ServiceEndpoints.EXPIRES_AT).n());
        final long justBefore = TimeUnit.SECONDS.toNanos(expiresAt) - 1;
        assertEquals(this.shard, this.endpoints.get(ServiceEndpoint.Kind.SHARD, "use1-e89064", justBefore));
        assertNull(this.endpoints.get(ServiceEndpoint.Kind.SHARD, "use1-e89064", TimeUnit.SECONDS.toNanos(expiresAt)));
    }

    @Test
    void anUnknownProcessHasNoEndpoint() {
        this.endpoints.put(this.shard);

        assertNull(this.endpoints.get(ServiceEndpoint.Kind.ORDER_GATEWAY, "use1-e89064", WRITTEN));
    }
}
