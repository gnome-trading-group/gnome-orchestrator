package group.gnometrading.aeron;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import io.aeron.Publication;
import io.aeron.Subscription;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Once warm, the ingress agent's thread must not allocate: a GC pause there delays every stream a session reads. */
@Tag("allocation")
class AeronIngressAllocationTest {

    private static final int RECORDS = 200_000;

    @Test
    @Timeout(120)
    void theIngressThreadDoesNotAllocateOnceWarm() throws Exception {
        try (AeronRuntime shard =
                        AeronRuntime.launch("alloc-shard", AeronProfile.STANDARD, Throwable::printStackTrace);
                AeronRuntime session =
                        AeronRuntime.launch("alloc-session", AeronProfile.STANDARD, Throwable::printStackTrace)) {
            final Publication publication =
                    shard.aeron().addPublication(AeronChannels.shardPublication("localhost", 40070), 7);
            final Subscription subscription = session.aeron()
                    .addSubscription(
                            "aeron:udp?endpoint=localhost:40970|control=localhost:40070|control-mode=dynamic", 7);
            final SequencedRingBuffer<Mbp10Schema> ring =
                    new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
            final AtomicLong strategySeen = new AtomicLong();
            final SequencedPoller strategy =
                    ring.createPoller((seq, templateId, buffer, length) -> strategySeen.lazySet(seq));
            final AeronIngressAgent agent = new AeronIngressAgent();
            agent.add(subscription, new RingSink(ring));
            final AtomicLong ingressThread = new AtomicLong();
            final GnomeAgentRunner runner = new GnomeAgentRunner(
                    new group.gnometrading.concurrent.GnomeAgent() {
                        @Override
                        public int doWork() {
                            ingressThread.compareAndSet(
                                    0, Thread.currentThread().getId());
                            return agent.doWork();
                        }
                    },
                    Throwable::printStackTrace);
            GnomeAgentRunner.startOnThread(runner);
            final AtomicBoolean running = new AtomicBoolean(true);
            final Thread strategyThread = new Thread(() -> {
                try {
                    while (running.get()) {
                        strategy.poll();
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            strategyThread.start();
            try {
                final Mbp10Schema message = new Mbp10Schema();
                while (!publication.isConnected()) {
                    Thread.sleep(1);
                }
                publishRecords(publication, message, RECORDS / 2);
                awaitSeen(strategySeen, RECORDS / 2);

                final com.sun.management.ThreadMXBean threads =
                        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                final long before = threads.getThreadAllocatedBytes(ingressThread.get());
                publishRecords(publication, message, RECORDS / 2);
                awaitSeen(strategySeen, RECORDS);
                final long allocated = threads.getThreadAllocatedBytes(ingressThread.get()) - before;

                System.out.println(
                        "#### ingress thread allocated " + allocated + "B over " + (RECORDS / 2) + " records");
                assertTrue(allocated < 16_384, "the ingress thread allocated " + allocated + " bytes");
            } finally {
                running.set(false);
                strategyThread.join();
                runner.close();
            }
        }
    }

    private static void publishRecords(final Publication publication, final Mbp10Schema message, final int count) {
        for (int i = 0; i < count; i++) {
            message.encoder.sequence(i);
            while (publication.offer(message.buffer, 0, message.totalMessageSize()) < 0) {
                Thread.onSpinWait();
            }
        }
    }

    private static void awaitSeen(final AtomicLong seen, final long count) throws InterruptedException {
        while (seen.get() < count) {
            Thread.sleep(5);
        }
    }
}
