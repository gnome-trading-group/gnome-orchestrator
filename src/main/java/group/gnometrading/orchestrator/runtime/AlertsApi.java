package group.gnometrading.orchestrator.runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * gnome-registry's alerts ingest API ({@code POST <url>alerts}, the registry's API key), which sessions and order
 * gateways raise their alerts through. Sends asynchronously, with a short timeout. Allocates; never for the order path.
 */
public final class AlertsApi {

    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final URI url;
    private final String apiKey;
    private final HttpClient http;

    /** @param url the API's base URL, or null when it isn't known: alerts are then skipped */
    public AlertsApi(final URI url, final String apiKey) {
        this.url = url;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /** @param url the API's base URL as configured, with or without its trailing slash; null or empty when unknown */
    public static AlertsApi of(final String url, final String apiKey) {
        if (url == null || url.isBlank()) {
            return new AlertsApi(null, apiKey);
        }
        return new AlertsApi(URI.create(url.endsWith("/") ? url : url + "/"), apiKey);
    }

    /** @return null when there's no API to send it to */
    public CompletableFuture<HttpResponse<String>> post(final String body) {
        if (this.url == null) {
            return null;
        }
        return this.http.sendAsync(
                HttpRequest.newBuilder(this.url.resolve("alerts"))
                        .timeout(TIMEOUT)
                        .header("x-api-key", this.apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
