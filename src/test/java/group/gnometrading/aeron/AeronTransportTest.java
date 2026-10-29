package group.gnometrading.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeron.Aeron;
import io.aeron.ChannelUri;
import io.aeron.ConcurrentPublication;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.exceptions.RegistrationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The Aeron behaviour Phase 10 relies on, checked against real media drivers on loopback: one per simulated host.
 * Re-run on every Aeron upgrade.
 */
class AeronTransportTest {

    private static final String HOST = "localhost";
    private static final int STREAM_ID = 7;

    private final List<AutoCloseable> closeables = new ArrayList<>();
    // Real hosts each run one driver over the whole receive range. Drivers on one machine don't see each other's
    // ports, so every simulated host here gets its own slice of it.
    private int nextReceivePort = 40100;

    @AfterEach
    void close() throws Exception {
        for (int i = this.closeables.size() - 1; i >= 0; i--) {
            this.closeables.get(i).close();
        }
    }

    private Aeron host(final String name) throws Exception {
        final Path dir = Files.createTempDirectory("aeron-" + name);
        final MediaDriver driver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .aeronDirectoryName(dir.resolve("driver").toString())
                .threadingMode(ThreadingMode.SHARED)
                .receiverWildcardPortRange(this.nextReceivePort + " " + ((this.nextReceivePort += 10) - 1))
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));
        this.closeables.add(driver);
        final Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
        this.closeables.add(aeron);
        return aeron;
    }

    private static String publication(final int controlPort, final String extra) {
        return AeronChannels.shardPublication(HOST, controlPort) + extra;
    }

    private static String subscription(final int endpointPort, final int controlPort) {
        return "aeron:udp?endpoint=" + HOST + ":" + endpointPort + "|control=" + HOST + ":" + controlPort
                + "|control-mode=dynamic";
    }

    private static int port(final Subscription subscription) {
        final String endpoint =
                ChannelUri.parse(subscription.tryResolveChannelEndpointPort()).get("endpoint");
        return Integer.parseInt(endpoint.substring(endpoint.lastIndexOf(':') + 1));
    }

    private static void await(final BooleanSupplier condition, final String what) throws InterruptedException {
        final long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(1);
        }
    }

    private static long offer(final Publication publication, final UnsafeBuffer buffer, final int value) {
        buffer.putInt(0, value);
        return publication.offer(buffer, 0, 64);
    }

    @Test
    void oneReceivePortCantServeSubscriptionsToSeveralShards() throws Exception {
        final Aeron session = host("session");
        session.addSubscription(subscription(40100, 40000), STREAM_ID);

        final RegistrationException refused = assertThrows(
                RegistrationException.class, () -> session.addSubscription(subscription(40100, 40002), STREAM_ID + 1));
        assertTrue(refused.getMessage().contains("Address already in use"), refused.getMessage());
    }

    @Test
    void wildcardReceivePortsServeEveryShardFromTheConfiguredRange() throws Exception {
        final Aeron shardA = host("shard-a");
        final Aeron shardB = host("shard-b");
        final Aeron session = host("session");
        final Publication fromA = shardA.addPublication(publication(40000, ""), STREAM_ID);
        final Publication fromB = shardB.addPublication(publication(40002, ""), STREAM_ID + 1);

        final Subscription subA = session.addSubscription(subscription(0, 40000), STREAM_ID);
        final Subscription subB = session.addSubscription(subscription(0, 40002), STREAM_ID + 1);
        await(() -> fromA.isConnected() && fromB.isConnected(), "both shards to see the session");
        for (final Subscription sub : new Subscription[] {subA, subB}) {
            final int port =
                    Integer.parseInt(sub.tryResolveChannelEndpointPort().replaceAll(".*:", ""));
            assertTrue(port >= 40100 && port <= 40899, "port " + port);
        }

        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[64]);
        await(() -> offer(fromA, buffer, 1) > 0, "offer on A");
        await(() -> offer(fromB, buffer, 2) > 0, "offer on B");
        final AtomicInteger fromAValue = new AtomicInteger();
        final AtomicInteger fromBValue = new AtomicInteger();
        await(
                () -> {
                    subA.poll((buf, offset, length, header) -> fromAValue.set(buf.getInt(offset)), 10);
                    subB.poll((buf, offset, length, header) -> fromBValue.set(buf.getInt(offset)), 10);
                    return fromAValue.get() == 1 && fromBValue.get() == 2;
                },
                "both streams");
    }

    @Test
    void manyStreamsFromOneShardShareOneTaggedReceivePort() throws Exception {
        final Aeron shard = host("shard");
        final Aeron session = host("session");
        final Publication first = shard.addPublication(publication(40030, ""), STREAM_ID);
        final Publication second = shard.addPublication(publication(40030, ""), STREAM_ID + 1);

        // One wildcard endpoint per shard, named by a channel tag; the shard's other streams join it by tag.
        final long tag = session.nextCorrelationId();
        final Subscription firstSub =
                session.addSubscription(AeronChannels.shardSubscription(HOST, HOST, 40030, tag), STREAM_ID);
        final Subscription secondSub = session.addSubscription(AeronChannels.tagged(tag), STREAM_ID + 1);
        await(() -> first.isConnected() && second.isConnected(), "both streams connected");
        await(() -> firstSub.tryResolveChannelEndpointPort() != null, "the shared port");
        final int sharedPort = port(firstSub);
        assertTrue(sharedPort >= 40100 && sharedPort <= 40899, "port " + sharedPort);

        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[64]);
        final AtomicInteger firstValue = new AtomicInteger();
        final AtomicInteger secondValue = new AtomicInteger();
        await(
                () -> {
                    offer(first, buffer, 1);
                    offer(second, buffer, 2);
                    firstSub.poll((buf, offset, length, header) -> firstValue.set(buf.getInt(offset)), 10);
                    secondSub.poll((buf, offset, length, header) -> secondValue.set(buf.getInt(offset)), 10);
                    return firstValue.get() == 1 && secondValue.get() == 2;
                },
                "each stream on its own subscription");
    }

    @Test
    void aSubscriberThatStopsPollingNeverStallsTheShardOrTheOtherSubscribers() throws Exception {
        final Aeron shard = host("shard");
        final Aeron fast = host("fast");
        final Aeron slow = host("slow");
        final Publication publication = shard.addPublication(publication(40010, ""), STREAM_ID);
        final Subscription fastSub = fast.addSubscription(subscription(0, 40010), STREAM_ID);
        final AtomicInteger slowImagesLost = new AtomicInteger();
        final Subscription slowSub = slow.addSubscription(
                subscription(0, 40010), STREAM_ID, image -> {}, image -> slowImagesLost.incrementAndGet());
        await(() -> fastSub.imageCount() == 1 && slowSub.imageCount() == 1, "both subscribers joined");

        // 64-byte frames into a 64 KiB term: 20,000 is ~20 terms past where the slow subscriber stopped.
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[64]);
        final AtomicInteger received = new AtomicInteger();
        int backPressured = 0;
        for (int i = 1; i <= 20_000; i++) {
            long result;
            while ((result = offer(publication, buffer, i)) < 0) {
                if (result == Publication.BACK_PRESSURED) {
                    backPressured++;
                }
                fastSub.poll((buf, offset, length, header) -> received.set(buf.getInt(offset)), 100);
            }
            fastSub.poll((buf, offset, length, header) -> received.set(buf.getInt(offset)), 100);
        }
        await(
                () -> {
                    fastSub.poll((buf, offset, length, header) -> received.set(buf.getInt(offset)), 100);
                    return received.get() == 20_000;
                },
                "the fast subscriber to receive everything");

        // The slow one resumes while the shard keeps publishing: what does it read, and does it ever catch up?
        final AtomicInteger slowLast = new AtomicInteger();
        final AtomicInteger slowCount = new AtomicInteger();
        final AtomicInteger slowJumps = new AtomicInteger();
        final long until = System.nanoTime() + 3_000_000_000L;
        int next = 20_001;
        while (System.nanoTime() < until) {
            if (offer(publication, buffer, next) > 0) {
                next++;
            }
            fastSub.poll((buf, offset, length, header) -> {}, 100);
            slowSub.poll(
                    (buf, offset, length, header) -> {
                        final int value = buf.getInt(offset);
                        if (slowLast.get() != 0 && value != slowLast.get() + 1) {
                            slowJumps.incrementAndGet();
                        }
                        slowLast.set(value);
                        slowCount.incrementAndGet();
                    },
                    100);
            Thread.sleep(0, 100_000);
        }
        // It reads what its receive window still held, then stalls for good: the shard has overwritten the rest, so
        // NAKs go unanswered, and heartbeats keep the image "available". No unavailable-image callback ever fires.
        assertEquals(0, slowJumps.get());
        assertTrue(slowLast.get() < 20_000, "read " + slowLast.get());
        assertEquals(0, slowImagesLost.get());
        assertEquals(1, slowSub.imageCount());

        // A session must therefore spot the stall itself and subscribe again, which joins at the live position.
        slowSub.close();
        final Subscription rejoined = slow.addSubscription(subscription(0, 40010), STREAM_ID);
        await(() -> rejoined.imageCount() == 1, "the rejoin");
        final AtomicInteger afterRejoin = new AtomicInteger();
        final int[] published = {next};
        await(
                () -> {
                    if (offer(publication, buffer, published[0]) > 0) {
                        published[0]++;
                    }
                    fastSub.poll((buf, offset, length, header) -> {}, 100);
                    rejoined.poll((buf, offset, length, header) -> afterRejoin.set(buf.getInt(offset)), 100);
                    return afterRejoin.get() > 20_000;
                },
                "the rejoined subscriber to read live values");
    }

    @Test
    void sessionsJoinAndLeaveAShardStreamAtWill() throws Exception {
        final Aeron shard = host("shard");
        final Aeron first = host("first");
        final Aeron second = host("second");
        final ConcurrentPublication publication = shard.addPublication(publication(40020, ""), STREAM_ID);
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[64]);
        assertEquals(Publication.NOT_CONNECTED, offer(publication, buffer, 0), "no subscribers: dropped at once");

        final Subscription firstSub = first.addSubscription(subscription(0, 40020), STREAM_ID);
        await(publication::isConnected, "first subscriber");
        final Subscription secondSub = second.addSubscription(subscription(0, 40020), STREAM_ID);
        await(() -> secondSub.imageCount() == 1, "second subscriber");

        firstSub.close();
        final AtomicInteger seen = new AtomicInteger();
        await(
                () -> {
                    offer(publication, buffer, 5);
                    secondSub.poll((buf, offset, length, header) -> seen.set(buf.getInt(offset)), 10);
                    return seen.get() == 5;
                },
                "the remaining subscriber to keep receiving");
        secondSub.close();
        await(() -> !publication.isConnected(), "the shard to notice nobody listens");
        assertTrue(offer(publication, buffer, 6) < 0);
    }
}
