package group.gnometrading.gateways;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.gateways.inbound.BinanceInboundOrchestrator;
import group.gnometrading.gateways.inbound.DefaultInboundOrchestrator;
import group.gnometrading.gateways.inbound.HyperliquidInboundOrchestrator;
import group.gnometrading.gateways.inbound.KalshiInboundOrchestrator;
import group.gnometrading.gateways.inbound.LighterInboundOrchestrator;
import group.gnometrading.gateways.inbound.PolymarketIntlInboundOrchestrator;
import group.gnometrading.gateways.inbound.PolymarketUsInboundOrchestrator;
import group.gnometrading.gateways.outbound.DefaultOutboundOrchestrator;
import group.gnometrading.gateways.outbound.KalshiOutboundOrchestrator;
import group.gnometrading.gateways.outbound.PolymarketIntlOutboundOrchestrator;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import org.junit.jupiter.api.Test;

class GatewaySelectionTest {

    private static Listing listingOn(final String exchangeCode, final String exchangeName) {
        return new Listing(1, new Exchange(1, exchangeCode, exchangeName, "global", SchemaType.MBP_10), null, "x", "x");
    }

    @Test
    void inbound_SelectedByExchangeCode() {
        assertEquals(
                HyperliquidInboundOrchestrator.class,
                DefaultInboundOrchestrator.findInboundOrchestrator(listingOn("HYPERLIQUID", "Hyperliquid")));
        assertEquals(
                LighterInboundOrchestrator.class,
                DefaultInboundOrchestrator.findInboundOrchestrator(listingOn("LIGHTER", "Lighter")));
        assertEquals(
                BinanceInboundOrchestrator.class,
                DefaultInboundOrchestrator.findInboundOrchestrator(listingOn("BINANCE", "Binance")));
        assertEquals(
                KalshiInboundOrchestrator.class,
                DefaultInboundOrchestrator.findInboundOrchestrator(listingOn("KALSHI", "Kalshi")));
        assertEquals(
                PolymarketIntlInboundOrchestrator.class,
                DefaultInboundOrchestrator.findInboundOrchestrator(
                        listingOn("POLYMARKET_INTL", "Polymarket (International)")));
        assertEquals(
                PolymarketUsInboundOrchestrator.class,
                DefaultInboundOrchestrator.findInboundOrchestrator(listingOn("POLYMARKET_US", "Polymarket (US)")));
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
                () -> DefaultInboundOrchestrator.findInboundOrchestrator(listingOn("UNKNOWN_VENUE", "Unknown")));
        assertThrows(
                IllegalArgumentException.class,
                () -> DefaultOutboundOrchestrator.findOutboundOrchestrator(listingOn("BINANCE", "Binance")));
    }
}
