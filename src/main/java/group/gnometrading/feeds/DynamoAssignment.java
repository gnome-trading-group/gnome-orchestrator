package group.gnometrading.feeds;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

/**
 * A shard's streams from its item in the reconciler's {@code collection-shards} table: {@code generation}, the
 * {@code streams} it collects, the {@code draining} ones it keeps collecting until a make-before-break hand-off removes
 * them, the {@code accounts} map of source to label, and the hand-off {@code cuts}. A shard with no item reads nothing.
 *
 * <p>{@code cuts} are packed big-endian int32 pairs (stream id, minute since the epoch). For a stream in {@code streams}
 * the minute is the first it writes ({@link #WARMING}: none yet); for one in {@code draining} it is the first it no
 * longer writes. So the shard taking a stream over writes from the cut minute and the one giving it up writes up to it,
 * and each minute is written once.
 *
 * <p>Read every few seconds, so each read asks only for the generation and fetches the rest when it changed. Both id
 * lists are packed big-endian int32s in one binary attribute: a list of numbers would come back as an SDK object per id.
 */
public final class DynamoAssignment implements Assignment {

    static final String KEY = "shard_id";
    static final String GENERATION = "generation";
    static final String STREAMS = "streams";
    static final String DRAINING = "draining";
    static final String ACCOUNTS = "accounts";
    static final String CUTS = "cuts";
    /** A cut minute meaning: collect, but write nothing until a cut is set. */
    public static final int WARMING = Integer.MAX_VALUE;

    private static final long MINUTE_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final Streams NONE = new Streams(0, new int[0]);

    private final DynamoDbClient dynamo;
    private final GetItemRequest generationRequest;
    private final GetItemRequest itemRequest;
    private Streams current = NONE;

    public DynamoAssignment(final DynamoDbClient dynamo, final String table, final String shard) {
        this.dynamo = dynamo;
        final Map<String, AttributeValue> key = Map.of(KEY, AttributeValue.fromS(shard));
        // Consistent reads: a hand-off waits on what the shard has applied, so it must not read an older item.
        this.generationRequest = GetItemRequest.builder()
                .tableName(table)
                .key(key)
                .projectionExpression(GENERATION)
                .consistentRead(true)
                .build();
        this.itemRequest = GetItemRequest.builder()
                .tableName(table)
                .key(key)
                .consistentRead(true)
                .build();
    }

    @Override
    public Streams read() {
        final Map<String, AttributeValue> head =
                dynamo.getItem(generationRequest).item();
        if (head == null || head.isEmpty()) {
            current = NONE;
            return current;
        }
        if (generationOf(head) == current.generation()) {
            return current;
        }
        current = parse(dynamo.getItem(itemRequest).item());
        return current;
    }

    static Streams parse(final Map<String, AttributeValue> item) {
        if (item == null || item.isEmpty()) {
            return NONE;
        }
        final int[] streams = ids(item.get(STREAMS));
        final int[] draining = ids(item.get(DRAINING));
        final int[] collected = new int[streams.length + draining.length];
        System.arraycopy(streams, 0, collected, 0, streams.length);
        System.arraycopy(draining, 0, collected, streams.length, draining.length);
        final Set<Integer> isDraining = new HashSet<>();
        for (int streamId : draining) {
            isDraining.add(streamId);
        }
        final Map<Integer, Long> writeFrom = new HashMap<>();
        final Map<Integer, Long> writeUntil = new HashMap<>();
        final int[] cuts = ids(item.get(CUTS));
        if (cuts.length % 2 != 0) {
            throw new IllegalArgumentException("Cuts are (stream id, minute) pairs; got " + cuts.length + " ints");
        }
        for (int i = 0; i < cuts.length; i += 2) {
            final long nanos = cuts[i + 1] == WARMING ? Long.MAX_VALUE : cuts[i + 1] * MINUTE_NANOS;
            (isDraining.contains(cuts[i]) ? writeUntil : writeFrom).put(cuts[i], nanos);
        }
        return new Streams(
                generationOf(item),
                collected,
                accounts(item.get(ACCOUNTS)),
                Map.copyOf(writeFrom),
                Map.copyOf(writeUntil));
    }

    private static long generationOf(final Map<String, AttributeValue> item) {
        final AttributeValue generation = item.get(GENERATION);
        if (generation == null || generation.n() == null) {
            throw new IllegalArgumentException("A shard item needs a numeric generation");
        }
        return Long.parseLong(generation.n());
    }

    private static int[] ids(final AttributeValue value) {
        if (value == null || value.b() == null) {
            return new int[0];
        }
        final ByteBuffer bytes = value.b().asByteBuffer();
        if (bytes.remaining() % Integer.BYTES != 0) {
            throw new IllegalArgumentException("Stream ids are packed int32s; got " + bytes.remaining() + " bytes");
        }
        final int[] ids = new int[bytes.remaining() / Integer.BYTES];
        bytes.asIntBuffer().get(ids);
        return ids;
    }

    private static Map<String, String> accounts(final AttributeValue value) {
        if (value == null || !value.hasM()) {
            return Map.of();
        }
        final Map<String, String> accounts = new HashMap<>();
        value.m().forEach((source, label) -> accounts.put(source, label.s()));
        return Map.copyOf(accounts);
    }

    /** Packs stream ids as the item stores them; the reconciler and tests write them this way. */
    public static SdkBytes pack(final int... ids) {
        final ByteBuffer bytes = ByteBuffer.allocate(ids.length * Integer.BYTES);
        bytes.asIntBuffer().put(ids);
        return SdkBytes.fromByteBuffer(bytes);
    }
}
