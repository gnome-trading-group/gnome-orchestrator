package group.gnometrading.orchestrator.gateway.accounts;

import group.gnometrading.resources.Properties;

/**
 * What an order gateway is and how it sizes each account it holds.
 *
 * @param key the gateway's registry key, {@code live:<exchange code, lower case>:<instance>}
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

    /** The venue the key names. */
    public GatewayVenue venue() {
        final String[] parts = this.key.split(":");
        if (parts.length != 3 || !parts[0].equals("live")) {
            throw new IllegalArgumentException("A live gateway's key is live:<exchange>:<instance>, not " + this.key);
        }
        return GatewayVenue.forExchangeCode(parts[1]);
    }
}
