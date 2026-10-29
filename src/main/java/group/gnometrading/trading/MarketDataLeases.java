package group.gnometrading.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a session's market-data lease from the market-data API ({@code GET /collection/leases/{sessionId}}): which
 * shard carries each of its streams, where that shard listens, and whether the stream is live there. The launcher
 * creates the lease; the reconciler places its streams. Off the hot path: every call allocates and blocks.
 */
public final class MarketDataLeases {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Where a shard listens for sessions joining its streams. */
    public record Endpoint(String privateIp, int port) {}

    /** A shard a stream is on, or moving to. {@code endpoint} is null until the shard advertises one. */
    public record Placement(String shardId, String state, Endpoint endpoint) {}

    /**
     * One leased stream: {@code state} is UNPLACED, WARMING or LIVE on {@code shardId}; {@code next} is where it is
     * moving during a hand-off or roll, else null.
     */
    public record LeasedStream(int streamId, String state, String shardId, Endpoint endpoint, Placement next) {
        public boolean live() {
            return "LIVE".equals(state) && endpoint != null;
        }
    }

    public record Lease(String sessionId, String status, List<LeasedStream> streams) {
        /** The stream's entry, or null when the lease doesn't name it. */
        public LeasedStream stream(final int streamId) {
            for (final LeasedStream stream : streams) {
                if (stream.streamId() == streamId) {
                    return stream;
                }
            }
            return null;
        }
    }

    /** The API key, read once and kept until the API refuses it (a rotation). */
    public interface ApiKey {
        String get();

        /** Forgets the key, so the next {@link #get()} reads it again. */
        void invalidate();
    }

    private final HttpClient http;
    private final URI base;
    private final ApiKey apiKey;
    private final ObjectMapper mapper;

    /** {@code base} is the API's URL with its stage, e.g. {@code https://abc.execute-api.us-east-1.amazonaws.com/api}. */
    public MarketDataLeases(final HttpClient http, final URI base, final ApiKey apiKey, final ObjectMapper mapper) {
        this.http = http;
        this.base = base;
        this.apiKey = apiKey;
        this.mapper = mapper;
    }

    /** The session's lease, or null when it has none. Throws on any other failure, for the caller to retry. */
    public Lease get(final String sessionId) throws IOException, InterruptedException {
        HttpResponse<String> response = send(sessionId);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            apiKey.invalidate();
            response = send(sessionId);
        }
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException("GET lease " + sessionId + ": HTTP " + response.statusCode() + " " + response.body());
        }
        return parse(mapper.readTree(response.body()));
    }

    private HttpResponse<String> send(final String sessionId) throws IOException, InterruptedException {
        return http.send(
                HttpRequest.newBuilder(base.resolve(base.getPath() + "/collection/leases/" + sessionId))
                        .header("x-api-key", apiKey.get())
                        .timeout(TIMEOUT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static Lease parse(final JsonNode body) {
        final List<LeasedStream> streams = new ArrayList<>();
        for (final JsonNode stream : body.path("streams")) {
            streams.add(new LeasedStream(
                    stream.path("streamId").asInt(),
                    stream.path("state").asText(""),
                    text(stream.path("shardId")),
                    endpoint(stream.path("endpoint")),
                    placement(stream.path("next"))));
        }
        return new Lease(body.path("sessionId").asText(""), body.path("status").asText(""), List.copyOf(streams));
    }

    private static Placement placement(final JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new Placement(text(node.path("shardId")), text(node.path("state")), endpoint(node.path("endpoint")));
    }

    private static Endpoint endpoint(final JsonNode node) {
        if (node.isMissingNode() || node.isNull() || text(node.path("privateIp")) == null) {
            return null;
        }
        return new Endpoint(node.path("privateIp").asText(), node.path("port").asInt());
    }

    private static String text(final JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
