package group.gnometrading.orchestrator.gateway.accounts;

import group.gnometrading.gateways.venues.kalshi.KalshiVenue;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlVenue;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsVenue;
import java.time.Duration;
import java.util.Locale;

/** The venues an order gateway trades, by the exchange code in its key. */
public enum GatewayVenue {
    KALSHI,
    POLYMARKET_US,
    POLYMARKET_INTL;

    public static GatewayVenue forExchangeCode(final String code) {
        for (final GatewayVenue venue : values()) {
            if (venue.name().equalsIgnoreCase(code)) {
                return venue;
            }
        }
        throw new IllegalArgumentException("No order gateway trades " + code.toUpperCase(Locale.ROOT));
    }

    /** How long the venue keeps resting orders with no gateway in front of them, as the venue's facts say. */
    public Duration ordersOutliveGatewayFor() {
        return switch (this) {
            case KALSHI -> KalshiVenue.ordersOutliveGatewayFor();
            case POLYMARKET_US -> PolymarketUsVenue.ordersOutliveGatewayFor();
            case POLYMARKET_INTL -> PolymarketIntlVenue.ordersOutliveGatewayFor();
        };
    }
}
