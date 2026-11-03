package group.gnometrading.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.collectors.ShardOrchestrator;
import group.gnometrading.trading.TradingOrchestrator;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void eachRoleNamesItsProcess() {
        assertEquals(TradingOrchestrator.class, Main.Role.of("session").type());
        assertEquals(ShardOrchestrator.class, Main.Role.of("shard").type());
    }

    @Test
    void anUnknownRoleListsTheKnownOnes() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Main.Role.of("collector"));

        assertTrue(error.getMessage().contains("session, shard"), error.getMessage());
    }
}
