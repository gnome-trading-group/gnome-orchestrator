package group.gnometrading.aeron;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * The {@code service-endpoints} table (gnome-shared-infra, us-east-1): processes write their own item every few
 * seconds and sessions read the ones they connect to. An item expires {@link #TTL_SECONDS} after its last write, so a
 * process that died stops being offered. Not for a hot path: both calls allocate and do I/O.
 */
public final class ServiceEndpoints {

    public static final String TABLE = "service-endpoints";
    public static final String REGION = "us-east-1";
    /** Writers refresh every 15 s; four missed writes and the item goes. */
    public static final long TTL_SECONDS = 60;

    static final String KEY = "service_id";
    static final String KIND = "kind";
    static final String ID = "id";
    static final String INSTANCE = "instance";
    static final String SERVICE_REGION = "region";
    static final String PRIVATE_IP = "private_ip";
    static final String PORT = "port";
    static final String STATE = "state";
    static final String VERSION = "version";
    static final String WRITTEN_AT = "written_at_ns";
    static final String EXPIRES_AT = "expires_at";

    private final DynamoDbClient dynamo;
    private final String table;

    public ServiceEndpoints(final DynamoDbClient dynamo) {
        this(dynamo, TABLE);
    }

    ServiceEndpoints(final DynamoDbClient dynamo, final String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    public void put(final ServiceEndpoint endpoint) {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(KEY, AttributeValue.fromS(endpoint.serviceId()));
        item.put(KIND, AttributeValue.fromS(endpoint.kind().identifier()));
        item.put(ID, AttributeValue.fromS(endpoint.id()));
        item.put(INSTANCE, AttributeValue.fromS(endpoint.instance()));
        item.put(SERVICE_REGION, AttributeValue.fromS(endpoint.region()));
        item.put(PRIVATE_IP, AttributeValue.fromS(endpoint.privateIp()));
        item.put(PORT, AttributeValue.fromN(Integer.toString(endpoint.port())));
        item.put(STATE, AttributeValue.fromS(endpoint.state()));
        item.put(VERSION, AttributeValue.fromS(endpoint.version()));
        item.put(WRITTEN_AT, AttributeValue.fromN(Long.toString(endpoint.writtenAtNanos())));
        final long writtenAtSeconds = TimeUnit.NANOSECONDS.toSeconds(endpoint.writtenAtNanos());
        item.put(EXPIRES_AT, AttributeValue.fromN(Long.toString(writtenAtSeconds + TTL_SECONDS)));
        this.dynamo.putItem(
                PutItemRequest.builder().tableName(this.table).item(item).build());
    }

    /**
     * The process's endpoint, or null when it has none or its item has expired. DynamoDB removes expired items only
     * eventually, so expiry is checked here too, against {@code nowNanos}.
     */
    public ServiceEndpoint get(final ServiceEndpoint.Kind kind, final String id, final long nowNanos) {
        final Map<String, AttributeValue> item = this.dynamo
                .getItem(GetItemRequest.builder()
                        .tableName(this.table)
                        .key(Map.of(KEY, AttributeValue.fromS(ServiceEndpoint.serviceId(kind, id))))
                        .consistentRead(true)
                        .build())
                .item();
        if (item == null || item.isEmpty()) {
            return null;
        }
        if (Long.parseLong(item.get(EXPIRES_AT).n()) <= TimeUnit.NANOSECONDS.toSeconds(nowNanos)) {
            return null;
        }
        return new ServiceEndpoint(
                ServiceEndpoint.Kind.forIdentifier(item.get(KIND).s()),
                item.get(ID).s(),
                item.get(INSTANCE).s(),
                item.get(SERVICE_REGION).s(),
                item.get(PRIVATE_IP).s(),
                Integer.parseInt(item.get(PORT).n()),
                item.get(STATE).s(),
                item.get(VERSION).s(),
                Long.parseLong(item.get(WRITTEN_AT).n()));
    }
}
