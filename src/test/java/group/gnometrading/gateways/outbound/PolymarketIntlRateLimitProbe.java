package group.gnometrading.gateways.outbound;

import group.gnometrading.gateways.credentials.PolymarketIntlCredentials;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlAuthHeaders;
import group.gnometrading.gateways.ratelimit.VenueRateLimiter;
import group.gnometrading.gateways.ratelimit.VenueRateLimiters;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.strings.ViewString;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.SystemNanoClock;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Polymarket International's per-signer cancel bucket against our limiter: {@code -Dthreads} threads, each with its
 * own HTTP client as each listing's gateway has, cancel an order id that can't exist {@code -Dcount} times through one
 * shared limiter with the rate-limit headers captured. Places and cancels nothing real. Prints what the venue answered
 * and the lowest Poly-RateLimit-Remaining it reported. Refuses to run from a region Polymarket blocks.
 */
public final class PolymarketIntlRateLimitProbe {

    private static final String CLOB_HOST = "clob.polymarket.com";
    private static final ViewString ORDER_PATH = new ViewString("/order");
    private static final int REMAINING = 1;

    private PolymarketIntlRateLimitProbe() {}

    public static void main(final String[] args) throws Exception {
        requireNotGeoblocked();
        final int threads = Integer.getInteger("threads", 8);
        final int count = Integer.getInteger("count", 20);
        final PolymarketIntlCredentials creds = credentials();
        final VenueRateLimiter limiter = new VenueRateLimiters(
                        new ConsoleLogger(new SystemEpochNanoClock()), SystemNanoClock.INSTANCE, new SystemEpochClock())
                .forAccount(VenueRateLimits.polymarketIntl(), creds.signerAddress());
        final byte[] body = ("{\"orderID\":\"0x" + "00".repeat(32) + "\"}").getBytes(StandardCharsets.UTF_8);

        final Map<String, AtomicInteger> statuses = new ConcurrentSkipListMap<>();
        final AtomicLong lowestRemaining = new AtomicLong(Long.MAX_VALUE);
        final Thread[] workers = new Thread[threads];
        final long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                try {
                    final HTTPClient client = OrderEntryHttpClients.create(limiter);
                    final PolymarketIntlAuthHeaders auth = new PolymarketIntlAuthHeaders(
                            creds.apiKey(), creds.secret(), creds.passphrase(), creds.signerAddress());
                    for (int i = 0; i < count; i++) {
                        auth.sign("DELETE", "/order", body, 0, body.length);
                        final HTTPResponse response = client.delete(
                                HTTPProtocol.HTTPS,
                                CLOB_HOST,
                                ORDER_PATH,
                                body,
                                body.length,
                                "POLY_API_KEY",
                                auth.apiKey(),
                                "POLY_SIGNATURE",
                                auth.signature(),
                                "POLY_TIMESTAMP",
                                auth.timestamp(),
                                "POLY_PASSPHRASE",
                                auth.passphrase(),
                                "POLY_ADDRESS",
                                auth.address());
                        final String key = response.getStatusCode() + (response.isRefusedLocally() ? " (ours)" : "");
                        statuses.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                        final long remaining = response.headerAsLong(REMAINING, Long.MAX_VALUE);
                        lowestRemaining.accumulateAndGet(remaining, Math::min);
                    }
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            });
            workers[t].start();
        }
        for (final Thread worker : workers) {
            worker.join();
        }
        final long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("#### " + threads * count + " cancels in " + millis + " ms (about "
                + threads * count * 1000L / Math.max(1, millis) + "/s): " + statuses);
        System.out.println("#### lowest Poly-RateLimit-Remaining seen: "
                + (lowestRemaining.get() == Long.MAX_VALUE ? "none" : lowestRemaining.get()));
    }

    /** Polymarket refuses trading from some regions; this only runs where it says it doesn't. */
    private static void requireNotGeoblocked() throws Exception {
        final HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("https://polymarket.com/api/geoblock"))
                                .timeout(Duration.ofSeconds(10))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        System.out.println("geoblock: " + response.body());
        if (!response.body().replace(" ", "").contains("\"blocked\":false")) {
            throw new IllegalStateException("Polymarket reports this region as blocked; not running");
        }
    }

    private static PolymarketIntlCredentials credentials() {
        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(ProfileCredentialsProvider.create("dev"))
                .build()) {
            return PolymarketIntlCredentials.fromJson(client.getSecretValue(GetSecretValueRequest.builder()
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
