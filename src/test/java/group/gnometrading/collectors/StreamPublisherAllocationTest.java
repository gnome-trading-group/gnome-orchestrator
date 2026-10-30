package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.aeron.AeronChannels;
import group.gnometrading.aeron.AeronProfile;
import group.gnometrading.aeron.AeronRuntime;
import group.gnometrading.aeron.AeronStreamIds;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.schemas.Mbp10Schema;
import io.aeron.Subscription;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Once warm, the publisher's thread must not allocate: it drains every ring the shard collects, so a GC pause there
 * would back up the feeds.
 */
@Tag("allocation")
class StreamPublisherAllocationTest {

    private static final int RECORDS = 200_000;

    @Test
    @Timeout(120)
    void thePublisherThreadDoesNotAllocateOnceWarm() throws Exception {
        final StreamTestKit kit = new StreamTestKit();
        try (AeronRuntime shard =
                        AeronRuntime.launch("pub-alloc-shard", AeronProfile.STANDARD, Throwable::printStackTrace);
                AeronRuntime session =
                        AeronRuntime.launch("pub-alloc-session", AeronProfile.STANDARD, Throwable::printStackTrace)) {
            final String channel = AeronChannels.shardPublication("localhost");
            final StreamPublisherAgent agent = new StreamPublisherAgent(
                    StreamPublication.of(shard.aeron().addPublication(channel, AeronStreamIds.SHARD_STATUS)),
                    () -> 1,
                    System::nanoTime,
                    System::currentTimeMillis);
            final ShardStream stream = kit.publishingStream(10);
            stream.publication(StreamPublication.of(shard.aeron().addPublication(channel, 10)));
            agent.add(stream);
            // Two hosts on one machine would hand out the same receive port, so the session names one here.
            final Subscription subscription = session.aeron()
                    .addSubscription(
                            "aeron:udp?endpoint=localhost:40960|control=localhost:40000|control-mode=dynamic", 10);
            final AtomicLong received = new AtomicLong();
            final AtomicLong publisherThread = new AtomicLong();
            final GnomeAgentRunner runner = new GnomeAgentRunner(
                    new GnomeAgent() {
                        @Override
                        public int doWork() throws Exception {
                            publisherThread.compareAndSet(
                                    0, Thread.currentThread().getId());
                            return agent.doWork();
                        }
                    },
                    Throwable::printStackTrace);
            GnomeAgentRunner.startOnThread(runner);
            final AtomicBoolean running = new AtomicBoolean(true);
            // The collector's poller gates the ring too; it drains here, on one thread of its own as the collector
            // agent does, and off the measured one.
            final Thread reader = new Thread(() -> {
                try {
                    while (running.get()) {
                        subscription.poll((buffer, offset, length, header) -> received.incrementAndGet(), 100);
                        stream.poll(System.nanoTime());
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            reader.start();
            try {
                while (subscription.imageCount() == 0) {
                    Thread.sleep(1);
                }
                publish(stream, RECORDS / 2);
                awaitHandled(stream, RECORDS / 2);

                final com.sun.management.ThreadMXBean threads =
                        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                final long before = threads.getThreadAllocatedBytes(publisherThread.get());
                publish(stream, RECORDS / 2);
                awaitHandled(stream, RECORDS);
                final long allocated = threads.getThreadAllocatedBytes(publisherThread.get()) - before;

                System.out.println("#### publisher thread allocated " + allocated + "B over " + (RECORDS / 2)
                        + " records (" + received.get() + " received, " + stream.dropped() + " dropped in all)");
                assertTrue(allocated < 16_384, "the publisher thread allocated " + allocated + " bytes");
            } finally {
                running.set(false);
                reader.join();
                runner.close();
            }
        }
    }

    private static void publish(final ShardStream stream, final int count) {
        for (int i = 0; i < count; i++) {
            final Mbp10Schema slot = stream.ring().claim();
            slot.encoder.streamId(10).sequence(i);
            stream.ring().publish();
        }
    }

    /** Every record offered, or dropped when the reader fell behind, which the publisher does rather than wait. */
    private static void awaitHandled(final ShardStream stream, final long target) throws InterruptedException {
        while (stream.offered() + stream.dropped() < target) {
            Thread.sleep(5);
        }
    }
}
