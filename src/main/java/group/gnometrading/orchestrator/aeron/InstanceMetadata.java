package group.gnometrading.orchestrator.aeron;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Reads this EC2 instance's metadata (IMDSv2: a token, then the value), as EC2 sessions and host-networked or
 * bridge-networked tasks on a host whose metadata hop limit allows them see the instance's. Off EC2 every read fails.
 */
final class InstanceMetadata {

    private static final String IMDS = "http://169.254.169.254/latest";
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final HttpClient http;
    private final String base;

    InstanceMetadata() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), IMDS);
    }

    InstanceMetadata(final HttpClient http, final String base) {
        this.http = http;
        this.base = base;
    }

    /** The values at these {@code meta-data/} paths, in order, under one token. Throws when any read fails. */
    String[] read(final String... paths) throws Exception {
        final String token = send(HttpRequest.newBuilder(URI.create(this.base + "/api/token"))
                .header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                .PUT(HttpRequest.BodyPublishers.noBody()));
        final String[] values = new String[paths.length];
        for (int i = 0; i < paths.length; i++) {
            values[i] = send(HttpRequest.newBuilder(URI.create(this.base + "/meta-data/" + paths[i]))
                    .header("X-aws-ec2-metadata-token", token)
                    .GET());
        }
        return values;
    }

    private String send(final HttpRequest.Builder request) throws Exception {
        final HttpResponse<String> response =
                this.http.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body().trim();
    }
}
