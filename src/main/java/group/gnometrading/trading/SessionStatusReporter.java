package group.gnometrading.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Tells the registry the session's agents are live, moving it from STARTING to RUNNING. Only the orchestrator
 * knows this; the EC2 instance state only says the machine booted.
 *
 * <p>Uses the JDK client because the registry's own client has no PATCH. The update is conditional on the
 * session still being SUBMITTED or STARTING, so a late report cannot revive a session that was stopped while
 * it was booting.
 */
public final class SessionStatusReporter {

    private static final String RUNNING_BODY =
            "{\"status\":\"RUNNING\",\"expectedStatus\":[\"SUBMITTED\",\"STARTING\"]}";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int ATTEMPTS = 3;
    private static final int CONFLICT = 409;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI baseUri;
    private final String apiKey;
    private final Logger logger;
    private final HttpClient client;

    public SessionStatusReporter(URI baseUri, String apiKey, Logger logger) {
        this.baseUri = baseUri;
        this.apiKey = apiKey;
        this.logger = logger;
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * Reports RUNNING, retrying briefly. Never throws: the agents are already trading, and a failed report
     * only leaves the session displayed as STARTING.
     *
     * @return whether the registry accepted the update
     */
    public boolean reportRunning(String sessionId) {
        URI uri = baseUri.resolve(
                "/api/strategy-sessions?sessionId=" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(RUNNING_BODY))
                .build();

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    logger.logf(LogMessage.DEBUG, "Session %s reported RUNNING", sessionId);
                    return true;
                }
                if (response.statusCode() == CONFLICT && isAlreadyRunning(response.body())) {
                    // The session's first heartbeat got there first: the registry takes a heartbeat as proof of
                    // RUNNING.
                    logger.logf(LogMessage.DEBUG, "Session %s was already RUNNING", sessionId);
                    return true;
                }
                if (response.statusCode() == CONFLICT) {
                    logger.logf(
                            LogMessage.UNKNOWN_ERROR,
                            "Session %s was no longer starting when reporting RUNNING (likely stopped): %s",
                            sessionId,
                            response.body());
                    return false;
                }
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Reporting RUNNING for %s failed (attempt %d): HTTP %d %s",
                        sessionId,
                        attempt,
                        response.statusCode(),
                        response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Reporting RUNNING for %s failed (attempt %d): %s",
                        sessionId,
                        attempt,
                        e.toString());
            }
            if (attempt < ATTEMPTS) {
                try {
                    Thread.sleep(Duration.ofSeconds(attempt).toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    // A 409 names the session's current status.
    private static boolean isAlreadyRunning(String body) {
        try {
            return "RUNNING".equals(MAPPER.readTree(body).path("status").asText());
        } catch (Exception e) {
            return false;
        }
    }
}
