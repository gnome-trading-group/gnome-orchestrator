package group.gnometrading.orchestrator.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class GatewayRollTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final Duration FOREVER = ChronoUnit.FOREVER.getDuration();

    private static Map<String, AttributeValue> roll(final String phase) {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(GatewayRoll.PHASE, AttributeValue.fromS(phase));
        item.put(GatewayRoll.INSTANCE_ID, AttributeValue.fromS("i-0abc"));
        item.put(GatewayRoll.STOPS_VERSION, AttributeValue.fromS("2.2.0"));
        item.put(GatewayRoll.UNTIL_MS, AttributeValue.fromN(Long.toString(NOW + 60_000)));
        return item;
    }

    private static GatewayRoll.Decision decide(final Map<String, AttributeValue> item) {
        return GatewayRoll.decide(item, "i-0abc", "2.2.0", FOREVER, NOW);
    }

    @Test
    void handsOverOnlyForARollThatNamesThisHostAndReleaseInTime() {
        assertTrue(decide(roll("stopping")).handOver());
        assertTrue(decide(roll("rolling_back")).handOver(), "a rollback's stop hands over too");
    }

    @Test
    void stopsAsPlannedWithoutARollOrOnceItIsPastStopping() {
        assertFalse(decide(Map.of()).handOver());
        for (final String phase : new String[] {"starting", "done", "rolled_back", "failed", "refused"}) {
            assertFalse(decide(roll(phase)).handOver(), phase);
        }
    }

    @Test
    void stopsAsPlannedOnAnotherHostOrRelease() {
        assertFalse(GatewayRoll.decide(roll("stopping"), "i-0def", "2.2.0", FOREVER, NOW)
                .handOver());
        assertFalse(
                GatewayRoll.decide(roll("stopping"), null, "2.2.0", FOREVER, NOW)
                        .handOver(),
                "off EC2");
        assertFalse(GatewayRoll.decide(roll("stopping"), "i-0abc", "2.1.0", FOREVER, NOW)
                .handOver());
    }

    @Test
    void stopsAsPlannedOnceTheRollsTimeIsUp() {
        final Map<String, AttributeValue> expired = roll("stopping");
        expired.put(GatewayRoll.UNTIL_MS, AttributeValue.fromN(Long.toString(NOW)));
        assertFalse(decide(expired).handOver());
        final Map<String, AttributeValue> untimed = roll("stopping");
        untimed.remove(GatewayRoll.UNTIL_MS);
        assertFalse(decide(untimed).handOver());
    }

    @Test
    void stopsAsPlannedOnAVenueThatCancelsRestingOrdersSoonerThanARollsGap() {
        final GatewayRoll.Decision decision =
                GatewayRoll.decide(roll("stopping"), "i-0abc", "2.2.0", Duration.ofSeconds(10), NOW);
        assertFalse(decision.handOver());
        assertTrue(decision.why().contains("10 s"), decision.why());
    }
}
