package group.gnometrading.health;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.BooleanSupplier;

public final class HealthCheckServer {

    private final HttpServer server;

    public HealthCheckServer(int port, BooleanSupplier healthCheck) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/health", exchange -> {
            boolean healthy = healthCheck.getAsBoolean();
            int status = healthy ? 200 : 503;
            byte[] body = healthy ? "OK".getBytes() : "UNHEALTHY".getBytes();
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        this.server.setExecutor(null);
    }

    public void start() {
        this.server.start();
    }
}
