package group.gnometrading.gateways.outbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.GatewayRunners;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlAuthHeaders;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlMarketInfo;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOrderSigner;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOutboundReader;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOutboundWriter;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlVenueOrderQuery;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.fee.FeeTimeline;
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;

/**
 * Drives the Polymarket International outbound gateway against the real CLOB, with real money, in small amounts.
 *
 * <p>Credentials come from files and are never printed: {@code -Dpm.keyFile} (the signing key, 0x hex) and
 * {@code -Dpm.credsFile} (JSON with apiKey, secret, passphrase). {@code -Dpm.signer}, {@code -Dpm.funder} and
 * {@code -Dpm.signatureType} describe the account; {@code -Dpm.condition} and {@code -Dpm.token} the listing.
 *
 * <p>Modes ({@code -Dmode}): {@code rest} rests a BUY far below the market and cancels it; {@code ioc} buys at
 * {@code -Dbuy.price} and sells back at {@code -Dsell.price}; {@code short} sells shares it doesn't hold; {@code one}
 * sends a single order ({@code -Dorder.side=bid|ask}, {@code -Dprice}, {@code -Dsize}, {@code -Dtif=gtc|ioc});
 * {@code reconnect} rests an order, drops the reader's socket, cancels and reconnects. {@code -Dlose.submits}
 * throws away that many submit answers after the CLOB took them.
 */
public final class PolymarketLiveSmoke {

    private static final String CLOB_HOST = "clob.polymarket.com";
    private static final String USER_WS = "wss://ws-subscriptions-clob.polymarket.com/ws/user";
    // No single order in these tests may spend more than this.
    // -Dmax.buy raises it, e.g. for a BUY priced too low to match whose size the balance can't cover.
    private static final double MAX_BUY_DOLLARS = Double.parseDouble(System.getProperty("max.buy", "3.0"));
    private static final ObjectMapper JSON = new ObjectMapper();

    private PolymarketLiveSmoke() {}

