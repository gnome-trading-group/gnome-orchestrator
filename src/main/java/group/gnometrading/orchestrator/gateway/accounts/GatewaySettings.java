package group.gnometrading.orchestrator.gateway.accounts;

import group.gnometrading.resources.Properties;
import java.util.Locale;

/**
 * What an order gateway is and how it sizes each account it holds.
 *
 * @param key the gateway's registry key, {@code live:<exchange code, lower case>:<instance>} for one that trades the
 *     venue, {@code paper:...} for one that simulates it for paper sessions
 * @param maxOrders the most orders one account may have working at once, across every session
 * @param laneConnections HTTP connections each account's lane sends on, so a slow answer holds up only its own order
 */
public record GatewaySettings(String key, int maxOrders, int laneConnections) {

    public static GatewaySettings of(final Properties properties) {
        return new GatewaySettings(
                properties.getStringProperty("gateway.key"),
                properties.getIntProperty("gateway.max.orders"),
                properties.getIntProperty("gateway.lane.connections"));
    }

    /** Whether it simulates its venue for paper sessions rather than trading it. */
    public boolean paper() {
        return parts()[0].equals("paper");
    }

    /** The exchange the key names, as the security master codes it (upper case). */
    public String exchangeCode() {
        return parts()[1].toUpperCase(Locale.ROOT);
    }

    /** The venue the key names; only a live gateway has one, as only venues with order entry can be traded. */
    public GatewayVenue venue() {
        if (paper()) {
            throw new IllegalStateException("A paper gateway trades no venue: " + this.key);
        }
        return GatewayVenue.forExchangeCode(parts()[1]);
    }

    private String[] parts() {
        final String[] parts = this.key.split(":");
        if (parts.length != 3 || !(parts[0].equals("live") || parts[0].equals("paper"))) {
            throw new IllegalArgumentException(
                    "A gateway's key is live:<exchange>:<instance> or paper:<exchange>:<instance>, not " + this.key);
        }
        return parts;
    }
}
