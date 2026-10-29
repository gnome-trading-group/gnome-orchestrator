package group.gnometrading.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.book.Mbp10Decoder;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import io.aeron.Publication;
import io.aeron.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AeronIngressAgentTest {

    private static final int STREAM_ID = 101;

    private final AeronRuntime shard =
            AeronRuntime.launch("ingress-shard", AeronProfile.STANDARD, Throwable::printStackTrace);
    private final AeronRuntime session =
            AeronRuntime.launch("ingress-session", AeronProfile.STANDARD, Throwable::printStackTrace);
    private final Publication publication =
            this.shard.aeron().addPublication(AeronChannels.shardPublication("localhost", 40060), STREAM_ID);
    // Two hosts on one machine would hand out the same receive port, so the session names one here.
    private final Subscription subscription = this.session
            .aeron()
            .addSubscription(
                    "aeron:udp?endpoint=localhost:40980|control=localhost:40060|control-mode=dynamic", STREAM_ID);
    private final AeronIngressAgent agent = new AeronIngressAgent();
    private final Mbp10Schema message = new Mbp10Schema();

    @AfterEach
    void close() {
        this.session.close();
        this.shard.close();
    }

    private void publish(final int sequence) throws InterruptedException {
        this.message.encoder.streamId(STREAM_ID).sequence(sequence);
        await(
                () -> this.publication.offer(this.message.buffer, 0, this.message.totalMessageSize()) > 0,
                "offer " + sequence);
    }

    private void await(final BooleanSupplier condition, final String what) throws InterruptedException {
        final long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            this.agent.doWork();
            Thread.sleep(1);
        }
    }

    @Test
    void recordsReachTheRingInOrderAndUnchanged() throws Exception {
        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        final List<Long> sequences = new ArrayList<>();
        final List<Integer> templates = new ArrayList<>();
        final Mbp10Schema received = new Mbp10Schema();
        final SequencedPoller poller = ring.createPoller((seq, templateId, buffer, length) -> {
            received.buffer.putBytes(0, buffer, 0, length);
            templates.add(templateId);
            sequences.add(received.decoder.sequence());
        });
        this.agent.add(this.subscription, new RingSink(ring));
        await(this.publication::isConnected, "the session to join");

        for (int i = 1; i <= 50; i++) {
            publish(i);
        }
        await(
                () -> {
                    try {
                        poller.poll();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return sequences.size() == 50;
                },
                "50 records");

        for (int i = 0; i < 50; i++) {
            assertEquals(i + 1, sequences.get(i));
            assertEquals(Mbp10Decoder.TEMPLATE_ID, templates.get(i));
        }
        assertEquals(STREAM_ID, received.decoder.streamId());
    }

    @Test
    void aFullRingLeavesRecordsInAeronUntilItDrainsAndLosesNone() throws Exception {
        final SequencedRingBuffer<Mbp10Schema> ring =
                new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence(), 4);
        final List<Long> sequences = new ArrayList<>();
        final Mbp10Schema received = new Mbp10Schema();
        final SequencedPoller poller = ring.createPoller((seq, templateId, buffer, length) -> {
            received.buffer.putBytes(0, buffer, 0, length);
            sequences.add(received.decoder.sequence());
        });
        this.agent.add(this.subscription, new RingSink(ring));
        await(this.publication::isConnected, "the session to join");

        for (int i = 1; i <= 20; i++) {
            publish(i);
        }
        await(() -> this.agent.refused() > 0, "the ring to fill");
        while (sequences.size() < 20) {
            poller.poll();
            this.agent.doWork();
        }

        for (int i = 0; i < 20; i++) {
            assertEquals(i + 1, sequences.get(i));
        }
        assertTrue(this.agent.refused() > 0);
        assertEquals(0, this.agent.fragmented());
    }

    @Test
    void aRemovedSubscriptionIsNoLongerPolled() throws Exception {
        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        final List<Long> seen = new ArrayList<>();
        final SequencedPoller poller = ring.createPoller((seq, templateId, buffer, length) -> seen.add(seq));
        this.agent.add(this.subscription, new RingSink(ring));
        await(this.publication::isConnected, "the session to join");
        publish(1);
        await(
                () -> {
                    try {
                        poller.poll();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return seen.size() == 1;
                },
                "the first record");

        this.agent.remove(this.subscription);
        this.agent.doWork();
        publish(2);
        for (int i = 0; i < 50; i++) {
            this.agent.doWork();
            Thread.sleep(1);
        }

        poller.poll();
        assertEquals(1, seen.size());
    }
}