    public static void main(final String[] args) throws Exception {
        final String mode = System.getProperty("mode", "rest");
        final Account account = Account.fromProperties();
        final String condition = required("pm.condition");
        final Listing listing = listing(condition + ":" + required("pm.token"));
        final PolymarketIntlMarketInfo marketInfo =
                PolymarketIntlMarketInfo.load(new HTTPClient(), CLOB_HOST, condition);
        System.out.println("market " + condition + ": " + marketInfo);

        if (mode.equals("status")) {
            // Read-only: what the CLOB says of one order, e.g. one a later session's recovery should have cancelled.
            final PolymarketIntlVenueOrderQuery query =
                    new PolymarketIntlVenueOrderQuery(new LoggingHttpClient("status"), CLOB_HOST, authHeaders(account));
            if (System.getProperty("pm.order") == null) {
                // Read-only: this account's trades in the market over the last -Dsince.seconds, raw.
                final PolymarketIntlAuthHeaders headers = authHeaders(account);
                final String path = "/data/trades?market=" + condition + "&after="
                        + (System.currentTimeMillis() / 1000 - Long.getLong("since.seconds", 600));
                headers.sign("GET", "/data/trades", null, 0, 0);
                final HTTPResponse response = new HTTPClient()
                        .get(
                                HTTPProtocol.HTTPS,
                                CLOB_HOST,
                                path,
                                "POLY_API_KEY",
                                headers.apiKey(),
                                "POLY_SIGNATURE",
                                headers.signature(),
                                "POLY_TIMESTAMP",
                                headers.timestamp(),
                                "POLY_PASSPHRASE",
                                headers.passphrase(),
                                "POLY_ADDRESS",
                                headers.address());
                System.out.println("TRADES " + response.getStatusCode() + " "
                        + StandardCharsets.UTF_8.decode(response.getBody().duplicate()));
                return;
            }
            System.out.println("order: " + query.getOrder(listing, required("pm.order"), 0));
            System.out.println("open orders: " + query.listOpenOrders(listing));
            return;
        }
        final Stack stack = new Stack(account, listing, marketInfo);
        Thread.sleep(4_000);
        try {
            switch (mode) {
                case "rest" -> {
                    stack.send(1, Side.Bid, "0.05", "5", TimeInForce.GOOD_TILL_CANCELED, "rest a BUY far below");
                    Thread.sleep(Math.max(0, marketInfo.minOrderAgeSeconds()) * 1_000L);
                    stack.cancel(1, "cancel it");
                }
                case "ioc" -> {
                    final String size = System.getProperty("size", "5");
                    stack.send(11, Side.Bid, required("buy.price"), size, TimeInForce.IMMEDIATE_OR_CANCELED, "buy");
                    final String sellSize = System.getProperty("sell.size", size);
                    stack.send(
                            12,
                            Side.Ask,
                            required("sell.price"),
                            sellSize,
                            TimeInForce.IMMEDIATE_OR_CANCELED,
                            "sell back");
                }
                case "fok-postonly" -> {
                    stack.send(
                            41, Side.Bid, required("fok.price"), "31", TimeInForce.FILL_OR_KILL, "FOK that can't fill");
                    stack.send(
                            42,
                            Side.Bid,
                            required("cross.price"),
                            System.getProperty("cross.size", "5"),
                            TimeInForce.GOOD_TILL_CANCELED,
                            true,
                            "post-only that would cross");
                    stack.send(
                            43, Side.Bid, "0.010", "5", TimeInForce.GOOD_TILL_CANCELED, true, "post-only that rests");
                    stack.cancel(43, "cancel it");
                }
                case "maker" -> {
                    // Rests at the touch and waits for someone to trade with it.
                    stack.send(
                            51,
                            Side.Bid,
                            required("maker.price"),
                            required("maker.size"),
                            TimeInForce.GOOD_TILL_CANCELED,
                            true,
                            "rest a post-only BUY at the touch");
                    Thread.sleep(Long.getLong("wait.seconds", 300) * 1_000L);
                    stack.cancel(51, "cancel what's left");
                }
                case "one" -> {
                    // One order on its own, to see what the CLOB makes of it; one that rests is cancelled.
                    final Side side = required("order.side").equals("ask") ? Side.Ask : Side.Bid;
                    final boolean ioc = System.getProperty("tif", "gtc").equals("ioc");
                    stack.send(
                            61,
                            side,
                            required("price"),
                            System.getProperty("size", "5"),
                            ioc ? TimeInForce.IMMEDIATE_OR_CANCELED : TimeInForce.GOOD_TILL_CANCELED,
                            "one " + (ioc ? "IOC " : "GTC ") + side);
                    Thread.sleep(3_000);
                    if (!ioc) {
                        Thread.sleep(Math.max(0, marketInfo.minOrderAgeSeconds()) * 1_000L);
                        stack.cancel(61, "cancel it, in case it rested");
                    }
                }
                case "short" -> stack.send(
                        21, Side.Ask, "0.95", "5", TimeInForce.GOOD_TILL_CANCELED, "sell shares we don't hold");
                case "reconnect" -> {
                    stack.send(31, Side.Bid, "0.05", "5", TimeInForce.GOOD_TILL_CANCELED, "rest a BUY far below");
                    System.out.println("\n==== the reader's socket goes down");
                    stack.reader.disconnect();
                    Thread.sleep(Math.max(0, marketInfo.minOrderAgeSeconds()) * 1_000L);
                    stack.cancel(31, "while it's down, cancel through the writer");
                    System.out.println("\n==== the reader reconnects and catches up");
                    stack.reader.connect();
                    Thread.sleep(6_000);
                }
                default -> throw new IllegalArgumentException("unknown -Dmode=" + mode);
            }
        } finally {
            stack.close();
        }
    }

