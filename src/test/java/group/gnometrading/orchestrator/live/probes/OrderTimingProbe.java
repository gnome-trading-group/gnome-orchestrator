package group.gnometrading.orchestrator.live.probes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.lane.LaneVenue;
import group.gnometrading.gateways.account.lane.RequestShape;
import group.gnometrading.gateways.orders.CancelResult;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.gateways.venues.kalshi.KalshiAuthSigner;
import group.gnometrading.gateways.venues.kalshi.KalshiLaneVenue;
import group.gnometrading.gateways.venues.kalshi.KalshiMarket;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlAuthHeaders;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlLaneVenue;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlMarket;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlMarketInfo;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlOrderSigner;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsAuthSigner;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsLaneVenue;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsListing;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsMarket;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsMarketInfo;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPCompletion;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPRequestWriter;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.orchestrator.credentials.KalshiCredentials;
import group.gnometrading.orchestrator.credentials.PolymarketIntlCredentials;
import group.gnometrading.orchestrator.credentials.PolymarketUsCredentials;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * What timing a venue gives back when an order is placed and cancelled: {@code -Drepeats} (3) rounds of one post-only
 * bid far below the market, cancelled once acknowledged, sent through the gateway's own {@link LaneVenue} (so signed and
 * shaped exactly as the lane sends them) on one keep-alive connection. For each answer it prints the status, the local
 * send and answer times, the timing headers it captures, and every body field whose name looks like a time. Ids, keys
 * and every other body value are never printed.
 *
 * <ul>
 *   <li>{@code -Dvenue=kalshi}: {@code -Dmarket=TICKER} (the YES side), {@code -Dsecret} (default
 *       {@code gnome/accounts/steady-heron-01/trading}), {@code -Daws.profile} (dev).
 *   <li>{@code -Dvenue=pmus}: {@code -Dmarket=slug} (the long side), {@code -Dsecret=gnome/accounts/<label>/trading}.
 *       From a US IP.
 *   <li>{@code -Dvenue=pmintl}: {@code -Dmarket=condition:token}, {@code -Dsecret=gnome/accounts/<label>/trading}
 *       (key, signer, funder and signature type all come from it). From outside the US.
 * </ul>
 *
 * <p>{@code -Dprice} (0.01) and {@code -Dsize} (Kalshi and Polymarket US 1, Polymarket the market's minimum) set the
 * bid. Trades real money, so {@code -Dconfirm=yes} is required. A cancel that isn't accepted is reported loudly, and
 * the run stops so nothing more is left resting.
 */
public final class OrderTimingProbe {

    private static final String KALSHI_API = "external-api.kalshi.com";
    private static final String PM_INTL_CLOB = "clob.polymarket.com";
    private static final String PM_US_API = "api.polymarket.us";
    private static final String PM_US_GATEWAY = "gateway.polymarket.us";
    private static final int EXCHANGE_ID = 2;
    private static final long SESSION_SLOT = 1;
    private static final int MAX_PATH = 1024;
    private static final int MAX_BODY = 16 * 1024;
    private static final long ANSWER_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long CONNECT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
    // Captured on every answer, at most HTTPClientBuilder.MAX_CAPTURED_HEADERS; names seen on these venues or common
    // for server-side timing.
    private static final String[] TIMING_HEADERS = {
        "date",
        "x-pm-server-latency",
        "x-pm-latency",
        "server-timing",
        "x-envoy-upstream-service-time",
        "x-response-time",
        "x-runtime",
        "x-ratelimit-remaining",
    };
    private static final Pattern TIME_FIELD =
            Pattern.compile("(?i).*(time|ts|date|latency|created|updated|insert|sent|stamp|_at)$|^ts.*");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int ordersPlaced;
    private static int ordersCancelled;

    private OrderTimingProbe() {}

    /** A venue ready to probe: its lane venue and loaded market, where to send, and how much to bid. */
    private record Target<M>(
            LaneVenue<M> venue, M market, Listing listing, String host, long price, long size, int minAgeSeconds) {}

    public static void main(final String[] args) throws Exception {
        if (!"yes".equals(System.getProperty("confirm"))) {
            throw new IllegalArgumentException("Trades real money: -Dconfirm=yes is required");
        }
        final Logger logger = new ConsoleLogger(new SystemEpochNanoClock());
        final String venue = required("venue");
        final int repeats = Integer.getInteger("repeats", 3);
        final boolean clean =
                switch (venue) {
                    case "kalshi" -> probe(kalshi(logger), repeats);
                    case "pmus" -> probe(polymarketUs(logger), repeats);
                    case "pmintl" -> probe(polymarketIntl(logger), repeats);
                    default -> throw new IllegalArgumentException("-Dvenue is kalshi, pmus or pmintl");
                };
        System.out.println((clean ? "\nDONE" : "\nSTOPPED: see the CANCEL lines above") + ": " + ordersPlaced
                + " placed, " + ordersCancelled + " cancelled");
        System.exit(clean ? 0 : 1);
    }

    // ----- venues -----

    private static Target<KalshiMarket> kalshi(final Logger logger) throws Exception {
        final KalshiCredentials creds = KalshiCredentials.fromJson(secret(
                System.getProperty("aws.profile", "dev"),
                System.getProperty("secret", "gnome/accounts/steady-heron-01/trading")));
        final KalshiLaneVenue venue =
                new KalshiLaneVenue(logger, new KalshiAuthSigner(creds.apiKey(), creds.privateKey()), "maker");
        final String ticker = required("market");
        final Listing listing = listing("KALSHI", ticker + ":yes", ticker);
        final KalshiMarket market = venue.loadMarket(
                listing,
                new HTTPClient(),
                KALSHI_API,
                new KalshiAuthSigner(creds.apiKey(), creds.privateKey()),
                System::currentTimeMillis);
        venue.addSession(SESSION_SLOT, "tmp" + Long.toString(System.currentTimeMillis() / 1000, Character.MAX_RADIX));
        return new Target<>(venue, market, listing, KALSHI_API, price(), size("1"), 0);
    }

    private static Target<PolymarketUsMarket> polymarketUs(final Logger logger) throws Exception {
        final String secretId = required("secret");
        final PolymarketUsCredentials creds =
                PolymarketUsCredentials.fromJson(secret(System.getProperty("aws.profile", "dev"), secretId));
        final PolymarketUsLaneVenue venue =
                new PolymarketUsLaneVenue(logger, new PolymarketUsAuthSigner(creds.apiKey(), creds.privateKey()));
        final String slug = required("market");
        final Listing listing = listing("POLYMARKET_US", slug + ":long", slug);
        final PolymarketUsMarketInfo info = PolymarketUsMarketInfo.load(
                new HTTPClient(), PM_US_GATEWAY, new PolymarketUsListing(listing).marketSlug());
        final PolymarketUsMarket market = new PolymarketUsMarket(listing, info);
        venue.addMarket(EXCHANGE_ID, listing.security().securityId(), market);
        return new Target<>(venue, market, listing, PM_US_API, price(), size("1"), 0);
    }

    private static Target<PolymarketIntlMarket> polymarketIntl(final Logger logger) throws Exception {
        final PolymarketIntlCredentials creds = PolymarketIntlCredentials.fromJson(
                secret(System.getProperty("aws.profile", "dev"), required("secret")));
        final PolymarketIntlLaneVenue venue = new PolymarketIntlLaneVenue(
                logger,
                new PolymarketIntlOrderSigner(
                        creds.ethereumPrivateKey(),
                        creds.signerAddress(),
                        creds.funderAddress(),
                        creds.signatureType()),
                new PolymarketIntlAuthHeaders(
                        creds.apiKey(), creds.secret(), creds.passphrase(), creds.signerAddress()));
        final String spec = required("market");
        final String condition = spec.substring(0, spec.indexOf(':'));
        final Listing listing = listing("POLYMARKET", spec, condition);
        final PolymarketIntlMarketInfo info = PolymarketIntlMarketInfo.load(new HTTPClient(), PM_INTL_CLOB, condition);
        final PolymarketIntlMarket market = PolymarketIntlMarket.of(listing, info);
        venue.addMarket(EXCHANGE_ID, listing.security().securityId(), market);
        final String minSize = Double.toString((double) info.minOrderSize() / Statics.SIZE_SCALING_FACTOR);
        return new Target<>(venue, market, listing, PM_INTL_CLOB, price(), size(minSize), info.minOrderAgeSeconds());
    }

    // ----- the probe -----

    private static <M> boolean probe(final Target<M> target, final int repeats) throws Exception {
        final AsyncHTTPConnections connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTPS, target.host())
                .withConnections(1)
                .captureHeaders(TIMING_HEADERS)
                .build();
        final HTTPConnector connector = new HTTPConnector(List.of(connections), HTTPConnector.Listener.NONE);
        final RequestShape shape = new RequestShape(MAX_PATH);
        final byte[] body = new byte[MAX_BODY];
        System.out.printf(
                "Probing %s: %d round(s), bid %s x %s%n",
                target.host(),
                repeats,
                (double) target.price() / Statics.PRICE_SCALING_FACTOR,
                (double) target.size() / Statics.SIZE_SCALING_FACTOR);
        try (connections) {
            // Opens the connection before the first timed request, so no round pays the TLS handshake.
            acquire(connections, connector);
            for (int round = 1; round <= repeats; round++) {
                if (!round(target, round, connections, connector, shape, body)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static <M> boolean round(
            final Target<M> target,
            final int round,
            final AsyncHTTPConnections connections,
            final HTTPConnector connector,
            final RequestShape shape,
            final byte[] body)
            throws Exception {
        final LaneVenue<M> venue = target.venue();
        final OrderContext ctx = new OrderContext();
        final LaneCommand command = command(target, round);
        ctx.sessionSlot = command.sessionSlot;
        ctx.clientOidCounter = command.clientOidCounter;
        ctx.clientOidStrategyId = command.clientOidStrategyId;
        ctx.exchangeId = command.exchangeId;
        ctx.securityId = command.securityId;
        ctx.side = command.side;
        ctx.limitPrice = command.price;
        ctx.originalQty = command.size;
        ctx.leavesQty = command.size;
        final int bodyLength = venue.prepare(command, target.market(), ctx, body, System.currentTimeMillis());
        if (bodyLength < 0) {
            System.out.println("round " + round + ": the venue refused to build the order: " + ctx.rejectReason);
            return true;
        }

        venue.submitShape(ctx, target.market(), shape);
        final int submitConnection = acquire(connections, connector);
        final HTTPRequestWriter submit =
                connections.request(submitConnection, shape.method).path(shape.path);
        venue.writeSubmit(ctx, shape, body, bodyLength, submit, System.currentTimeMillis());
        final HTTPResponse submitAnswer = exchange(round, "SUBMIT", connections, submitConnection);
        if (submitAnswer == null) {
            System.out.println("CANCEL NEEDED: the submit got no answer; check the account for a resting order");
            return false;
        }
        final SubmitResult submitted = venue.readSubmit(ctx, submitAnswer);
        System.out.println("  read as " + submitted + (ctx.rejectReason != null ? " (" + ctx.rejectReason + ")" : ""));
        if (submitted == SubmitResult.ACCEPTED) {
            ordersPlaced++;
        } else {
            venue.onReleased(ctx);
            return submitted == SubmitResult.REJECTED;
        }
        if (!venue.venueIdKnown(ctx)) {
            System.out.println("CANCEL NEEDED: accepted without a venue id; cancel it by hand");
            return false;
        }
        if (target.minAgeSeconds() > 0) {
            // The venue refuses a cancel of an order younger than this.
            Thread.sleep(TimeUnit.SECONDS.toMillis(target.minAgeSeconds()) + 500);
        }

        venue.cancelShape(ctx, target.market(), shape);
        final int cancelConnection = acquire(connections, connector);
        final HTTPRequestWriter cancel =
                connections.request(cancelConnection, shape.method).path(shape.path);
        venue.writeCancel(ctx, target.market(), shape, cancel, System.currentTimeMillis());
        final HTTPResponse cancelAnswer = exchange(round, "CANCEL", connections, cancelConnection);
        final CancelResult cancelled = cancelAnswer == null ? null : venue.readCancel(ctx, cancelAnswer);
        System.out.println("  read as " + cancelled);
        venue.onReleased(ctx);
        if (cancelled != CancelResult.ACCEPTED) {
            System.out.println("CANCEL NEEDED: the cancel was not accepted; the bid may still rest on the venue");
            return false;
        }
        ordersCancelled++;
        return true;
    }

    private static <M> LaneCommand command(final Target<M> target, final int round) {
        final LaneCommand command = new LaneCommand();
        command.reset();
        command.kind = LaneCommand.NEW;
        // Polymarket US makes its client order stamp from the slot and counter; a fresh counter per run keeps them
        // apart from earlier runs'.
        command.sessionSlot = SESSION_SLOT + (System.currentTimeMillis() / 1000) % 90_000;
        command.clientOidCounter = round;
        command.clientOidStrategyId = 1;
        command.exchangeId = EXCHANGE_ID;
        command.securityId = target.listing().security().securityId();
        command.side = Side.Bid;
        command.price = target.price();
        command.size = target.size();
        command.timeInForce = TimeInForce.GOOD_TILL_CANCELED;
        command.orderType = OrderType.LIMIT;
        command.postOnly = true;
        if (target.venue() instanceof KalshiLaneVenue) {
            command.sessionSlot = SESSION_SLOT;
        }
        return command;
    }

    /** Sends the request written on {@code connection} and waits for its answer, printing what it saw. */
    private static HTTPResponse exchange(
            final int round, final String what, final AsyncHTTPConnections connections, final int connection) {
        final long sendEpochNanos = epochNanos();
        final long sendNanos = System.nanoTime();
        final int sent = connections.send(connection, round, sendNanos + ANSWER_TIMEOUT_NANOS);
        final long writtenNanos = System.nanoTime();
        if (sent != AsyncHTTPConnections.SENT) {
            System.out.printf("round %d %s: not sent (%d)%n", round, what, sent);
            return null;
        }
        while (!connections.poll()) {
            Thread.onSpinWait();
        }
        final long answerNanos = System.nanoTime();
        final HTTPCompletion completion = connections.completion();
        if (completion.outcome() != HTTPCompletion.RESPONSE) {
            System.out.printf("round %d %s: no answer (outcome %d)%n", round, what, completion.outcome());
            return null;
        }
        final HTTPResponse response = completion.response();
        System.out.printf(
                "round %d %s: status %d, sent at %d ms epoch, write %.1f us, answer after %.2f ms%n",
                round,
                what,
                response.getStatusCode(),
                sendEpochNanos / 1_000_000,
                (writtenNanos - sendNanos) / 1_000.0,
                (answerNanos - sendNanos) / 1_000_000.0);
        for (int i = 0; i < TIMING_HEADERS.length; i++) {
            if (response.hasHeader(i)) {
                System.out.println("  header " + TIMING_HEADERS[i] + ": " + response.header(i));
            }
        }
        printTimeFields(response.getBody(), sendEpochNanos / 1_000_000);
        return response;
    }

    /** Every body field whose name looks like a time, with its offset from our send where it reads as epoch ms or s. */
    private static void printTimeFields(final ByteBuffer body, final long sendEpochMillis) {
        if (body == null || !body.hasRemaining()) {
            System.out.println("  body: empty");
            return;
        }
        final ByteBuffer copy = body.duplicate();
        final byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        final JsonNode root;
        try {
            root = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("  body: " + bytes.length + " bytes, not JSON");
            return;
        }
        final StringBuilder names = new StringBuilder();
        walk(root, "", names, sendEpochMillis);
        System.out.println("  body fields: " + names);
    }

    private static void walk(
            final JsonNode node, final String path, final StringBuilder names, final long sendEpochMillis) {
        if (node.isObject()) {
            final Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                final Map.Entry<String, JsonNode> field = fields.next();
                final String name = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
                if (field.getValue().isValueNode()) {
                    names.append(name).append(' ');
                    if (TIME_FIELD.matcher(field.getKey()).matches()) {
                        printTimeField(name, field.getValue(), sendEpochMillis);
                    }
                } else {
                    walk(field.getValue(), name, names, sendEpochMillis);
                }
            }
        } else if (node.isArray() && !node.isEmpty()) {
            walk(node.get(0), path + "[0]", names, sendEpochMillis);
        }
    }

    private static void printTimeField(final String name, final JsonNode value, final long sendEpochMillis) {
        final String text = value.asText();
        String offset = "";
        if (text.matches("\\d{13}")) {
            offset = " (" + (Long.parseLong(text) - sendEpochMillis) + " ms after our send)";
        } else if (text.matches("\\d{10}")) {
            offset = " (" + (Long.parseLong(text) * 1000 - sendEpochMillis) + " ms after our send, in whole s)";
        }
        System.out.println("  time field " + name + " = " + text + offset);
    }

    private static int acquire(final AsyncHTTPConnections connections, final HTTPConnector connector)
            throws InterruptedException {
        final long giveUp = System.nanoTime() + CONNECT_TIMEOUT_NANOS;
        while (true) {
            connector.doWork();
            final int connection = connections.acquire();
            if (connection != AsyncHTTPConnections.NONE) {
                return connection;
            }
            if (System.nanoTime() - giveUp > 0) {
                throw new IllegalStateException("No connection to " + connections.host() + " opened");
            }
            Thread.sleep(5);
        }
    }

    // ----- helpers -----

    private static long epochNanos() {
        final Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    private static long price() {
        return scaled(System.getProperty("price", "0.01"), Statics.PRICE_SCALING_FACTOR);
    }

    private static long size(final String fallback) {
        return scaled(System.getProperty("size", fallback), Statics.SIZE_SCALING_FACTOR);
    }

    private static long scaled(final String value, final long scale) {
        return Math.round(Double.parseDouble(value) * scale);
    }

    private static Listing listing(final String exchange, final String exchangeSecurityId, final String symbol) {
        return new Listing(
                1,
                new Exchange(EXCHANGE_ID, exchange, exchange, "global"),
                new Security(3, symbol, null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                exchangeSecurityId,
                symbol);
    }

    private static String required(final String property) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("-D" + property + " is required");
        }
        return value;
    }

    private static String secret(final String profile, final String secretId) {
        try (SecretsManagerClient secrets = SecretsManagerClient.builder()
                .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
                .credentialsProvider(ProfileCredentialsProvider.create(profile))
                .build()) {
            return secrets.getSecretValue(
                            GetSecretValueRequest.builder().secretId(secretId).build())
                    .secretString();
        }
    }
}
