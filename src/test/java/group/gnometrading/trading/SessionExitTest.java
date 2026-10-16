package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.strategies.ExitResult;
import group.gnometrading.strategies.ExitResult.HeldPosition;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionExitTest {

    private record Received(String method, String path, String body) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    // What the session was doing as the process ended, in order.
    private final List<String> steps = new CopyOnWriteArrayList<>();
    private final Queue<Integer> patchStatuses = new ArrayDeque<>();
    private HttpServer server;
    private URI base;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            received.add(new Received(
                    method,
                    exchange.getRequestURI().getPath(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            int status;
            synchronized (patchStatuses) {
                status = method.equals("PATCH") && !patchStatuses.isEmpty() ? patchStatuses.poll() : 200;
            }
            if (method.equals("PATCH")) {
                steps.add("PATCH " + status);
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
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

    private SessionExit sessionExit(SessionReports reports) {
        return new SessionExit(
                new NullLogger(),
                reports,
                7,
                "live",
                List.of(listing(12, "POLYMARKET_INTL", "will-vitality-win")),
                () -> steps.add("close"),
                status -> steps.add("halt " + status));
    }

    private SessionReports reports() {
        return new SessionReports("s1", base.resolve("/alerts-api/"), base, "secret-key");
    }

    @Test
    void reportsTheExitAndMarksTheSessionStoppingBeforeClosing() throws Exception {
        sessionExit(reports()).exit(new ExitResult("match went live", false, false, List.of()));

        assertEquals(List.of("PATCH 200", "close", "halt 0"), steps);
        JsonNode update = body("PATCH");
        assertEquals("STOPPING", update.get("status").asText());
        assertEquals("match went live", update.get("stopReason").asText());
        assertEquals("strategy", update.get("stoppedBy").asText());
        assertEquals(
                List.of("SUBMITTED", "STARTING", "RUNNING"),
                mapper.convertValue(update.get("expectedStatus"), List.class));
        assertEquals("/api/strategy-sessions", received("PATCH").path());

        JsonNode alert = body("POST");
        assertEquals("/alerts-api/alerts", received("POST").path());
        assertEquals("SESSION_EXITED", alert.get("type").asText());
        assertEquals("INFO", alert.get("severity").asText());
        assertEquals("Session s1 exited: match went live", alert.get("title").asText());
        assertEquals("s1", alert.get("sessionId").asText());
        assertEquals(7, alert.get("strategyId").asInt());
    }

    @Test
    void warnsWhenItCouldNotCloseEverythingItWasAskedTo() throws Exception {
        sessionExit(reports())
                .exit(new ExitResult(
                        "done", true, true, List.of(new HeldPosition(12, 2_500_000L, ExitResult.Why.TIMED_OUT))));

        JsonNode alert = body("POST");
        assertEquals("WARNING", alert.get("severity").asText());
        assertTrue(alert.get("title").asText().contains("still holding positions"));
        String text = alert.get("body").asText();
        assertTrue(
                text.contains(
                        "POLYMARKET_INTL will-vitality-win (listing 12): 2.5 (still closing when the time ran out)"),
                text);
        assertTrue(text.contains("Ran out of time"), text);
    }

    @Test
    void keepingPositionsByChoiceIsNotAWarning() throws Exception {
        sessionExit(reports())
                .exit(new ExitResult(
                        "done", false, false, List.of(new HeldPosition(12, -3_000_000L, ExitResult.Why.KEPT))));

        JsonNode alert = body("POST");
        assertEquals("INFO", alert.get("severity").asText());
        assertTrue(alert.get("body").asText().contains("Kept its positions"));
        assertTrue(alert.get("body").asText().contains("(listing 12): -3"));
    }

    @Test
    void saysWhenAPositionWasTooSmallToClose() throws Exception {
        sessionExit(reports())
                .exit(new ExitResult(
                        "done", true, false, List.of(new HeldPosition(12, 220_000L, ExitResult.Why.TOO_SMALL))));

        JsonNode alert = body("POST");
        assertEquals("WARNING", alert.get("severity").asText());
        assertTrue(
                alert.get("body").asText().contains("0.22 (below the venue's minimum order"),
                alert.get("body").asText());
    }

    @Test
    void retriesMarkingItStoppingSoItIsNotLeftToFail() throws Exception {
        patchStatuses.addAll(List.of(500, 200));

        sessionExit(reports()).exit(new ExitResult("done", false, false, List.of()));

        assertEquals(List.of("PATCH 500", "PATCH 200", "close", "halt 0"), steps);
    }

    @Test
    void aSessionAlreadyStoppingIsLeftAsItIs() throws Exception {
        patchStatuses.add(409);

        sessionExit(reports()).exit(new ExitResult("done", false, false, List.of()));

        assertEquals(List.of("PATCH 409", "close", "halt 0"), steps);
    }

    @Test
    void stillClosesAndExitsWhenTheRegistryIsUnreachable() {
        server.stop(0);

        sessionExit(reports()).exit(new ExitResult("done", false, false, List.of()));

        assertEquals(List.of("close", "halt 0"), steps);
    }

    @Test
    void withoutARegistrySessionItOnlyClosesAndExits() {
        sessionExit(null).exit(new ExitResult("done", false, false, List.of()));

        assertEquals(List.of("close", "halt 0"), steps);
        assertTrue(received.isEmpty());
    }

    @Test
    void exitsOnceOnAThreadOfItsOwn() throws Exception {
        CountDownLatch halted = new CountDownLatch(1);
        List<String> threads = new CopyOnWriteArrayList<>();
        SessionExit exit = new SessionExit(
                new NullLogger(),
                null,
                7,
                "live",
                List.of(),
                () -> threads.add(Thread.currentThread().getName()),
                status -> halted.countDown());

        exit.onExitComplete(new ExitResult("done", false, false, List.of()));
        exit.onExitComplete(new ExitResult("again", false, false, List.of()));

        assertTrue(halted.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(List.of("session-exit"), threads);
    }

    private Received received(String method) {
        return received.stream()
                .filter(r -> r.method().equals(method))
                .findFirst()
                .orElseThrow();
    }

    private JsonNode body(String method) throws Exception {
        return mapper.readTree(received(method).body());
    }

    private static Listing listing(int listingId, String exchangeCode, String symbol) {
        return new Listing(
                listingId,
                new Exchange(1, exchangeCode, exchangeCode, "us-east-1", null),
                new Security(1, symbol, null, null, null, "USD", "USD", "USD", false, false, 0, 0, true, 0),
                symbol,
                symbol);
    }
}
