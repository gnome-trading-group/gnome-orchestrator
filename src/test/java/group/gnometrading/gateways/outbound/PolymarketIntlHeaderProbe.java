package group.gnometrading.gateways.outbound;

import group.gnometrading.gateways.credentials.PolymarketIntlCredentials;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlAuthHeaders;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Whether Polymarket International's per-signer rate limits are enforced or still warning-only: cancels an order id
 * that can't exist and reads the open orders, printing only the status and the rate-limit headers. Changes nothing on
 * the account. Credentials come from the dev secret and are never printed.
 */
public final class PolymarketIntlHeaderProbe {

    private static final String CLOB = "https://clob.polymarket.com";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private PolymarketIntlHeaderProbe() {}

    public static void main(final String[] args) throws Exception {
        final PolymarketIntlCredentials creds = credentials();
        final PolymarketIntlAuthHeaders auth = new PolymarketIntlAuthHeaders(
                creds.apiKey(), creds.secret(), creds.passphrase(), creds.signerAddress());

        final byte[] cancel = ("{\"orderID\":\"0x" + "00".repeat(32) + "\"}").getBytes(StandardCharsets.UTF_8);
        auth.sign("DELETE", "/order", cancel, 0, cancel.length);
        print("DELETE /order (an id that can't exist)", send(auth, "DELETE", "/order", cancel));

        auth.sign("GET", "/data/orders", null, 0, 0);
        print("GET /data/orders", send(auth, "GET", "/data/orders", null));
    }

    private static HttpResponse<String> send(
            final PolymarketIntlAuthHeaders auth, final String method, final String path, final byte[] body)
            throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(CLOB + path))
                .timeout(Duration.ofSeconds(15))
                .header("POLY_API_KEY", auth.apiKey())
                .header("POLY_SIGNATURE", auth.signature())
                .header("POLY_TIMESTAMP", auth.timestamp())
                .header("POLY_PASSPHRASE", auth.passphrase())
                .header("POLY_ADDRESS", auth.address())
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void print(final String label, final HttpResponse<String> response) {
        System.out.println("== " + label + " -> " + response.statusCode());
        for (final Map.Entry<String, List<String>> header :
                response.headers().map().entrySet()) {
            final String name = header.getKey().toLowerCase();
            if (name.contains("ratelimit") || name.contains("retry-after") || name.contains("rate-limit")) {
                System.out.println("   " + header.getKey() + ": " + header.getValue());
            }
        }
        final String body = response.body();
        System.out.println("   body: " + (body.length() > 200 ? body.substring(0, 200) + "..." : body));
    }

    private static PolymarketIntlCredentials credentials() {
        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(ProfileCredentialsProvider.create("dev"))
                .build()) {
            return PolymarketIntlCredentials.fromJson(client.getSecretValue(GetSecretValueRequest.builder()
                            .secretId("gnome/exchange-credentials/polymarket-intl")
                            .build())
                    .secretString());
        }
    }
}
