package group.gnometrading.orchestrator.gateway.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GatewaySettingsTest {

    @Test
    void theKeyNamesTheVenue() {
        assertEquals(GatewayVenue.KALSHI, new GatewaySettings("live:kalshi:main", 1, 1).venue());
        assertEquals(GatewayVenue.POLYMARKET_INTL, new GatewaySettings("live:polymarket_intl:b", 1, 1).venue());
        assertEquals(GatewayVenue.POLYMARKET_US, new GatewaySettings("live:polymarket_us:main", 1, 1).venue());
    }

    @Test
    void aKeyThatIsntALiveVenuesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new GatewaySettings("paper", 1, 1).venue());
        assertThrows(IllegalArgumentException.class, () -> new GatewaySettings("live:lighter:main", 1, 1).venue());
    }

    @Test
    void aLaneIsAliveWhileItsPassesMoveAndNotOnceTheyStop() {
        final AtomicLong passes = new AtomicLong();
        final AtomicLong now = new AtomicLong(1_000);
        final LaneLiveness alive = new LaneLiveness(passes::get, now::get, 100);
        assertEquals(false, alive.getAsBoolean(), "not before the lane has run");
        passes.set(5);
        assertEquals(true, alive.getAsBoolean());
        now.addAndGet(99);
        assertEquals(true, alive.getAsBoolean());
        now.addAndGet(1);
        assertEquals(false, alive.getAsBoolean(), "stuck for the stall time");
        passes.incrementAndGet();
        assertEquals(true, alive.getAsBoolean());
    }
}
