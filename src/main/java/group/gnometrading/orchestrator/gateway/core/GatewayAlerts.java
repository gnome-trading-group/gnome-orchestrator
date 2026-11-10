package group.gnometrading.orchestrator.gateway.core;

import java.util.Map;

/**
 * What the gateway alone sees going wrong, raised to gnome-registry's alert pipeline (types in its catalog, each with a
 * runbook). Raising an alert already open, or resolving one that isn't, changes nothing. Never blocks and allocates:
 * call it from a side thread or the control thread, never the order path.
 */
public interface GatewayAlerts {

    GatewayAlerts NONE = new GatewayAlerts() {
        @Override
        public void firing(
                final String type, final String about, final String title, final Map<String, String> labels) {}

        @Override
        public void resolved(final String type, final String about) {}
    };

    /**
     * @param about what in the gateway it concerns (an account, a session's order), or empty for the gateway itself:
     *     with the type and the gateway, the alert's key, so a repeat while it is open updates it
     */
    void firing(String type, String about, String title, Map<String, String> labels);

    void resolved(String type, String about);
}
