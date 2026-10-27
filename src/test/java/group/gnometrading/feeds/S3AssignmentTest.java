package group.gnometrading.feeds;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

class S3AssignmentTest {

    private static Assignment.Streams parse(final String json) throws Exception {
        return S3Assignment.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsTheGenerationAndStreams() throws Exception {
        Assignment.Streams streams = parse("{\"generation\": 7, \"streams\": [10, 11, 12]}");
        assertEquals(7, streams.generation());
        assertArrayEquals(new int[] {10, 11, 12}, streams.streamIds());
    }

    @Test
    void refusesAFileItCantTrust() {
        // Read as no streams, it would stop everything the shard runs; refused, the shard keeps what it has.
        assertThrows(Exception.class, () -> parse("{\"streams\": [10]}"));
        assertThrows(Exception.class, () -> parse("{\"generation\": 1, \"streams\": \"10\"}"));
        assertThrows(Exception.class, () -> parse("{\"generation\": 1, \"streams\": [\"ten\"]}"));
        assertThrows(Exception.class, () -> parse("{\"generation\": 1, \"str"));
    }

    @Test
    void aMissingFileIsNoStreams() throws Exception {
        // Deleting the file stops every stream the shard runs: placement, not a missing file, decides what runs.
        S3Client s3 = new S3Client() {
            @Override
            public ResponseBytes<GetObjectResponse> getObjectAsBytes(final GetObjectRequest request) {
                assertEquals("shards/shard-1.json", request.key());
                throw NoSuchKeyException.builder().message("gone").build();
            }

            @Override
            public String serviceName() {
                return "s3";
            }

            @Override
            public void close() {}
        };

        Assignment.Streams streams = new S3Assignment(s3, "bucket", "shard-1").read();
        assertEquals(0, streams.generation());
        assertArrayEquals(new int[0], streams.streamIds());
    }
}
