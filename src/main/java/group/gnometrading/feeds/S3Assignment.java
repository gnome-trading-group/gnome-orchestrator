package group.gnometrading.feeds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * A shard's streams from {@code s3://<bucket>/shards/<shard>.json}: {@code {"generation": n, "streams": [ids]}}. A
 * shard with no file reads nothing. Placement writes the file; Phase 6 moves it to DynamoDB.
 */
public final class S3Assignment implements Assignment {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Streams NONE = new Streams(0, new int[0]);

    private final S3Client s3;
    private final String bucket;
    private final String key;

    public S3Assignment(final S3Client s3, final String bucket, final String shard) {
        this.s3 = s3;
        this.bucket = bucket;
        this.key = "shards/" + shard + ".json";
    }

    @Override
    public Streams read() throws Exception {
        final byte[] body;
        try {
            body = s3.getObjectAsBytes(request -> request.bucket(bucket).key(key))
                    .asByteArray();
        } catch (NoSuchKeyException e) {
            return NONE;
        }
        return parse(body);
    }

    static Streams parse(final byte[] body) throws Exception {
        final JsonNode root = MAPPER.readTree(body);
        final JsonNode generation = root.get("generation");
        final JsonNode streams = root.get("streams");
        if (generation == null || !generation.canConvertToLong() || streams == null || !streams.isArray()) {
            throw new IllegalArgumentException("An assignment needs a generation and a list of streams");
        }
        final int[] ids = new int[streams.size()];
        for (int i = 0; i < ids.length; i++) {
            if (!streams.get(i).canConvertToInt()) {
                throw new IllegalArgumentException("Stream ids are integers: " + streams.get(i));
            }
            ids[i] = streams.get(i).intValue();
        }
        return new Streams(generation.longValue(), ids);
    }
}
