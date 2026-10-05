package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import group.gnometrading.logging.Logger;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SessionStatusReporterTest {

    private record Received(String method, String query, String apiKey, String body) {}

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private SessionStatusReporter reporterRespondingWith(int... statuses) throws Exception {
        AtomicInteger call = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/strategy-sessions", exchange -> {
            received.add(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getQuery(),
                    exchange.getRequestHeaders().getFirst("x-api-key"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            int status = statuses[Math.min(call.getAndIncrement(), statuses.length - 1)];
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new SessionStatusReporter(base, "secret-key", mock(Logger.class));
    }

    @Test
    void testSendsConditionalRunningPatch() throws Exception {
        assertTrue(reporterRespondingWith(200).reportRunning("abc-123"));

        assertEquals(1, received.size());
        Received request = received.get(0);
        assertEquals("PATCH", request.method());
        assertEquals("sessionId=abc-123", request.query());
        assertEquals("secret-key", request.apiKey());
        assertEquals("{\"status\":\"RUNNING\",\"expectedStatus\":[\"SUBMITTED\",\"STARTING\"]}", request.body());
    }

    @Test
    void testConflictMeansStoppedAndIsNotRetried() throws Exception {
        assertFalse(reporterRespondingWith(409).reportRunning("abc-123"));

        assertEquals(1, received.size());
    }

    @Test
    void testRetriesServerErrors() throws Exception {
        assertTrue(reporterRespondingWith(500, 200).reportRunning("abc-123"));

        assertEquals(2, received.size());
    }

    @Test
    void testGivesUpWithoutThrowingWhenRegistryIsUnreachable() throws Exception {
        SessionStatusReporter reporter = reporterRespondingWith(200);
        server.stop(0);
        server = null;

        assertFalse(reporter.reportRunning("abc-123"));
    }
}
