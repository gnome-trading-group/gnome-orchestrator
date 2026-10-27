package group.gnometrading.collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Which task and host a shard runs as, for its status: read once at startup. On ECS the task id comes from the task
 * metadata endpoint; elsewhere (a local run, a bare instance) the hostname stands in. Shards use host networking, so
 * their private IP, where sessions will reach them over Aeron, is the instance's.
 */
final class ShardIdentity {

    private static final String METADATA_URI = "ECS_CONTAINER_METADATA_URI_V4";
    private static final String IMDS = "http://169.254.169.254/latest";
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private ShardIdentity() {}

    static ShardStatusWriter.Identity resolve(
            final ObjectMapper mapper, final String shard, final String version, final long startedAtNanos) {
        final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        return new ShardStatusWriter.Identity(
                shard, taskId(http, mapper, System.getenv(METADATA_URI)), privateIp(http), version, startedAtNanos);
    }

    static String taskId(final HttpClient http, final ObjectMapper mapper, final String metadataUri) {
        if (metadataUri != null && !metadataUri.isBlank()) {
            try {
                final String body = get(http, HttpRequest.newBuilder(URI.create(metadataUri + "/task")));
                return taskIdOf(mapper.readTree(body));
            } catch (Exception e) {
                // Falls through to the hostname: a status from an unnamed task is still worth writing.
            }
        }
        return hostname();
    }

    /** The last part of the task's ARN, {@code arn:aws:ecs:<region>:<account>:task/<cluster>/<id>}. */
    static String taskIdOf(final JsonNode task) {
        final String arn = task.path("TaskARN").asText("");
        if (arn.isEmpty()) {
            throw new IllegalArgumentException("Task metadata has no TaskARN");
        }
        return arn.substring(arn.lastIndexOf('/') + 1);
    }

    private static String privateIp(final HttpClient http) {
        try {
            final String token = send(
                    http,
                    HttpRequest.newBuilder(URI.create(IMDS + "/api/token"))
                            .header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                            .PUT(HttpRequest.BodyPublishers.noBody()));
            return get(
                    http,
                    HttpRequest.newBuilder(URI.create(IMDS + "/meta-data/local-ipv4"))
                            .header("X-aws-ec2-metadata-token", token));
        } catch (Exception e) {
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (Exception unknown) {
                return null;
            }
        }
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String get(final HttpClient http, final HttpRequest.Builder request) throws Exception {
        return send(http, request.GET());
    }

    private static String send(final HttpClient http, final HttpRequest.Builder request) throws Exception {
        final HttpResponse<String> response =
                http.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body().trim();
    }
}
