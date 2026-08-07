package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.luben.zstd.ZstdInputStream;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sm.AssetClass;
import group.gnometrading.sm.ContractType;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.SecurityType;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class RawMarketDataCollectorTest {

    private static final Listing LISTING = new Listing(
            532,
            new Exchange(151, "Polymarket", "global", SchemaType.MBP_10),
            new Security(
                    499,
                    "TEST",
                    SecurityType.EVENT_CONTRACT,
                    ContractType.BINARY,
                    AssetClass.PREDICTION,
                    "",
                    "USD",
                    "USD",
                    false,
                    false,
                    0L,
                    0L,
                    true,
                    0),
            "condition:token",
            "TEST-YES");

    private S3Client s3Client;
    private List<StoredObject> objects;

    @BeforeEach
    void setUp() {
        this.s3Client = mock(S3Client.class);
        this.objects = new CopyOnWriteArrayList<>();
        when(this.s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    final PutObjectRequest request = invocation.getArgument(0);
                    final RequestBody body = invocation.getArgument(1);
                    final byte[] data = body.contentStreamProvider().newStream().readAllBytes();
                    this.objects.add(new StoredObject(request, data));
                    return PutObjectResponse.builder().build();
                });
    }

    @Test
    void storesLosslessFramedMessagesAndManifest() throws Exception {
        final RawMarketDataCollector collector = collector();
        final long firstTimestamp = nanos("2026-08-03T12:00:10Z");
        final long secondTimestamp = nanos("2026-08-03T12:01:05Z");
        final byte[] firstMessage = "{\"event_type\":\"book\"}".getBytes(StandardCharsets.UTF_8);
        final byte[] secondMessage = "PONG".getBytes(StandardCharsets.UTF_8);

        collector.onMessage(ByteBuffer.wrap(firstMessage), 0, firstMessage.length, firstTimestamp);
        collector.onMessage(ByteBuffer.wrap(secondMessage), 0, secondMessage.length, secondTimestamp);
        collector.close();

        assertEquals(4, this.objects.size());
        final StoredObject firstData = this.objects.stream()
                .filter(object -> object.request.key().endsWith(".raw.zst"))
                .findFirst()
                .orElseThrow();
        assertTrue(firstData.request.key().contains("exchange=151/security=499/listing=532/2026/08/03/12/00"));

        try (var input = new DataInputStream(new ZstdInputStream(new ByteArrayInputStream(firstData.data)))) {
            final byte[] magic = input.readNBytes(RawMarketDataCollector.MAGIC.length);
            assertArrayEquals(RawMarketDataCollector.MAGIC, magic);
            assertEquals(RawMarketDataCollector.FORMAT_VERSION, input.readInt());
            assertEquals(LISTING.listingId(), input.readInt());
            assertEquals(firstTimestamp, input.readLong());
            final int messageLength = input.readInt();
            assertArrayEquals(firstMessage, input.readNBytes(messageLength));
            assertEquals(-1, input.read());
        }

        final String manifest = this.objects.stream()
                .filter(object -> object.request.key().endsWith(".manifest.json"))
                .map(object -> new String(object.data, StandardCharsets.UTF_8))
                .findFirst()
                .orElseThrow();
        assertTrue(manifest.contains("\"format\":\"gnome-raw-v1\""));
        assertTrue(manifest.contains("\"messageCount\":1"));
        assertTrue(manifest.contains("\"sha256\":"));
        assertTrue(manifest.contains("\"collectionId\":\"untracked\""));
    }

    @Test
    void reportsUploadFailuresAsUnhealthy() throws Exception {
        when(this.s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("storage unavailable"));
        final RawMarketDataCollector collector = collector();
        final ByteBuffer message = ByteBuffer.wrap("PONG".getBytes(StandardCharsets.UTF_8));
        collector.onMessage(message, message.position(), message.remaining(), nanos("2026-08-03T12:00:10Z"));

        assertThrows(IOException.class, collector::close);
        assertFalse(collector.isHealthy());
    }

    @Test
    void copiesOnlyExplicitBoundsWithoutChangingSourcePosition() throws Exception {
        final RawMarketDataCollector collector = collector();
        final byte[] wrapped = "xxPONGyy".getBytes(StandardCharsets.UTF_8);
        final ByteBuffer source = ByteBuffer.wrap(wrapped);
        source.position(1);
        source.limit(7);

        collector.onMessage(source, 2, 4, nanos("2026-08-03T12:00:10Z"));
        assertEquals(1, source.position());
        assertEquals(7, source.limit());
        collector.close();

        final StoredObject data = this.objects.stream()
                .filter(object -> object.request.key().endsWith(".raw.zst"))
                .findFirst()
                .orElseThrow();
        try (var input = new DataInputStream(new ZstdInputStream(new ByteArrayInputStream(data.data)))) {
            input.skipNBytes(RawMarketDataCollector.MAGIC.length + Integer.BYTES * 2L + Long.BYTES);
            assertEquals(4, input.readInt());
            assertArrayEquals("PONG".getBytes(StandardCharsets.UTF_8), input.readNBytes(4));
        }
    }

    @Test
    void oversizedFrameFailsClosed() {
        final RawMarketDataCollector collector = new RawMarketDataCollector(
                new NullLogger(),
                this.s3Client,
                LISTING,
                "venue-raw-test",
                CollectionContext.untracked(),
                Executors.newSingleThreadExecutor(),
                false,
                2,
                4);
        final ByteBuffer message = ByteBuffer.wrap(new byte[5]);

        assertThrows(
                IOException.class, () -> collector.onMessage(message, message.position(), message.remaining(), 1L));
        assertFalse(collector.isHealthy());
        assertEquals(1L, collector.oversizedFrameCount());
        assertEquals(0L, collector.invalidBoundsCount());
        assertEquals(0L, collector.ringSaturationCount());
        assertThrows(IOException.class, collector::close);
    }

    @Test
    void saturatedRingFailsClosedWithoutOverwritingClaimedFrame() throws Exception {
        final RawMarketDataCollector collector = new RawMarketDataCollector(
                new NullLogger(),
                this.s3Client,
                LISTING,
                "venue-raw-test",
                CollectionContext.untracked(),
                Executors.newSingleThreadExecutor(),
                false,
                1,
                4,
                false);
        final ByteBuffer first = ByteBuffer.wrap(new byte[] {1});
        final ByteBuffer second = ByteBuffer.wrap(new byte[] {2});

        collector.onMessage(first, 0, 1, 1L);
        assertThrows(IOException.class, () -> collector.onMessage(second, 0, 1, 2L));

        assertEquals(1L, collector.lastMessageNanos());
        assertEquals(1L, collector.ringSaturationCount());
        assertFalse(collector.isHealthy());
        assertThrows(IOException.class, collector::close);
    }

    @Test
    void successfulIngressAllocatesNoBytesOnProducerThread() throws Exception {
        final RawMarketDataCollector collector = collector();
        final ByteBuffer message = ByteBuffer.wrap("PONG".getBytes(StandardCharsets.UTF_8));
        final java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof com.sun.management.ThreadMXBean);
        final com.sun.management.ThreadMXBean allocationBean = (com.sun.management.ThreadMXBean) platformBean;
        assumeTrue(allocationBean.isThreadAllocatedMemorySupported());
        allocationBean.setThreadAllocatedMemoryEnabled(true);
        final long threadId = Thread.currentThread().getId();

        for (int i = 0; i < 32; i++) {
            collector.onMessage(message, 0, message.remaining(), 1_000L + i);
        }
        final long before = allocationBean.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < 32; i++) {
            collector.onMessage(message, 0, message.remaining(), 2_000L + i);
        }
        final long after = allocationBean.getThreadAllocatedBytes(threadId);

        assertEquals(0L, after - before);
        collector.close();
    }

    private RawMarketDataCollector collector() {
        return new RawMarketDataCollector(
                new NullLogger(), this.s3Client, LISTING, "venue-raw-test", Executors.newSingleThreadExecutor(), false);
    }

    private static long nanos(String instant) {
        final Instant parsed = Instant.parse(instant);
        return parsed.getEpochSecond() * 1_000_000_000L + parsed.getNano();
    }

    private record StoredObject(PutObjectRequest request, byte[] data) {}
}
