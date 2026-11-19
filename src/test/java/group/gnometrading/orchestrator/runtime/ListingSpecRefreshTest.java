package group.gnometrading.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.ListingSpecRefresher;
import group.gnometrading.logging.NullLogger;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ListingSpecRefreshTest {

    private final ListingSpecRefresher refresher = mock(ListingSpecRefresher.class);
    private final AtomicInteger after = new AtomicInteger();

    @Test
    void eachRefreshIsFollowedByItsAfterwards() {
        when(refresher.refresh()).thenReturn(new ListingSpecRefresher.Result(1, 0, null));

        ListingSpecRefresh.refreshOnce(new NullLogger(), refresher, after::incrementAndGet);

        assertEquals(1, after.get());
    }

    @Test
    void aRefreshThatThrowsIsLoggedNotThrownSoTheScheduleKeepsRunning() {
        when(refresher.refresh()).thenThrow(new IllegalStateException("registry down"));

        ListingSpecRefresh.refreshOnce(new NullLogger(), refresher, after::incrementAndGet);

        assertEquals(0, after.get());
    }
}
