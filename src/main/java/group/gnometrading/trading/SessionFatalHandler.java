package group.gnometrading.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import group.gnometrading.gateways.ProcessExit;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import org.agrona.ErrorHandler;

/**
 * The one way a session ends on an error: an agent failing, a gateway that can't reconnect, a startup that can't
 * finish, or an exception on any other thread. Before exiting it says why, twice: an alert, so someone is told at once
 * rather than when the session's heartbeat goes quiet, and the session's failure reason, which the session monitor
 * keeps when the instance goes. Both share a few seconds; neither may keep the process from exiting.
 *
 * <p>An error while the session is already shutting down (an operator's stop: sockets closing under their readers) is
 * the stop itself, so it exits without saying anything.
 */
public final class SessionFatalHandler implements ProcessExit {

    static final Duration REPORT_BUDGET = Duration.ofSeconds(3);
    private static final int MAX_FRAMES = 8;
    private static final int MAX_REASON = 500;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger logger;
    private final String sessionId;
    private final int strategyId;
    private final String mode;
    private final URI alertsApi;
    private final URI registry;
    private final String apiKey;
    private final HttpClient http;
    private final IntConsumer halt;
    private final AtomicBoolean exiting = new AtomicBoolean();
    private volatile boolean shuttingDown;

    /**
     * @param alertsApi the alerts API's base URL, or null when it isn't known (an alert is then skipped)
     * @param halt ends the process; {@code System::exit} outside tests
     */
    public SessionFatalHandler(
            final Logger logger,
            final String sessionId,
            final int strategyId,
            final String mode,
            final URI alertsApi,
            final URI registry,
            final String apiKey,
            final IntConsumer halt) {
        this.logger = logger;
        this.sessionId = sessionId;
        this.strategyId = strategyId;
        this.mode = mode;
        this.alertsApi = alertsApi;
        this.registry = registry;
        this.apiKey = apiKey;
        this.halt = halt;
        this.http = HttpClient.newBuilder().connectTimeout(REPORT_BUDGET).build();
    }

    /** From here on an error is part of the stop. */
    public void markShuttingDown() {
        this.shuttingDown = true;
    }

    /** For the session's own agents. */
    public ErrorHandler errorHandler() {
        return error -> exit(1, error);
    }

    @Override
    public void exit(final int status, final Throwable cause) {
        // Errors tend to arrive together (a gateway and the agents behind it); the first one says why.
        if (!exiting.compareAndSet(false, true)) {
            return;
        }
        logger.logf(LogMessage.FATAL_ERROR_EXITING, "Session error: %s", cause);
        if (!shuttingDown) {
            report(cause);
        }
        halt.accept(status);
    }

    /** Ends the process without reporting anything: for a session that should never have started. */
    public void exitWithoutReport(final int status, final String why) {
        if (!exiting.compareAndSet(false, true)) {
            return;
        }
        logger.logf(LogMessage.DEBUG, "Exiting: %s", why);
        halt.accept(status);
    }

    private void report(final Throwable cause) {
        final List<CompletableFuture<?>> sent = new ArrayList<>(2);
        try {
            if (alertsApi != null) {
                sent.add(send(alertsApi.resolve("alerts"), "POST", alertBody(cause)));
            }
            final ObjectNode failure = MAPPER.createObjectNode().put("failureReason", reason(cause));
            sent.add(send(
                    registry.resolve(
                            "/api/strategy-sessions?sessionId=" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)),
                    "PATCH",
                    failure.toString()));
            CompletableFuture.allOf(sent.toArray(new CompletableFuture<?>[0]))
                    .get(REPORT_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Couldn't report the session's failure: %s", e.toString());
        }
    }

    private CompletableFuture<HttpResponse<Void>> send(final URI uri, final String method, final String body) {
        return http.sendAsync(
                HttpRequest.newBuilder(uri)
                        .timeout(REPORT_BUDGET)
                        .header("x-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }

    String alertBody(final Throwable cause) {
        final StringBuilder trace = new StringBuilder(reason(cause));
        final StackTraceElement[] frames = cause.getStackTrace();
        for (int i = 0; i < Math.min(MAX_FRAMES, frames.length); i++) {
            trace.append("\n    at ").append(frames[i]);
        }
        return MAPPER.createObjectNode()
                .put("type", "FATAL_ERROR")
                .put("key", "FATAL_ERROR:" + sessionId)
                .put("title", "Session " + sessionId + " hit a fatal error")
                .put("body", trace.toString())
                .put("sessionId", sessionId)
                .put("strategyId", strategyId)
                .put("mode", mode)
                .toString();
    }

    /** The error and, when it wraps another (a child orchestrator failing to build), what it wraps. */
    static String reason(final Throwable cause) {
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        final String text = describe(cause) + (root == cause ? "" : " (caused by " + describe(root) + ")");
        return text.length() <= MAX_REASON ? text : text.substring(0, MAX_REASON);
    }

    private static String describe(final Throwable error) {
        return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }
}
