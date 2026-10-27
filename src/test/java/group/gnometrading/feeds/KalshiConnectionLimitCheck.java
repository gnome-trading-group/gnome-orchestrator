package group.gnometrading.feeds;

import group.gnometrading.gateways.credentials.KalshiCredentials;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Settles whether Kalshi's market-data connection limit is per key or per account (collection-sharding decision 10).
 * Opens Kalshi websockets with the first key until one is refused, keeps them open, then opens more with the second
 * key: if the second key gets its own full allowance the limit is per key, if it is refused at once it is per account.
 * Subscribes to nothing. Manual: never part of {@code mvn test}.
 *
 * <p>{@code -Dsecrets=gnome/market-data/kalshi/md-1,gnome/market-data/kalshi/md-2} (two keys on one account, or one to
 * find a single key's limit), {@code -Durl=} the websocket (default prod), {@code -Dmax=} the most to try per key
 * (default 300), {@code -DperSecond=} connects a second (default 2, as the feeds pace Kalshi, so its connect rate limit
 * isn't mistaken for the connection limit), {@code -Dregion=} Secrets Manager's (default us-east-1). Needs AWS
 * credentials for the secrets.
 */
public final class KalshiConnectionLimitCheck {

    private static final String PATH = "/trade-api/ws/v2";

    private KalshiConnectionLimitCheck() {}

    public static void main(final String[] args) throws Exception {
        final String[] secretNames = System.getProperty("secrets", "").split(",");
        final URI url = URI.create(System.getProperty("url", "wss://external-api-ws.kalshi.com" + PATH));
        final int max = Integer.getInteger("max", 300);
        final long spacingMillis = 1000L / Integer.getInteger("perSecond", 2);
        final HttpClient http = HttpClient.newHttpClient();
        final List<WebSocket> open = new ArrayList<>();
        try (SecretsManagerClient secrets = SecretsManagerClient.builder()
                .region(Region.of(System.getProperty("region", "us-east-1")))
                .build()) {
            for (String secretName : secretNames) {
                if (secretName.isBlank()) {
                    continue;
                }
                final KalshiCredentials key = KalshiCredentials.fromJson(
                        secrets.getSecretValue(r -> r.secretId(secretName)).secretString());
                int opened = 0;
                String refusal = "none: reached -Dmax";
                for (; opened < max; opened++) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(spacingMillis);
                        open.add(connect(http, url, key));
                    } catch (CompletionException e) {
                        refusal = String.valueOf(e.getCause());
                        break;
                    }
                }
                System.out.printf(
                        "%s: opened %d more (%d held in total); refused with %s%n",
                        secretName, opened, open.size(), refusal);
            }
        } finally {
            for (WebSocket socket : open) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
            TimeUnit.SECONDS.sleep(2);
        }
    }

    private static WebSocket connect(final HttpClient http, final URI url, final KalshiCredentials key)
            throws Exception {
        final String timestamp = Long.toString(System.currentTimeMillis());
        final Signature signature = Signature.getInstance("RSASSA-PSS");
        signature.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
        signature.initSign(key.privateKey());
        signature.update((timestamp + "GET" + PATH).getBytes(StandardCharsets.UTF_8));
        return http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .header("KALSHI-ACCESS-KEY", key.apiKey())
                .header("KALSHI-ACCESS-TIMESTAMP", timestamp)
                .header("KALSHI-ACCESS-SIGNATURE", Base64.getEncoder().encodeToString(signature.sign()))
                .buildAsync(url, new WebSocket.Listener() {})
                .join();
    }
}