    /** The account, from files and properties; nothing secret is ever printed. */
    private record Account(
            byte[] key, String apiKey, String secret, String passphrase, String signer, String funder, int type) {

        static Account fromProperties() throws IOException {
            final String keyHex =
                    Files.readString(Path.of(required("pm.keyFile"))).strip();
            final JsonNode creds = JSON.readTree(Files.readString(Path.of(required("pm.credsFile"))));
            return new Account(
                    HexFormat.of().parseHex(keyHex.startsWith("0x") ? keyHex.substring(2) : keyHex),
                    creds.path("apiKey").asText(),
                    creds.path("secret").asText(),
                    creds.path("passphrase").asText(),
                    required("pm.signer"),
                    required("pm.funder"),
                    Integer.parseInt(required("pm.signatureType")));
        }

        @Override
        public String toString() {
            return "Account[signer=" + signer + ", funder=" + funder + ", type=" + type + "]";
        }
    }

    private static final class Stack {
        private final SequencedRingBuffer<Order> orders = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        private final SequencedRingBuffer<OrderExecutionReport> reports =
                new SequencedRingBuffer<>(OrderExecutionReport::new, new GlobalSequence());
        // Printed from a thread of its own, as reports arrive, while the main thread drives the orders.
        private GnomeAgentRunner reportsRunner;
        private final GnomeAgentRunner writerRunner;
        private final GatewayRunners gatewayRunners;
        final PolymarketIntlOutboundReader reader;

        Stack(final Account account, final Listing listing, final PolymarketIntlMarketInfo marketInfo)
                throws Exception {
            final SystemEpochNanoClock nanoClock = new SystemEpochNanoClock();
            final Logger logger = new ConsoleLogger(nanoClock);
            final ManyToOneRingBuffer<OrderContext> newOrderQueue = queue();
            final ManyToOneRingBuffer<OrderContext> writerReportQueue = queue();
            final ManyToOneRingBuffer<OrderContext> releasedOrderQueue = queue();
            this.reportsRunner = new GnomeAgentRunner(
                    this.reports.createPoller((seq, templateId, buffer, length) -> printReport(buffer))::poll,
                    Throwable::printStackTrace);
            GnomeAgentRunner.startOnThread(this.reportsRunner);

            final WebSocketClient ws = new WebSocketClientBuilder()
                    .withURI(new URI(USER_WS))
                    .withSocketFactory(new NativeSSLSocketFactory())
                    .withReadBufferSize(1 << 16)
                    .build();
            this.reader = new PolymarketIntlOutboundReader(
                    logger,
                    this.reports,
                    newOrderQueue,
                    writerReportQueue,
                    releasedOrderQueue,
                    nanoClock,
                    listing,
                    ws,
                    new JsonDecoder(),
                    account.apiKey(),
                    account.secret(),
                    account.passphrase(),
                    // No registry here, so fills report no fee.
                    FeeTimeline.EMPTY,
                    new PolymarketIntlVenueOrderQuery(
                            new LoggingHttpClient("catchup"), CLOB_HOST, authHeaders(account)));
            final PolymarketIntlOutboundWriter writer = new PolymarketIntlOutboundWriter(
                    logger,
                    this.orders,
                    newOrderQueue,
                    writerReportQueue,
                    releasedOrderQueue,
                    new LoggingHttpClient("writer"),
                    CLOB_HOST,
                    new PolymarketIntlOrderSigner(account.key(), account.signer(), account.funder(), account.type()),
                    authHeaders(account),
                    marketInfo,
                    nanoClock,
                    listing);
            final GatewayConfig config = new GatewayConfig.Builder()
                    .withKeepAliveInterval(Duration.ofSeconds(10))
                    .withMaxSilentInterval(Duration.ofSeconds(30))
                    .build();
            final OutboundGateway gateway = new OutboundGateway(logger, this.reader, config, new SystemEpochClock());
            this.gatewayRunners = GatewayRunners.venue(
                            this.reader.pauseControl,
                            new GnomeAgentRunner(this.reader, Throwable::printStackTrace),
                            new GnomeAgentRunner(gateway, Throwable::printStackTrace))
                    .start();
            this.writerRunner = new GnomeAgentRunner(writer, Throwable::printStackTrace);
            GnomeAgentRunner.startOnThread(this.writerRunner);
        }

