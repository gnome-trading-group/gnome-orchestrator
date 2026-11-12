package group.gnometrading.orchestrator.shard;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.luben.zstd.ZstdInputStream;
import group.gnometrading.logging.NullLogger;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.function.Consumer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ExtendWith(MockitoExtension.class)
class CycleBufferTest {

    private static final String BUCKET = "test-bucket";
    private static final String KEY = "test/key.zst";
    private static final LocalDateTime TIMESTAMP = LocalDateTime.of(2025, 4, 1, 12, 0);

    @Mock
    S3Client s3Client;

    private final DirectBufferPool pool = new DirectBufferPool();

    static void write(CycleBuffer buffer, byte[] data) throws IOException {
        buffer.write(new UnsafeBuffer(data), 0, data.length);
    }

    private byte[] uploadAndDecompress(CycleBuffer buffer) throws IOException {
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        when(s3Client.putObject(any(Consumer.class), body.capture()))
                .thenReturn(PutObjectResponse.builder().build());
        buffer.upload(s3Client, BUCKET, new NullLogger());
        byte[] compressed = body.getValue().contentStreamProvider().newStream().readAllBytes();
        try (ZstdInputStream zstd = new ZstdInputStream(new ByteArrayInputStream(compressed))) {
            return zstd.readAllBytes();
        }
    }

    @Test
    void testUploadCallsS3WithCorrectKeyAndBucket() throws IOException {
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        CycleBuffer buffer = new CycleBuffer(pool, TIMESTAMP, KEY);
        write(buffer, "data".getBytes(StandardCharsets.UTF_8));
        buffer.upload(s3Client, BUCKET, new NullLogger());

        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> requestCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest.Builder builder = PutObjectRequest.builder();
        requestCaptor.getValue().accept(builder);
        assertEquals(KEY, builder.build().key());
        assertEquals(BUCKET, builder.build().bucket());
    }

    @Test
    void testUploadCompressesDataCorrectly() throws IOException {
        CycleBuffer buffer = new CycleBuffer(pool, TIMESTAMP, KEY);
        write(buffer, "hello world".getBytes(StandardCharsets.UTF_8));

        assertEquals("hello world", new String(uploadAndDecompress(buffer), StandardCharsets.UTF_8));
    }

    @Test
    void aMinuteLargerThanEveryBufferRoundTrips() throws IOException {
        // Incompressible and several times the staging, target and initial output sizes, so every one of them fills.
        byte[] data = new byte[1 << 20];
        new Random(7).nextBytes(data);
        CycleBuffer buffer = new CycleBuffer(pool, TIMESTAMP, KEY);
        for (int offset = 0; offset < data.length; offset += 1000) {
            buffer.write(new UnsafeBuffer(data), offset, Math.min(1000, data.length - offset));
        }

        assertArrayEquals(data, uploadAndDecompress(buffer));
    }

    @Test
    void writingFromAByteBufferLeavesItsPositionAlone() throws IOException {
        ByteBuffer source = ByteBuffer.allocateDirect(16);
        source.put("0123456789abcdef".getBytes(StandardCharsets.UTF_8)).flip().position(4);
        CycleBuffer buffer = new CycleBuffer(pool, TIMESTAMP, KEY);

        buffer.write(source, source.position(), 6);

        assertEquals(4, source.position());
        assertEquals("456789", new String(uploadAndDecompress(buffer), StandardCharsets.UTF_8));
    }

    @Test
    void anEmptyMinuteUploadsAValidEmptyFile() throws IOException {
        assertEquals(0, uploadAndDecompress(new CycleBuffer(pool, TIMESTAMP, KEY)).length);
    }

    @Test
    void releasedBuffersAreReused() {
        ByteBuffer first = pool.acquire(5000);
        pool.release(first);
        assertSame(first, pool.acquire(4097));
    }

    @Test
    void testTimestampReturned() {
        assertEquals(TIMESTAMP, new CycleBuffer(pool, TIMESTAMP, KEY).timestamp());
    }

    @Test
    void uploadsOnlyIfNoOneWroteTheMinuteAndTakesA412AsDone() throws IOException {
        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> requestCaptor = ArgumentCaptor.forClass(Consumer.class);
        when(s3Client.putObject(requestCaptor.capture(), any(RequestBody.class)))
                .thenThrow(S3Exception.builder()
                        .statusCode(412)
                        .message("PreconditionFailed")
                        .build());

        CycleBuffer buffer = new CycleBuffer(pool, TIMESTAMP, KEY);
        write(buffer, "data".getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> buffer.upload(s3Client, BUCKET, new NullLogger()));

        PutObjectRequest.Builder builder = PutObjectRequest.builder();
        requestCaptor.getValue().accept(builder);
        assertEquals("*", builder.build().ifNoneMatch());
    }

    @Test
    void anyOtherS3ErrorFailsTheUploadSoItIsRetried() {
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder()
                        .statusCode(503)
                        .message("SlowDown")
                        .build());

        CycleBuffer buffer = new CycleBuffer(pool, TIMESTAMP, KEY);

        assertThrows(S3Exception.class, () -> {
            write(buffer, "data".getBytes(StandardCharsets.UTF_8));
            buffer.upload(s3Client, BUCKET, new NullLogger());
        });
    }
}
