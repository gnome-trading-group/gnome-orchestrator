package group.gnometrading.orchestrator.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.resources.Properties;
import org.agrona.concurrent.BackoffIdleStrategy;
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
    void aGatewaySetsItsThreadsTimerSlackOnlyWhenAsked() throws Exception {
        final AeronPathIdle unset = AeronPathIdle.forGateway(new Properties("orchestrator.prod.properties"));
        assertEquals(0, unset.timerSlackNanos());
        assertInstanceOf(BackoffIdleStrategy.class, unset.agentStrategy(), "parks exactly as before");

        final AeronPathIdle slacked = AeronPathIdle.forGateway(
                new Properties("orchestrator.prod.properties", new String[] {"--gateway.idle.timer.slack.nanos=1000"}));
        assertEquals(1_000, slacked.timerSlackNanos());
        assertEquals(unset.maxParkNanos(), slacked.maxParkNanos());
        assertNotSame(BackoffIdleStrategy.class, slacked.agentStrategy().getClass());
        // Idling sets the calling thread's slack (a no-op off Linux), then parks as before.
        slacked.agentStrategy().idle(0);
    }

    @Test
    void aParkUnderAMicrosecondIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new AeronPathIdle(999));
    }
}
