package group.gnometrading.orchestrator.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionPositionsTest {

    private static final int STRATEGY = 15;

    private static List<Listing> listings(int count) {
        Exchange kalshi = new Exchange(2, "KALSHI", "Kalshi", "global");
        List<Listing> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Security security = new Security(
                    1000 + i, "KXRT-" + i, null, null, null, null, null, null, false, false, 0L, 0L, true, 0);
            out.add(new Listing(500 + i, kalshi, security, "KXRT-EBE-" + i + ":yes", "KXRT-EBE-" + i));
        }
        return out;
    }

    @Test
    void everyListingOfASessionWithMoreThan64GetsAPositionSlot() {
        // Ebenezer's rt_review_wave session: 39 strikes on three venues, a YES and a NO listing each.
        List<Listing> listings = listings(78);
        DefaultPositionTracker tracker = SessionOrchestrator.positionTracker(listings);
        for (Listing listing : listings) {
            tracker.registerSlot(STRATEGY, listing.listingId());
        }
        List<Integer> seen = new ArrayList<>();
        tracker.forEachStrategyPosition((strategy, listing, position) -> seen.add(listing));
        assertEquals(78, seen.size());
    }
}
