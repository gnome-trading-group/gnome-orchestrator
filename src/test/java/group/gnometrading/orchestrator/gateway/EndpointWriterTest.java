package group.gnometrading.orchestrator.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.gateway.GatewayState;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EndpointWriterTest {

    @Test
    void theControlThreadNeverWaitsOnAWriteAndOnlyTheLatestStateWaitsBehindASlowOne() throws Exception {
        final CountDownLatch firstWriting = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> written = new CopyOnWriteArrayList<>();
        final EndpointWriter writer = new EndpointWriter(new NullLogger(), (state, linked, working) -> {
                    if (written.isEmpty()) {
                        firstWriting.countDown();
                        release.await();
                    }
                    written.add(state + "/" + linked + "/" + working);
                })
                .start();

        writer.advertise(GatewayState.STARTING, 0, 0);
        assertTrue(firstWriting.await(5, TimeUnit.SECONDS));
        final long started = System.nanoTime();
        writer.advertise(GatewayState.READY, 1, 0);
        writer.advertise(GatewayState.READY, 1, 2);
        writer.advertise(GatewayState.DEGRADED, 1, 2);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 100, "advertising didn't wait");

        release.countDown();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (written.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(List.of("STARTING/0/0", "DEGRADED/1/2"), written, "the states in between overtaken");
    }

    @Test
    void aFailedWriteIsLoggedAndTheNextStillGoesOut() throws Exception {
        final List<String> written = new CopyOnWriteArrayList<>();
        final EndpointWriter writer = new EndpointWriter(new NullLogger(), (state, linked, working) -> {
                    if (state == GatewayState.STARTING) {
                        throw new IllegalStateException("table unreachable");
                    }
                    written.add(state.name());
                })
                .start();

        writer.advertise(GatewayState.STARTING, 0, 0);
        Thread.sleep(50);
        writer.advertise(GatewayState.READY, 0, 0);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (written.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(List.of("READY"), written);
    }
}
