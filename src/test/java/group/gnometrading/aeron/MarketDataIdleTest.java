package group.gnometrading.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.resources.Properties;
import org.junit.jupiter.api.Test;

class MarketDataIdleTest {

    @Test
    void sessionsAndShardsEachReadTheirOwnSetting() throws Exception {
        Properties properties = new Properties("agent-runtime/split-idle.properties");

        assertEquals(200_000, MarketDataIdle.from(properties).maxParkNanos());
        assertEquals(50_000, MarketDataIdle.forShard(properties).maxParkNanos());
    }

    @Test
    void eitherParksUpToAMillisecondWhenUnset() throws Exception {
        Properties properties = new Properties("agent-runtime/standard.properties");

        assertEquals(MarketDataIdle.DEFAULT, MarketDataIdle.from(properties));
        assertEquals(MarketDataIdle.DEFAULT, MarketDataIdle.forShard(properties));
    }

    @Test
    void aParkUnderAMicrosecondIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new MarketDataIdle(999));
    }
}
