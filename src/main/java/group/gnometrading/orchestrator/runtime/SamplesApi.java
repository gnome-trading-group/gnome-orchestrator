package group.gnometrading.orchestrator.runtime;

import group.gnometrading.RegistryConnection;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.RetryableHTTPClient;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.ViewString;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Where an order gateway or shard posts its samples: the samples API ({@code samples.api.url}, the host alone, as
 * {@code registry.url} is), behind the registry's API key.
 */
public final class SamplesApi {

    private static final GnomeString PATH = new ViewString("/api/samples");
    private static final int REQUEST_HEADER_BYTES = 4 * 1024;
    // A post that can't go through promptly is retried at the next one; the poster's thread does nothing else.
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int RESPONSE_TIMEOUT_MS = 5_000;

    private SamplesApi() {}

    /**
     * A sender on a connection of its own (the client isn't thread-safe), failing fast rather than retrying inside the
     * client; null when there is no API key (a local run), so nothing is posted.
     */
    public static SamplePoster.Sender sender(final String host, final String apiKey) {
        if (apiKey == null || apiKey.isEmpty() || host == null || host.isEmpty()) {
            return null;
        }
        final RegistryConnection connection = new RegistryConnection(
                host,
                apiKey,
                RetryableHTTPClient.builder()
                        .withMaxRetries(0)
                        .withHttpClient(HTTPClient.builder()
                                .withConnectTimeout(CONNECT_TIMEOUT_MS)
                                .withResponseTimeout(RESPONSE_TIMEOUT_MS)
                                .withWriteBufferSize(SamplePoster.MAX_POST_BYTES + REQUEST_HEADER_BYTES))
                        .build());
        return (body, length) -> connection.tryPost(PATH, body, length);
    }

    /**
     * A sender appending each post as one line to {@code path}, for a run with no samples API to read its samples from
     * (a load test); never fails a post it wrote.
     */
    public static SamplePoster.Sender toFile(final String path) {
        final FileOutputStream out;
        try {
            out = new FileOutputStream(path, true);
        } catch (IOException e) {
            throw new IllegalArgumentException("Samples can't be written to " + path + ": " + e.getMessage(), e);
        }
        return (body, length) -> {
            try {
                out.write(body, 0, length);
                out.write('\n');
                out.flush();
                return 200;
            } catch (IOException e) {
                return RegistryConnection.NO_RESPONSE;
            }
        };
    }
}
