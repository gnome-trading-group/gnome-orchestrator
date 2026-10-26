package group.gnometrading.gateways.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import org.junit.jupiter.api.Test;

class InboundRecordTypeTest {

    private static final List<Class<? extends DefaultInboundOrchestrator<?>>> VENUES = List.of(
            HyperliquidInboundOrchestrator.class,
            LighterInboundOrchestrator.class,
            KalshiInboundOrchestrator.class,
            PolymarketIntlInboundOrchestrator.class,
            PolymarketUsInboundOrchestrator.class);

    /** The ring buffer is built from the record type, so a venue whose two disagree would publish the wrong record. */
    @Test
    void everyVenuesRecordTypeIsTheRecordItsGatewayIsTypedFor() throws Exception {
        for (Class<? extends DefaultInboundOrchestrator<?>> venue : VENUES) {
            Class<?> typedFor =
                    (Class<?>) ((ParameterizedType) venue.getGenericSuperclass()).getActualTypeArguments()[0];
            DefaultInboundOrchestrator<?> orchestrator =
                    venue.getDeclaredConstructor().newInstance();
            assertEquals(typedFor, orchestrator.recordType().newInstance().getClass(), venue.getSimpleName());
        }
    }
}