        void send(
                final long counter,
                final Side side,
                final String price,
                final String size,
                final TimeInForce tif,
                final String label)
                throws Exception {
            send(counter, side, price, size, tif, false, label);
        }

        void send(
                final long counter,
                final Side side,
                final String price,
                final String size,
                final TimeInForce tif,
                final boolean postOnly,
                final String label)
                throws Exception {
            final double dollars = Double.parseDouble(price) * Double.parseDouble(size);
            if (side == Side.Bid && dollars > MAX_BUY_DOLLARS) {
                throw new IllegalStateException("Refusing a BUY of $" + dollars + ": over the test's limit");
            }
            System.out.println("\n==== " + label + ": " + side + " " + size + " @ " + price + " " + tif
                    + (postOnly ? " post-only" : "") + " (client oid " + counter + ")");
            final Order order = this.orders.claim();
            order.encoder.exchangeId(2);
            order.encoder.securityId(3);
            order.encoder.price(Math.round(Double.parseDouble(price) * Statics.PRICE_SCALING_FACTOR));
            order.encoder.size(Math.round(Double.parseDouble(size) * Statics.SIZE_SCALING_FACTOR));
            order.encoder.side(side);
            order.encoder.orderType(OrderType.LIMIT);
            order.encoder.timeInForce(tif);
            order.encoder.flags().clear();
            order.encoder.flags().postOnly(postOnly);
            order.encodeClientOid(counter, 1);
            this.orders.publish();
            Thread.sleep(8_000);
        }

        void cancel(final long counter, final String label) throws Exception {
            System.out.println("\n==== " + label + " (client oid " + counter + ")");
            final CancelOrder cancel = new CancelOrder();
            cancel.encoder.exchangeId(2);
            cancel.encoder.securityId(3);
            cancel.encodeClientOid(counter, 1);
            this.orders.publishRaw(cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, cancel.totalMessageSize());
            Thread.sleep(8_000);
        }

        void close() throws Exception {
            this.writerRunner.close();
            this.gatewayRunners.close();
            this.reportsRunner.close();
        }

        private static ManyToOneRingBuffer<OrderContext> queue() {
            return new ManyToOneRingBuffer<>(
                    OrderContext[]::new, OrderContext::new, OrderContext.HANDOFF_QUEUE_CAPACITY);
        }
    }

    private static PolymarketIntlAuthHeaders authHeaders(final Account account) {
        // POLY_ADDRESS is the key's own address, whatever the wallet type.
        return new PolymarketIntlAuthHeaders(
                account.apiKey(), account.secret(), account.passphrase(), account.signer());
    }

    /** Prints each request and the CLOB's answer, without the auth headers' values. */
    private static final class LoggingHttpClient extends HTTPClient {
        private final String role;
        private int submitsToLose = Integer.getInteger("lose.submits", 0);

        LoggingHttpClient(final String role) {
            this.role = role;
        }

