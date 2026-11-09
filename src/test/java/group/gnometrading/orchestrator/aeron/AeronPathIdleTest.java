package group.gnometrading.orchestrator.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.resources.Properties;
import org.junit.jupiter.api.Test;

class AeronPathIdleTest {

    @Test
    void sessionsAndShardsEachReadTheirOwnSetting() throws Exception {
        Properties properties = new Properties("agent-runtime/split-idle.properties");

        assertEquals(200_000, AeronPathIdle.from(properties).maxParkNanos());
        assertEquals(50_000, AeronPathIdle.forShard(properties).maxParkNanos());
    }

    @Test
    void eitherParksUpToAMillisecondWhenUnset() throws Exception {
        Properties properties = new Properties("agent-runtime/standard.properties");

        assertEquals(AeronPathIdle.DEFAULT, AeronPathIdle.from(properties));
        assertEquals(AeronPathIdle.DEFAULT, AeronPathIdle.forShard(properties));
    }

    @Test
    void aParkUnderAMicrosecondIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new AeronPathIdle(999));
    }
}
