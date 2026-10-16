package group.gnometrading.trading;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * What a session says about itself as it ends: alerts to the alerts API and updates to its own registry row. Uses the
 * JDK client because the registry's own client has no PATCH.
 */
public final class SessionReports {

    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final String sessionId;
    private final URI alertsApi;
    private final URI registry;
    private final String apiKey;
    private final HttpClient http;

    /** @param alertsApi the alerts API's base URL, or null when it isn't known (alerts are then skipped) */
    public SessionReports(final String sessionId, final URI alertsApi, final URI registry, final String apiKey) {
        this.sessionId = sessionId;
        this.alertsApi = alertsApi;
        this.registry = registry;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    public String sessionId() {
        return sessionId;
    }

    /** @return null when there's no alerts API to send it to */
    public CompletableFuture<HttpResponse<String>> alert(final String body) {
        return alertsApi == null ? null : send(alertsApi.resolve("alerts"), "POST", body);
    }

    public CompletableFuture<HttpResponse<String>> updateSession(final String body) {
        return send(
                registry.resolve(
                        "/api/strategy-sessions?sessionId=" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)),
                "PATCH",
                body);
    }

    private CompletableFuture<HttpResponse<String>> send(final URI uri, final String method, final String body) {
        return http.sendAsync(
                HttpRequest.newBuilder(uri)
                        .timeout(TIMEOUT)
                        .header("x-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
