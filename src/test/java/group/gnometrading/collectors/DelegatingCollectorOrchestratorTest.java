package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DelegatingCollectorOrchestratorTest {

    private static final long MAX_STALE = TimeUnit.SECONDS.toNanos(90);
    // A wall-clock time in nanoseconds, as MarketDataCollector records it.
    private static final long NOW = TimeUnit.MILLISECONDS.toNanos(1_760_000_000_000L);

    @Test
    void healthyBeforeAnyListingHasReceived() {
        assertTrue(DelegatingCollectorOrchestrator.isHealthy(new long[] {0L, 0L}, NOW, MAX_STALE));
    }

    @Test
    void healthyWhileAnyListingIsStillReceiving() {
        long stale = NOW - TimeUnit.SECONDS.toNanos(600);
        long fresh = NOW - TimeUnit.SECONDS.toNanos(1);
        assertTrue(DelegatingCollectorOrchestrator.isHealthy(new long[] {stale, fresh}, NOW, MAX_STALE));
    }

    @Test
    void unhealthyOnceEveryListingHasGoneQuiet() {
        long stale = NOW - TimeUnit.SECONDS.toNanos(91);
        assertFalse(DelegatingCollectorOrchestrator.isHealthy(new long[] {stale, 0L}, NOW, MAX_STALE));
    }
}
