package group.gnometrading.orchestrator.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HostZoneTest {

    private static final String TOKEN = "token-1";

    private HttpServer server;
    private final AtomicReference<String> tokenAsked = new AtomicReference<>();

    // An instance metadata service: a token for a PUT, then values for GETs that carry it.
    private InstanceMetadata metadata(final Map<String, String> values) throws Exception {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/latest/api/token", exchange -> {
            this.tokenAsked.set(exchange.getRequestMethod());
            final byte[] body = TOKEN.getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        this.server.createContext("/latest/meta-data/", exchange -> {
            final String path = exchange.getRequestURI().getPath().substring("/latest/meta-data/".length());
            final String value = values.get(path);
            final boolean authorized = TOKEN.equals(exchange.getRequestHeaders().getFirst("X-aws-ec2-metadata-token"));
            if (value == null || !authorized) {
                exchange.sendResponseHeaders(authorized ? 404 : 401, -1);
                exchange.close();
                return;
            }
            final byte[] body = (value + "\n").getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        this.server.start();
        return new InstanceMetadata(
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + this.server.getAddress().getPort() + "/latest");
    }

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    @Test
    void readsTheZonesNameAndIdUnderOneToken() throws Exception {
        final HostZone zone = HostZone.of(metadata(Map.of(
                "placement/availability-zone", "us-east-2b",
                "placement/availability-zone-id", "use2-az2")));

        assertEquals(new HostZone("us-east-2b", "use2-az2"), zone);
        assertEquals("PUT", this.tokenAsked.get());
    }

    @Test
    void hasNoZoneWhenEitherValueIsMissing() throws Exception {
        assertNull(HostZone.of(metadata(Map.of("placement/availability-zone", "us-east-2b"))));
    }

    @Test
    void hasNoZoneOffEc2() {
        assertNull(HostZone.of(new InstanceMetadata(HttpClient.newHttpClient(), "http://127.0.0.1:1/latest")));
    }
}
