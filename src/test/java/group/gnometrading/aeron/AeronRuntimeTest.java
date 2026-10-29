package group.gnometrading.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeron.Publication;
import io.aeron.Subscription;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AeronRuntimeTest {

    @ParameterizedTest
    @EnumSource(AeronProfile.class)
    void aRuntimeCarriesAStreamAndCleansUpItsDirectory(final AeronProfile profile) throws Exception {
        final String directory;
        try (AeronRuntime shard = AeronRuntime.launch("test-shard-" + profile, profile, Throwable::printStackTrace);
                AeronRuntime session =
                        AeronRuntime.launch("test-session-" + profile, profile, Throwable::printStackTrace)) {
            directory = shard.directory();
            assertTrue(new File(directory).isDirectory());
            final Publication publication =
                    shard.aeron().addPublication(AeronChannels.shardPublication("localhost", 40050), 11);
            // Two hosts on one machine would hand out the same receive port, so the session names one here.
            final Subscription subscription = session.aeron()
                    .addSubscription(
                            "aeron:udp?endpoint=localhost:40990|control=localhost:40050|control-mode=dynamic", 11);
            final UnsafeBuffer buffer = new UnsafeBuffer(new byte[32]);
            buffer.putInt(0, 99);
            final AtomicInteger received = new AtomicInteger();
            final long deadline = System.nanoTime() + 10_000_000_000L;
            while (received.get() != 99) {
                assertTrue(System.nanoTime() < deadline, "timed out");
                publication.offer(buffer, 0, 32);
                subscription.poll((buf, offset, length, header) -> received.set(buf.getInt(offset)), 10);
                Thread.sleep(1);
            }
            assertEquals(99, received.get());
        }
        assertFalse(new File(directory).exists());
    }
}
