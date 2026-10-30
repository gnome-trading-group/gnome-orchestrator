package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.MessageHeaderDecoder;
import group.gnometrading.schemas.ShardStatusDecoder;
import io.aeron.Publication;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class StreamPublisherAgentTest {

    private static final long SECOND = 1_000_000_000L;

    /** Records what it is offered; answers with {@code result} while that is negative. */
    private static final class Recording implements StreamPublication {
        final List<byte[]> offers = new ArrayList<>();
        long result = 1;
        long position = 4_096;
        // Answers these first, then {@link #result}: an end-of-term rotation, say.
        final java.util.ArrayDeque<Long> first = new java.util.ArrayDeque<>();

        @Override
        public long offer(final DirectBuffer buffer, final int offset, final int length) {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            offers.add(copy);
            return first.isEmpty() ? result : first.poll();
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public int sessionId() {
            return 77;
        }

        @Override
        public void close() {}
    }

    private final StreamTestKit kit = new StreamTestKit();
    private final Recording status = new Recording();
    private long now = 10 * SECOND;
    private final StreamPublisherAgent agent = new StreamPublisherAgent(status, () -> 7, () -> now, () -> 99);

    private ShardStream publishedStream(final int streamId) {
        final ShardStream stream = kit.publishingStream(streamId);
        agent.add(stream);
        return stream;
    }

    /** A status message as {sentNanos, generation, then streamId, state, position per stream}. */
    private static List<Long> decode(final byte[] message) {
        final UnsafeBuffer buffer = new UnsafeBuffer(message);
        final MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buffer, 0);
        assertEquals(ShardStatusDecoder.TEMPLATE_ID, header.templateId());
        final ShardStatusDecoder status = new ShardStatusDecoder()
                .wrap(buffer, MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version());
        final List<Long> fields = new ArrayList<>(List.of(status.sentNanos(), status.generation()));
        for (final ShardStatusDecoder.StreamsDecoder stream : status.streams()) {
            fields.add((long) stream.streamId());
            fields.add((long) stream.state());
            fields.add(stream.position());
            fields.add((long) stream.publicationSessionId());
            fields.add(stream.dropped());
            fields.add((long) stream.reconnecting());
        }
        return fields;
    }

    private static int streamsIn(final byte[] message) {
        return (decode(message).size() - 2) / 6;
    }

    private static int sequenceOf(final byte[] record) {
        final Mbp10Schema schema = new Mbp10Schema();
        schema.buffer.putBytes(0, record);
        return (int) schema.decoder.sequence();
    }

    private static void book(final ShardStream stream, final long sequence) {
        final Mbp10Schema slot = stream.ring().claim();
        slot.encoder.streamId(stream.streamId()).sequence(sequence);
        stream.ring().publish();
    }

    @Test
    void aLeasedStreamsRecordsGoToItsPublicationAsTheyArrive() throws Exception {
        final ShardStream stream = publishedStream(10);
        final Recording publication = new Recording();
        stream.publication(publication);

        book(stream, 1);
        book(stream, 2);
        agent.doWork();

        assertEquals(
                List.of(1, 2),
                publication.offers.stream()
                        .map(StreamPublisherAgentTest::sequenceOf)
                        .toList());
        assertEquals(2, stream.offered());
    }

    @Test
    void anUnleasedStreamIsStillDrainedSoItsRingNeverBlocksTheFeed() throws Exception {
        final ShardStream stream = publishedStream(10);
        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < 50; i++) {
                book(stream, i);
            }
            agent.doWork();
            stream.poll(now);
        }
        assertEquals(500, stream.records());
    }

    @Test
    void aNewPublicationGetsTheLastRecordAtOnce() throws Exception {
        final ShardStream stream = publishedStream(10);
        book(stream, 5);
        agent.doWork();

        final Recording publication = new Recording();
        stream.publication(publication);
        agent.doWork();

        assertEquals(
                List.of(5),
                publication.offers.stream()
                        .map(StreamPublisherAgentTest::sequenceOf)
                        .toList());
    }

    @Test
    void aQuietStreamResendsItsLastRecordEveryFiveSeconds() throws Exception {
        final ShardStream stream = publishedStream(10);
        final Recording publication = new Recording();
        stream.publication(publication);
        book(stream, 3);
        agent.doWork();

        now += ShardStream.RESEND_NANOS - 1;
        agent.doWork();
        assertEquals(1, publication.offers.size(), "not quiet long enough");
        now += 1;
        agent.doWork();
        assertEquals(
                List.of(3, 3),
                publication.offers.stream()
                        .map(StreamPublisherAgentTest::sequenceOf)
                        .toList());

        book(stream, 4);
        now += 1;
        agent.doWork();
        now += ShardStream.RESEND_NANOS - 1;
        agent.doWork();
        assertEquals(3, publication.offers.size(), "a new record restarts the quiet period");
    }

    @Test
    void aRecordNoSessionCouldTakeIsCountedAndDroppedNotRetried() throws Exception {
        final ShardStream stream = publishedStream(10);
        final Recording publication = new Recording();
        publication.result = Publication.BACK_PRESSURED;
        stream.publication(publication);
        book(stream, 1);
        agent.doWork();

        publication.result = Publication.NOT_CONNECTED;
        book(stream, 2);
        agent.doWork();

        assertEquals(1, stream.dropped(), "nobody listening isn't a drop, nor is a re-send that doesn't go through");
        assertEquals(1, stream.backPressured());
        assertEquals(0, stream.lapped());
        assertEquals(0, stream.offered());
    }

    @Test
    void anEndOfTermRotationIsRetriedAtOnceNotDropped() throws Exception {
        final ShardStream stream = publishedStream(10);
        final Recording publication = new Recording();
        stream.publication(publication);
        agent.doWork();
        publication.offers.clear();

        publication.first.add(Publication.ADMIN_ACTION);
        book(stream, 1);
        agent.doWork();

        assertEquals(2, publication.offers.size(), "offered again straight away");
        assertEquals(1, stream.offered());
        assertEquals(0, stream.dropped());
    }

    @Test
    void aNewPublicationNobodyHasJoinedKeepsOfferingTheLastRecordUntilSomeoneHas() throws Exception {
        final ShardStream stream = publishedStream(10);
        book(stream, 5);
        agent.doWork();
        final Recording publication = new Recording();
        publication.result = Publication.NOT_CONNECTED;
        stream.publication(publication);

        agent.doWork();
        agent.doWork();
        assertEquals(2, publication.offers.size());
        publication.result = 1;
        agent.doWork();
        agent.doWork();

        assertEquals(3, publication.offers.size(), "taken once, then quiet until the re-send interval");
    }

    @Test
    void recordsThePublisherWasLappedPastCountAsDropped() throws Exception {
        final ShardStream stream = publishedStream(10);
        stream.publication(new Recording());
        for (int i = 0; i < 200; i++) {
            book(stream, i);
            stream.poll(now);
        }
        agent.doWork();

        assertTrue(stream.dropped() > 0, "a ring of 64 lapped by 200 records");
        assertEquals(stream.dropped(), stream.lapped());
        assertEquals(0, stream.backPressured());
    }

    @Test
    void theStatusNamesEveryPublishedStreamWithItsPositionEverySecond() throws Exception {
        final ShardStream published = publishedStream(10);
        publishedStream(11);
        final Recording publication = new Recording();
        publication.position = 12_345;
        published.publication(publication);

        agent.doWork();
        assertEquals(1, status.offers.size());
        assertEquals(
                List.of(99L, 7L, 10L, (long) StreamPublisherAgent.NO_STATE, 12_345L, 77L, 0L, 0L),
                decode(status.offers.get(0)),
                "only the leased stream; no market placed in this test");

        now += StreamPublisherAgent.STATUS_EVERY_NANOS - 1;
        agent.doWork();
        assertEquals(1, status.offers.size());
        now += 1;
        agent.doWork();
        assertEquals(2, status.offers.size());
    }

    @Test
    void aFullStatusMessageFitsOneAeronFrame() {
        assertTrue(StreamPublisherAgent.STATUS_CAPACITY <= 1408 - 32);
    }

    @Test
    void aShardPublishingNothingStillSaysItIsAlive() throws Exception {
        agent.doWork();

        assertEquals(0, streamsIn(status.offers.get(0)));
    }

    @Test
    void manyPublishedStreamsSpreadOverSeveralFrames() throws Exception {
        for (int streamId = 1; streamId <= StreamPublisherAgent.MAX_STREAMS_PER_STATUS + 5; streamId++) {
            publishedStream(streamId).publication(new Recording());
        }

        agent.doWork();

        assertEquals(2, status.offers.size());
        assertEquals(StreamPublisherAgent.MAX_STREAMS_PER_STATUS, streamsIn(status.offers.get(0)));
        assertEquals(5, streamsIn(status.offers.get(1)));
    }
}
