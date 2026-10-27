package group.gnometrading.feeds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.SecurityMaster;
import group.gnometrading.md.SourceAccount;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketDataCredentialsTest {

    private final SecurityMaster securityMaster = mock(SecurityMaster.class);

    private static SourceAccount account(final String source, final String label, final String status) {
        return new SourceAccount(
                1, 4, source, label, "gnome/market-data/" + source.toLowerCase() + "/" + label, 0, status);
    }

    @Test
    void sessionsKeepThePerExchangeKey() {
        assertEquals("gnome/market-data/kalshi", MarketDataCredentials.SESSION.secretFor("KALSHI"));
        assertEquals("gnome/market-data/polymarket-us", MarketDataCredentials.SESSION.secretFor("POLYMARKET_US"));
    }

    @Test
    void aShardUsesTheAccountItIsBoundToAndMd1ForASourceNotNamed() {
        when(securityMaster.getSourceAccount("KALSHI", "md-2")).thenReturn(account("KALSHI", "md-2", "ACTIVE"));
        when(securityMaster.getSourceAccount("POLYMARKET_US", "md-1"))
                .thenReturn(account("POLYMARKET_US", "md-1", "ACTIVE"));
        MarketDataCredentials credentials = MarketDataCredentials.forShard(securityMaster, Map.of("KALSHI", "md-2"));

        assertEquals("gnome/market-data/kalshi/md-2", credentials.secretFor("KALSHI"));
        assertEquals("gnome/market-data/polymarket_us/md-1", credentials.secretFor("POLYMARKET_US"));
    }

    @Test
    void aMissingOrRetiredAccountIsRefusedByName() {
        when(securityMaster.getSourceAccount("KALSHI", "md-1")).thenReturn(account("KALSHI", "md-1", "RETIRED"));
        MarketDataCredentials credentials = MarketDataCredentials.forShard(securityMaster, Map.of());

        IllegalStateException retired =
                assertThrows(IllegalStateException.class, () -> credentials.secretFor("KALSHI"));
        assertTrue(retired.getMessage().contains("md-1 for KALSHI is RETIRED"), retired.getMessage());
        IllegalStateException missing =
                assertThrows(IllegalStateException.class, () -> credentials.secretFor("POLYMARKET_US"));
        assertTrue(
                missing.getMessage().contains("No market-data account md-1 for POLYMARKET_US"), missing.getMessage());
    }

    @Test
    void labelsComeAsOneJsonObjectAndNothingMeansNone() {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(Map.of("KALSHI", "md-2"), MarketDataCredentials.parseLabels(mapper, "{\"KALSHI\":\"md-2\"}"));
        assertEquals(Map.of(), MarketDataCredentials.parseLabels(mapper, "{}"));
        assertEquals(Map.of(), MarketDataCredentials.parseLabels(mapper, null));
        assertThrows(IllegalArgumentException.class, () -> MarketDataCredentials.parseLabels(mapper, "KALSHI=md-2"));
    }
}
