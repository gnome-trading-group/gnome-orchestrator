package group.gnometrading.orchestrator.gateway;

import group.gnometrading.orchestrator.gateway.core.GatewayAlerts;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Every alert raised and resolved, in order, as "FIRING TYPE about" or "RESOLVED TYPE about". */
public final class RecordingAlerts implements GatewayAlerts {

    public record Raised(String type, String about, String title, Map<String, String> labels) {}

    public final List<String> said = new CopyOnWriteArrayList<>();
    public final List<Raised> raised = new CopyOnWriteArrayList<>();

    @Override
    public void firing(final String type, final String about, final String title, final Map<String, String> labels) {
        this.said.add(("FIRING " + type + " " + about).trim());
        this.raised.add(new Raised(type, about, title, labels));
    }

    @Override
    public void resolved(final String type, final String about) {
        this.said.add(("RESOLVED " + type + " " + about).trim());
    }
}