        @Override
        public HTTPResponse post(
                final HTTPProtocol protocol,
                final String host,
                final GnomeString path,
                final byte[] body,
                final int bodyLength,
                final String k1,
                final String v1,
                final String k2,
                final String v2,
                final String k3,
                final String v3,
                final String k4,
                final String v4,
                final String k5,
                final String v5)
                throws IOException {
            requireClob(host);
            final HTTPResponse response =
                    super.post(protocol, host, path, body, bodyLength, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
            if (this.submitsToLose > 0) {
                this.submitsToLose--;
                System.out.println("HTTP " + this.role + " POST " + path + " -> " + response.getStatusCode()
                        + " (answer thrown away, as if it timed out)");
                throw new IOException("simulated lost response");
            }
            System.out.println("HTTP " + this.role + " POST " + path + " body=" + redactSignature(body, bodyLength)
                    + "\n  -> " + response.getStatusCode() + ": " + text(response.getBody()));
            return response;
        }

        @Override
        public HTTPResponse get(
                final HTTPProtocol protocol,
                final String host,
                final String path,
                final String k1,
                final String v1,
                final String k2,
                final String v2,
                final String k3,
                final String v3,
                final String k4,
                final String v4,
                final String k5,
                final String v5)
                throws IOException {
            requireClob(host);
            final HTTPResponse response = super.get(protocol, host, path, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
            System.out.println("HTTP " + this.role + " GET " + path + "\n  -> " + response.getStatusCode() + ": "
                    + text(response.getBody()));
            return response;
        }

        @Override
        public HTTPResponse delete(
                final HTTPProtocol protocol,
                final String host,
                final GnomeString path,
                final byte[] body,
                final int bodyLength,
                final String k1,
                final String v1,
                final String k2,
                final String v2,
                final String k3,
                final String v3,
                final String k4,
                final String v4,
                final String k5,
                final String v5)
                throws IOException {
            requireClob(host);
            final HTTPResponse response =
                    super.delete(protocol, host, path, body, bodyLength, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
            System.out.println("HTTP " + this.role + " DELETE " + path + " body="
                    + new String(body, 0, bodyLength, StandardCharsets.UTF_8) + "\n  -> " + response.getStatusCode()
                    + ": " + text(response.getBody()));
            return response;
        }
    }

    private static void requireClob(final String host) {
        if (!CLOB_HOST.equals(host)) {
            throw new IllegalStateException("Refusing a request to " + host);
        }
    }

    /** The order body with its signature shortened: it's long, and of no use in a log. */
    private static String redactSignature(final byte[] body, final int length) {
        return new String(body, 0, length, StandardCharsets.UTF_8)
                .replaceAll("\"signature\":\"(0x[0-9a-f]{16})[0-9a-f]*\"", "\"signature\":\"$1...\"");
    }

    private static String text(final ByteBuffer body) {
        if (body == null) {
            return "";
        }
        final String text = StandardCharsets.UTF_8.decode(body.duplicate()).toString();
        return text.length() > 2000 ? text.substring(0, 2000) + " ...(truncated)" : text;
    }

    private static void printReport(final org.agrona.concurrent.UnsafeBuffer buffer) {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.wrap(buffer);
        final OrderExecutionReportDecoder d = report.decoder;
        System.out.println("REPORT oid=" + report.getClientOidCounter() + " exec=" + d.execType() + " status="
                + d.orderStatus() + " reject=" + d.rejectReason()
                + " filled=" + nullable(d.filledQty(), OrderExecutionReportDecoder.filledQtyNullValue(), true)
                + " fillPrice=" + nullable(d.fillPrice(), OrderExecutionReportDecoder.fillPriceNullValue(), false)
                + " cum=" + qty(d.cumulativeQty()) + " leaves=" + qty(d.leavesQty())
                + " fee=" + nullable(d.fee(), OrderExecutionReportDecoder.feeNullValue(), false)
                + " liquidity=" + d.liquidity());
    }

    private static String nullable(final long value, final long nullValue, final boolean isQty) {
        if (value == nullValue) {
            return "-";
        }
        return isQty ? qty(value) : String.format("%.5f", (double) value / Statics.PRICE_SCALING_FACTOR);
    }

    private static String qty(final long size) {
        return String.format("%.2f", (double) size / Statics.SIZE_SCALING_FACTOR);
    }

    private static Listing listing(final String exchangeSecurityId) {
        return new Listing(
                1,
                new Exchange(2, "POLYMARKET", "Polymarket", "global", SchemaType.MBP_10),
                new Security(3, exchangeSecurityId, null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                exchangeSecurityId,
                exchangeSecurityId);
    }

    private static String required(final String property) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("-D" + property + " is required");
        }
        return value;
    }
}
