package group.gnometrading.gateways;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.gateways.orders.DefaultOutboundOrchestrator;
import group.gnometrading.gateways.orders.KalshiOutboundOrchestrator;
import group.gnometrading.gateways.orders.PolymarketIntlOutboundOrchestrator;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import org.junit.jupiter.api.Test;

class GatewaySelectionTest {

    private static Listing listingOn(final String exchangeCode, final String exchangeName) {
        return new Listing(1, new Exchange(1, exchangeCode, exchangeName, "global"), null, "x", "x");
    }

    @Test
    void outbound_SelectedByExchangeCode() {
        assertEquals(
                KalshiOutboundOrchestrator.class,
                DefaultOutboundOrchestrator.findOutboundOrchestrator(listingOn("KALSHI", "Kalshi")));
        assertEquals(
                PolymarketIntlOutboundOrchestrator.class,
                DefaultOutboundOrchestrator.findOutboundOrchestrator(
                        listingOn("POLYMARKET_INTL", "Polymarket (International)")));
    }

    @Test
    void displayName_PlaysNoPartInSelection() {
        // The display name is free to change; only the code identifies the venue.
        assertThrows(
                IllegalArgumentException.class,
                () -> DefaultOutboundOrchestrator.findOutboundOrchestrator(listingOn("SOMETHING_ELSE", "Polymarket")));
        assertEquals(
                PolymarketIntlOutboundOrchestrator.class,
                DefaultOutboundOrchestrator.findOutboundOrchestrator(listingOn("POLYMARKET_INTL", "Renamed Anything")));
    }

    @Test
    void unknownCode_FailsLoudly() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DefaultOutboundOrchestrator.findOutboundOrchestrator(listingOn("UNKNOWN_VENUE", "Unknown")));
    }
}
