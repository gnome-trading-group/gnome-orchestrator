package group.gnometrading.collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.collector.CycleUploader;
import group.gnometrading.collector.DirectBufferPool;
import group.gnometrading.collector.MarketDataCollector;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.RecordType;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedEventHandler;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/** Real rings, collectors and uploader, with S3 recorded instead of called. */
final class StreamTestKit {

    static final String MARKET_BUCKET = "market";

    final Clock clock = Clock.fixed(Instant.parse("2026-10-26T12:00:30Z"), ZoneOffset.UTC);
    final S3Client s3Client = mock(S3Client.class);
    final List<String> uploadedBuckets = new ArrayList<>();
    final CycleUploader uploader;
    final DirectBufferPool pool = new DirectBufferPool();

    @SuppressWarnings("unchecked")
    StreamTestKit() {
        when(s3Client.putObject(any(Consumer.class), any(RequestBody.class))).thenAnswer(invocation -> {
            final PutObjectRequest.Builder request = PutObjectRequest.builder();
            ((Consumer<PutObjectRequest.Builder>) invocation.getArgument(0)).accept(request);
            synchronized (uploadedBuckets) {
                uploadedBuckets.add(request.build().bucket());
            }
            return PutObjectResponse.builder().build();
        });
        uploader = new CycleUploader(new NullLogger(), s3Client);
    }

    /** A stream recording into the market bucket. */
    ShardStream stream(final int streamId) {
        return new ShardStream(streamId, "stream " + streamId, ring(), collector(streamId));
    }

    /** A stream whose records go to {@code handler} instead, e.g. one that throws. */
    ShardStream stream(final int streamId, final SequencedEventHandler handler) {
        return new ShardStream(streamId, "stream " + streamId, ring(), collector(streamId), handler);
    }

    private static SequencedRingBuffer<Mbp10Schema> ring() {
        return new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence(), 64);
    }

    private MarketDataCollector collector(final int streamId) {
        return new MarketDataCollector(
                new NullLogger(), clock, uploader, pool, streamId, RecordType.MBP_10, MARKET_BUCKET);
    }

    static void publish(final ShardStream stream, final int count) {
        for (int i = 0; i < count; i++) {
            stream.ring().claim();
            stream.ring().publish();
        }
    }

    /** Waits for every handed-over minute to upload. */
    List<String> uploads() {
        uploader.close();
        synchronized (uploadedBuckets) {
            return List.copyOf(uploadedBuckets);
        }
    }
}
