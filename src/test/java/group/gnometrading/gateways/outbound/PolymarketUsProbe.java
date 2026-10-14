package group.gnometrading.gateways.outbound;

import group.gnometrading.gateways.credentials.PolymarketUsCredentials;
import group.gnometrading.gateways.outbound.exchanges.polymarket.us.PolymarketUsAuthSigner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Phase 0 checks of the Polymarket US retail API, before any gateway code: what its REST answers and private
 * WebSocket actually carry. Credentials come from the dev secret and are never printed.
 *
 * <p>Modes ({@code -Dmode}): {@code get} prints {@code -Dpath}; {@code post} sends {@code -Dbody} to {@code -Dpath};
 * {@code ws} subscribes the private socket to {@code -Dslug} for {@code -Dseconds}.
 */
public final class PolymarketUsProbe {

    private static final String API = "https://api.polymarket.us";
    private static final String PRIVATE_WS = "wss://api.polymarket.us/v1/ws/private";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private PolymarketUsProbe() {}

    public static void main(final String[] args) throws Exception {
        final PolymarketUsCredentials creds = credentials();
        final PolymarketUsAuthSigner signer = new PolymarketUsAuthSigner(creds.apiKey(), creds.privateKey());
        final String mode = System.getProperty("mode", "get");
        switch (mode) {
            case "get" -> send(signer, "GET", System.getProperty("path"), null);
            case "post" -> send(signer, "POST", System.getProperty("path"), System.getProperty("body", "{}"));
            case "ws" -> privateSocket(signer, System.getProperty("slug"), Integer.getInteger("seconds", 20));
            default -> throw new IllegalArgumentException("unknown -Dmode=" + mode);
        }
    }

    private static void send(
            final PolymarketUsAuthSigner signer, final String method, final String path, final String body)
            throws Exception {
        final String signedPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        signer.sign(System.currentTimeMillis(), method, signedPath);
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(15))
                .header(PolymarketUsAuthSigner.ACCESS_KEY_HEADER, signer.apiKey())
                .header(PolymarketUsAuthSigner.TIMESTAMP_HEADER, signer.timestamp())
                .header(PolymarketUsAuthSigner.SIGNATURE_HEADER, signer.signature());
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        final HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        System.out.println(method + " " + path + (body == null ? "" : " body=" + body) + "\n  -> "
                + response.statusCode() + ": " + response.body());
    }

    private static void privateSocket(final PolymarketUsAuthSigner signer, final String slug, final int seconds)
            throws Exception {
        signer.sign(System.currentTimeMillis(), "GET", "/v1/ws/private");
        final WebSocket ws = HTTP.newWebSocketBuilder()
                .header(PolymarketUsAuthSigner.ACCESS_KEY_HEADER, signer.apiKey())
                .header(PolymarketUsAuthSigner.TIMESTAMP_HEADER, signer.timestamp())
                .header(PolymarketUsAuthSigner.SIGNATURE_HEADER, signer.signature())
                .buildAsync(URI.create(PRIVATE_WS), new Printer())
                .join();
        for (final String type : System.getProperty("types", "ORDER,ORDER_SNAPSHOT,ACCOUNT_BALANCE")
                .split(",")) {
            final String subscribe = "{\"subscribe\":{\"requestId\":\"probe-" + type.toLowerCase()
                    + "\",\"subscriptionType\":\"SUBSCRIPTION_TYPE_" + type + "\",\"marketSlugs\":[\"" + slug + "\"]}}";
            ws.sendText(subscribe, true).join();
            System.out.println("WS sent " + subscribe);
        }
        Thread.sleep(seconds * 1_000L);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private static final class Printer implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(final WebSocket webSocket) {
            System.out.println("WS opened");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onPing(final WebSocket webSocket, final java.nio.ByteBuffer message) {
            System.out.println("WS ping");
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(final WebSocket webSocket, final CharSequence data, final boolean last) {
            this.partial.append(data);
            if (last) {
                System.out.println("WS " + System.currentTimeMillis() + " " + this.partial);
                this.partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode, final String reason) {
            System.out.println("WS closed " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(final WebSocket webSocket, final Throwable error) {
            System.out.println("WS error " + error);
        }
    }

    private static PolymarketUsCredentials credentials() {
        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(ProfileCredentialsProvider.create("dev"))
                .build()) {
            return PolymarketUsCredentials.fromJson(client.getSecretValue(GetSecretValueRequest.builder()
                            .secretId(tradingSecret())
                            .build())
                    .secretString());
        }
    }

    // Trading keys belong to venue accounts now, so the probe is pointed at one with
    // -Dsecret=gnome/accounts/<label>/trading.
    private static String tradingSecret() {
        final String secret = System.getProperty("secret");
        if (secret == null) {
            throw new IllegalArgumentException("-Dsecret=gnome/accounts/<label>/trading is required");
        }
        return secret;
    }
}
