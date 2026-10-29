package group.gnometrading.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
            WRITTEN);

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
