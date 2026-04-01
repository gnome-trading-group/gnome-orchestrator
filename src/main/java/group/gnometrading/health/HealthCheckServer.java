package group.gnometrading.health;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class HealthCheckServer {

    static {
        Logger.getLogger("com.sun.net.httpserver").setLevel(Level.OFF);
    }

    private final HttpServer server;

    public HealthCheckServer(int port, BooleanSupplier healthCheck) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/health", exchange -> {
            boolean healthy = healthCheck.getAsBoolean();
            int status = healthy ? 200 : 503;
            boolean isHead = "HEAD".equals(exchange.getRequestMethod());
            byte[] body = healthy ? "OK".getBytes() : "UNHEALTHY".getBytes();
            exchange.sendResponseHeaders(status, isHead ? -1 : body.length);
            if (!isHead) {
                exchange.getResponseBody().write(body);
            }
            exchange.getResponseBody().close();
        });
        this.server.setExecutor(null);
    }

    public void start() {
        this.server.start();
    }
}
