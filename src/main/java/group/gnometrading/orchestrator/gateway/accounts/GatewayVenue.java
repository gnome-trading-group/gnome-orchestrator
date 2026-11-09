package group.gnometrading.orchestrator.gateway.accounts;

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
}
