package group.gnometrading.orchestrator.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.gateway.core.GatewayAlerts;
import group.gnometrading.orchestrator.runtime.AlertsApi;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The gateway's alerts through the alerts ingest API, in its mode (a paper gateway's are paper alerts, which never
 * page), labelled with the gateway's key and exchange.
 * A failure to send is logged, never thrown: the condition is in the gateway's log either way.
 */
final class IngestGatewayAlerts implements GatewayAlerts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger logger;
    private final AlertsApi api;
    private final String gatewayKey;
    private final String exchange;
    private final String mode;

    IngestGatewayAlerts(final Logger logger, final AlertsApi api, final String gatewayKey) {
        this.logger = logger;
        this.api = api;
        this.gatewayKey = gatewayKey;
        final String[] parts = gatewayKey.split(":");
        this.mode = "paper".equals(parts[0]) ? "paper" : "live";
        this.exchange = parts.length > 1 ? parts[1] : "";
    }

    @Override
    public void firing(final String type, final String about, final String title, final Map<String, String> labels) {
        send("FIRING", type, about, title, labels);
    }

    @Override
    public void resolved(final String type, final String about) {
        send("RESOLVED", type, about, type + " cleared", Map.of());
    }

    /** Sends and waits a little for it, for the last thing a failing process says before it halts. */
    void firingAndWait(
            final String type,
            final String about,
            final String title,
            final Map<String, String> labels,
            final long millis) {
        final CompletableFuture<?> sent = send("FIRING", type, about, title, labels);
        if (sent == null) {
            return;
        }
        try {
            sent.get(millis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            this.logger.logf(LogMessage.UNKNOWN_ERROR, "Gateway: alert %s not sent before exiting: %s", type, e);
        }
    }

    private CompletableFuture<?> send(
            final String status,
            final String type,
            final String about,
            final String title,
            final Map<String, String> labels) {
        final String key = type + ":" + this.gatewayKey + (about.isEmpty() ? "" : ":" + about);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("type", type);
        body.put("key", key);
        body.put("title", title);
        body.put("mode", this.mode);
        final Map<String, String> all = new LinkedHashMap<>();
        all.put("gateway", this.gatewayKey);
        all.put("exchange", this.exchange);
        all.putAll(labels);
        body.put("labels", all);
        body.put("occurredAt", Instant.now().toString());
        try {
            final CompletableFuture<HttpResponse<String>> sent = this.api.post(MAPPER.writeValueAsString(body));
            if (sent != null) {
                sent.whenComplete((response, error) -> {
                    if (error != null) {
                        this.logger.logf(LogMessage.UNKNOWN_ERROR, "Gateway: alert %s not sent: %s", key, error);
                    } else if (response.statusCode() >= 300) {
                        this.logger.logf(
                                LogMessage.UNKNOWN_ERROR,
                                "Gateway: alert %s refused (%d): %s",
                                key,
                                response.statusCode(),
                                response.body());
                    }
                });
            }
            return sent;
        } catch (JsonProcessingException e) {
            this.logger.logf(LogMessage.UNKNOWN_ERROR, "Gateway: alert %s not written: %s", key, e);
            return null;
        }
    }
}
