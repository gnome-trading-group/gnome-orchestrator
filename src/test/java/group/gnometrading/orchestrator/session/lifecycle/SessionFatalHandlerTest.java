package group.gnometrading.orchestrator.session.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.logging.NullLogger;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionFatalHandlerTest {

    private record Received(String method, String path, String query, String apiKey, String body) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final List<Integer> exits = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private URI base;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(),
                    exchange.getRequestHeaders().getFirst("x-api-key"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            final byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(exchange.getRequestMethod().equals("POST") ? 202 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private SessionFatalHandler handler(final boolean withAlerts) {
        return new SessionFatalHandler(
                new NullLogger(),
                new SessionReports("s1", withAlerts ? base.resolve("/alerts-api/") : null, base, "secret-key"),
                7,
                "live",
                exits::add);
    }

    @Test
    void raisesAnAlertAndRecordsWhyBeforeExiting() throws Exception {
        handler(true)
                .exit(
                        1,
                        new RuntimeException(
                                "Failed to create child orchestrator",
                                new IllegalStateException("No venue account bound for exchange 2")));

        awaitExits(1);
        assertEquals(List.of(1), exits);
        assertEquals(2, received.size());
        final Received alert = received.stream()
                .filter(r -> r.method().equals("POST"))
                .findFirst()
                .orElseThrow();
        assertEquals("/alerts-api/alerts", alert.path());
        assertEquals("secret-key", alert.apiKey());
        final JsonNode body = mapper.readTree(alert.body());
        assertEquals("FATAL_ERROR", body.get("type").asText());
        assertEquals("FATAL_ERROR:s1", body.get("key").asText());
        assertEquals("s1", body.get("sessionId").asText());
        assertEquals(7, body.get("strategyId").asInt());
        assertEquals("live", body.get("mode").asText());
        assertTrue(body.get("body").asText().contains("caused by IllegalStateException: No venue account bound"));
        assertTrue(body.get("body").asText().contains("\n    at "), "the top of the stack");

        final Received failure = received.stream()
                .filter(r -> r.method().equals("PATCH"))
                .findFirst()
                .orElseThrow();
        assertEquals("/api/strategy-sessions", failure.path());
        assertEquals("sessionId=s1", failure.query());
        assertEquals(
                "RuntimeException: Failed to create child orchestrator (caused by IllegalStateException: No venue"
                        + " account bound for exchange 2)",
                mapper.readTree(failure.body()).get("failureReason").asText());
    }

    @Test
    void onlyTheFirstErrorIsReported() throws Exception {
        final SessionFatalHandler handler = handler(true);
        handler.exit(1, new IllegalStateException("first"));
        handler.errorHandler().onError(new IllegalStateException("second"));

        awaitExits(1);
        Thread.sleep(100);
        assertEquals(List.of(1), exits);
        assertEquals(2, received.size());
    }

    @Test
    void anErrorDuringAStopExitsQuietly() throws Exception {
        final SessionFatalHandler handler = handler(true);
        handler.markShuttingDown();
        handler.exit(1, new IllegalStateException("socket closed"));

        awaitExits(1);
        assertEquals(List.of(1), exits);
        assertTrue(received.isEmpty(), "the stop itself, not a failure");
    }

    @Test
    void anAgentErrorExitsWhileTheShutdownHookJoinsTheAgentsThread() throws Exception {
        // As System.exit does: the halt runs the shutdown hook, which closes the session by joining every agent's
        // thread, and only then ends the process.
        final AtomicReference<GnomeAgentRunner> runner = new AtomicReference<>();
        final SessionFatalHandler handler = new SessionFatalHandler(
                new NullLogger(), new SessionReports("s1", null, base, "secret-key"), 7, "live", status -> {
                    try {
                        runner.get().close();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    exits.add(status);
                });
        runner.set(new GnomeAgentRunner(
                new GnomeAgent() {
                    @Override
                    public String roleName() {
                        return "failing";
                    }

                    @Override
                    public int doWork() {
                        throw new IllegalStateException("Journal gap");
                    }
                },
                handler.errorHandler()));
        GnomeAgentRunner.startOnThread(runner.get());

        awaitExits(1);
        assertEquals(List.of(1), exits);
    }

    @Test
    void aSessionThatMustNotStartExitsWithoutAnAlert() {
        handler(true).exitWithoutReport(0, "Session s1 is STOPPED; refusing to start");

        assertEquals(List.of(0), exits);
        assertTrue(received.isEmpty());
    }

    private void awaitExits(final int count) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 10_000;
        while (exits.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(count, exits.size(), "the process exits");
    }

    @Test
    void withoutTheAlertsApiItStillRecordsWhy() throws Exception {
        handler(false).exit(1, new IllegalStateException("boom"));

        awaitExits(1);
        assertEquals(List.of(1), exits);
        assertEquals(List.of("PATCH"), received.stream().map(Received::method).toList());
    }
}
