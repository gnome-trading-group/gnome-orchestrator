package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.SecurityMaster;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sm.AssetClass;
import group.gnometrading.sm.ContractType;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.SecurityType;
import java.util.List;
import org.junit.jupiter.api.Test;

class DelegatingCollectorOrchestratorTest {

    @Test
    void resolvesTrimmedUniqueListingsInConfiguredOrder() {
        final Properties properties = mock(Properties.class);
        final SecurityMaster securityMaster = mock(SecurityMaster.class);
        final Listing first = listing(101);
        final Listing second = listing(202);
        when(properties.getStringProperty("listings")).thenReturn("101, 202,101");
        when(securityMaster.getListing(101)).thenReturn(first);
        when(securityMaster.getListing(202)).thenReturn(second);

        assertEquals(
                List.of(first, second), DelegatingCollectorOrchestrator.resolveListings(properties, securityMaster));
    }

    @Test
    void fallsBackToLegacySingleListingProperty() {
        final Properties properties = mock(Properties.class);
        final SecurityMaster securityMaster = mock(SecurityMaster.class);
        final Listing listing = listing(303);
        when(properties.getStringProperty("listings")).thenThrow(new IllegalArgumentException("missing"));
        when(properties.getStringProperty("listing")).thenReturn("303");
        when(securityMaster.getListing(303)).thenReturn(listing);

        assertEquals(List.of(listing), DelegatingCollectorOrchestrator.resolveListings(properties, securityMaster));
    }

    private static Listing listing(final int listingId) {
        return new Listing(
                listingId,
                new Exchange(151, "Polymarket", "global", SchemaType.MBP_10),
                new Security(
                        listingId + 1,
                        "TEST-" + listingId,
                        SecurityType.EVENT_CONTRACT,
                        ContractType.BINARY,
                        AssetClass.PREDICTION,
                        "",
                        "USD",
                        "USD",
                        false,
                        false,
                        0L,
                        0L,
                        true,
                        0),
                "condition:" + listingId,
                "TEST-YES");
    }
}
