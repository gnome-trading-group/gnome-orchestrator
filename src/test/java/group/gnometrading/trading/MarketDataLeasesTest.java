package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MarketDataLeasesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The market-data API's own example: a live stream, one mid hand-off, one not placed yet. */
    private static final String BODY =
            """
            {"sessionId":"s-1","status":"ACTIVE","mode":"paper","priority":"RESEARCH","expiresAt":1800007200,
             "streams":[
              {"streamId":1,"state":"LIVE","shardId":"use1-1","endpoint":{"privateIp":"10.0.1.5","port":40000},"next":null},
              {"streamId":7,"state":"LIVE","shardId":"use1-1","endpoint":{"privateIp":"10.0.1.5","port":40000},
               "next":{"shardId":"use1-2","state":"WARMING","endpoint":null}},
              {"streamId":3,"state":"UNPLACED","shardId":null,"endpoint":null,"next":null}]}
            """;

    private static MarketDataLeases.ApiKey key(final String... values) {
        return new MarketDataLeases.ApiKey() {
            private int read;
            private String current;

            @Override
            public String get() {
                if (current == null) {
                    current = values[Math.min(read++, values.length - 1)];
                }
                return current;
            }

            @Override
            public void invalidate() {
                current = null;
            }
        };
    }

    @Test
    void readsEveryStreamsPlacementAndMove() throws Exception {
        final MarketDataLeases.Lease lease = MarketDataLeases.parse(MAPPER.readTree(BODY));

        assertEquals("s-1", lease.sessionId());
        assertEquals("ACTIVE", lease.status());
        assertTrue(lease.stream(1).live());
        assertEquals(
                new MarketDataLeases.Endpoint("10.0.1.5", 40000),
                lease.stream(1).endpoint());
        assertNull(lease.stream(1).next());
        assertEquals(
                new MarketDataLeases.Placement("use1-2", "WARMING", null),
                lease.stream(7).next());
        assertFalse(lease.stream(3).live());
        assertNull(lease.stream(3).shardId());
        assertNull(lease.stream(99));
    }

    @Test
    void getsTheSessionsLeaseWithTheApiKeyAndReadsNoLeaseAsNull() throws Exception {
        final AtomicReference<String> key = new AtomicReference<>();
        final AtomicReference<String> path = new AtomicReference<>();
        final HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/collection/leases/", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            path.set(exchange.getRequestURI().getPath());
            final boolean known = path.get().endsWith("/s-1");
            final byte[] body = (known ? BODY : "{\"error\":\"not found\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(known ? 200 : 404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            final MarketDataLeases leases = new MarketDataLeases(
                    HttpClient.newHttpClient(),
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/api"),
                    key("secret-key"),
                    MAPPER);

            assertEquals(3, leases.get("s-1").streams().size());
            assertEquals("secret-key", key.get());
            assertEquals("/api/collection/leases/s-1", path.get());
            assertNull(leases.get("s-2"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRefusedKeyIsReadAgainOnceBeforeGivingUp() throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/collection/leases/", exchange -> {
            final boolean rotated =
                    "new-key".equals(exchange.getRequestHeaders().getFirst("x-api-key"));
            final byte[] body = (rotated ? BODY : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(rotated ? 200 : 403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            final MarketDataLeases leases = new MarketDataLeases(
                    HttpClient.newHttpClient(),
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/api"),
                    key("old-key", "new-key"),
                    MAPPER);

            assertEquals("s-1", leases.get("s-1").sessionId());
        } finally {
            server.stop(0);
        }
    }
}
