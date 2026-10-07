package group.gnometrading.gateways.outbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.GatewayRunners;
import group.gnometrading.gateways.credentials.KalshiCredentials;
import group.gnometrading.gateways.inbound.InboundGateway;
import group.gnometrading.gateways.inbound.InboundJsonWebSocketWriter;
import group.gnometrading.gateways.inbound.exchanges.kalshi.KalshiInboundReader;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiAuthSigner;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiOutboundReader;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiOutboundWriter;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiVenueOrderQuery;
import group.gnometrading.gateways.outbound.recovery.VenueOrder;
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
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
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
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Manual live check of the Kalshi gateways, run by hand; not a unit test.
 *
 * <ul>
 *   <li>{@code -Dmode=inbound}: production, read-only. Runs the real KalshiInboundReader on {@code -Dprod.ticker} and
 *       prints the books it publishes next to Kalshi's REST orderbook. Without a ticker, lists open markets.
 *   <li>{@code -Dmode=orders}: demo only. Runs the real outbound writer and reader for the YES and NO listings of
 *       {@code -Ddemo.ticker}, sends four 1-contract IOC orders (YES ask, YES bid, NO bid, NO ask) priced to cross,
 *       and prints the raw user_order and fill messages next to the execution reports the reader publishes.
 * </ul>
 *
 * <p>Credentials are read from Secrets Manager and never printed. Orders refuse to run against any host that isn't
 * Kalshi's demo environment.
 */
public final class KalshiLiveSmoke {

    private static final String PROD_API = "https://external-api.kalshi.com";
    private static final String PROD_WS = "wss://external-api-ws.kalshi.com/trade-api/ws/v2";
    private static final String DEMO_API = "https://external-api.demo.kalshi.co";
    private static final String DEMO_WS_HOST = "wss://external-api-ws.demo.kalshi.co";
    private static final String DEMO_HOST_SUFFIX = ".demo.kalshi.co";
    private static final String MARKET_WS_PATH = "/trade-api/ws/v2";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private KalshiLiveSmoke() {}

    public static void main(final String[] args) throws Exception {
        final String mode = System.getProperty("mode", "inbound");
        final String profile = System.getProperty("aws.profile", "dev");
        try {
            if (mode.equals("inbound")) {
                runInbound(
                        credentials(profile, System.getProperty("prod.secret", "gnome/exchange-credentials/kalshi")),
                        System.getProperty("prod.ticker"),
                        Integer.getInteger("seconds", 60));
            } else if (mode.equals("orders")) {
                final String keyFile = System.getProperty("demo.keyFile");
                runOrders(
                        keyFile != null
                                ? credentialsFromFile(System.getProperty("demo.keyId"), keyFile)
                                : credentials(
                                        profile,
                                        System.getProperty("demo.secret", "gnome/exchange-credentials/kalshi-demo")),
                        System.getProperty("demo.ticker"));
            } else if (mode.equals("recovery")) {
                final String keyFile = System.getProperty("demo.keyFile");
                runRecovery(
                        keyFile != null
                                ? credentialsFromFile(System.getProperty("demo.keyId"), keyFile)
                                : credentials(
                                        profile,
                                        System.getProperty("demo.secret", "gnome/exchange-credentials/kalshi-demo")),
                        System.getProperty("demo.ticker"));
            } else if (mode.equals("partial")) {
                runPartial(
                        credentialsFromFile(System.getProperty("demo.keyId"), System.getProperty("demo.keyFile")),
                        credentialsFromFile(System.getProperty("other.keyId"), System.getProperty("other.keyFile")),
                        System.getProperty("demo.ticker"));
            } else if (mode.equals("reconnect")) {
                runReconnect(
                        credentialsFromFile(System.getProperty("demo.keyId"), System.getProperty("demo.keyFile")),
                        credentialsFromFile(System.getProperty("other.keyId"), System.getProperty("other.keyFile")),
                        System.getProperty("demo.ticker"));
            } else if (mode.equals("idle-reconnect")) {
                runIdleReconnect(
                        credentialsFromFile(System.getProperty("demo.keyId"), System.getProperty("demo.keyFile")),
                        credentialsFromFile(System.getProperty("other.keyId"), System.getProperty("other.keyFile")),
                        System.getProperty("demo.ticker"));
            } else if (mode.equals("transfer")) {
                // Collateral is per exchange shard: moves demo dollars from shard 0 to -Dshard.
                final KalshiCredentials creds =
                        credentialsFromFile(System.getProperty("demo.keyId"), System.getProperty("demo.keyFile"));
                final long centicents = Math.round(Double.parseDouble(System.getProperty("amount", "10")) * 10_000);
                signedRequest(
                        creds,
                        "POST",
                        "/trade-api/v2/portfolio/intra_exchange_instance_transfer",
                        "{\"source\":\"event_contract\",\"destination\":\"event_contract\",\"amount\":"
                                + centicents + ",\"source_exchange_shard\":0,\"destination_exchange_shard\":"
                                + Integer.getInteger("shard", 0) + "}");
                Thread.sleep(3_000);
                System.out.println(signedGet(creds, DEMO_API, "/trade-api/v2/portfolio/balance", ""));
            } else if (mode.equals("counterparty")) {
                runCounterparty(
                        credentialsFromFile(System.getProperty("demo.keyId"), System.getProperty("demo.keyFile")),
                        System.getProperty("demo.ticker"),
                        System.getProperty("action"),
                        System.getProperty("price", "0.01"));
            } else {
                throw new IllegalArgumentException("-Dmode must be inbound, orders, recovery or counterparty");
            }
        } catch (final Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    // ========== Inbound ==========

    private static void runInbound(final KalshiCredentials creds, final String ticker, final int seconds)
            throws Exception {
        if (ticker == null) {
            listOpenMarkets(creds, PROD_API);
            return;
        }
        System.out.println("== REST orderbook before (production)");
        printRestOrderbook(creds, PROD_API, ticker);

        final SystemEpochNanoClock nanoClock = new SystemEpochNanoClock();
        final Logger logger = new ConsoleLogger(nanoClock);
        final SequencedRingBuffer<Mbp10Schema> books =
                new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        final BookPrinter printer = new BookPrinter();
        books.handleEventsWith((seq, templateId, buffer, length) -> printer.onBook(buffer));
        books.start();

        final WebSocketClient ws = new WebSocketClientBuilder()
                .withURI(new URI(PROD_WS))
                .withSocketFactory(new NativeSSLSocketFactory())
                .withReadBufferSize(1 << 18)
                .build();
        final InboundJsonWebSocketWriter socketWriter = new InboundJsonWebSocketWriter(ws, new JsonEncoder());
        final KalshiInboundReader reader = new KalshiInboundReader(
                logger,
                books,
                nanoClock,
                socketWriter,
                listing(1, ticker + ":yes"),
                ws,
                new JsonDecoder(),
                creds.apiKey(),
                creds.privateKey());
        final InboundGateway gateway =
                new InboundGateway(logger, new GatewayConfig.Builder().build(), reader, new SystemEpochClock());

        final GatewayRunners runners = GatewayRunners.marketData(
                        reader.pauseControl,
                        new GnomeAgentRunner(reader, Throwable::printStackTrace),
                        new GnomeAgentRunner(socketWriter, Throwable::printStackTrace),
                        new GnomeAgentRunner(gateway, Throwable::printStackTrace))
                .start();

        System.out.println("== Published books for " + seconds + "s (none until the first delta after the snapshot)");
        Thread.sleep(seconds * 1000L);
        System.out.println("== Last published book");
        printer.printLast();
        System.out.println("== REST orderbook after (production)");
        printRestOrderbook(creds, PROD_API, ticker);

        closeTimed("inbound gateway", runners);
        books.shutdown();
    }

    private static final class BookPrinter {
        private final Mbp10Schema schema = new Mbp10Schema();
        private int printed;
        private long lastPrintMillis;
        private String last = "(nothing published)";

        synchronized void onBook(final org.agrona.concurrent.UnsafeBuffer buffer) {
            this.schema.wrap(buffer);
            this.last = describe(this.schema);
            final long now = System.currentTimeMillis();
            if (this.printed < 10 || now - this.lastPrintMillis >= 5_000) {
                System.out.println(this.last);
                this.printed++;
                this.lastPrintMillis = now;
            }
        }

        synchronized void printLast() {
            System.out.println(this.last);
        }

        private static String describe(final Mbp10Schema s) {
            final var d = s.decoder;
            final StringBuilder sb = new StringBuilder();
            sb.append("book action=").append(d.action()).append(" side=").append(d.side());
            sb.append(" ts=").append(d.timestampEvent());
            if (d.price() != d.priceNullValue()) {
                sb.append(" trade=").append(px(d.price())).append("x").append(qty(d.size()));
            }
            sb.append("\n  bids:");
            appendLevel(sb, d.bidPrice0(), d.bidSize0(), d.bidPrice0NullValue());
            appendLevel(sb, d.bidPrice1(), d.bidSize1(), d.bidPrice1NullValue());
            appendLevel(sb, d.bidPrice2(), d.bidSize2(), d.bidPrice2NullValue());
            appendLevel(sb, d.bidPrice3(), d.bidSize3(), d.bidPrice3NullValue());
            appendLevel(sb, d.bidPrice4(), d.bidSize4(), d.bidPrice4NullValue());
            sb.append("\n  asks:");
            appendLevel(sb, d.askPrice0(), d.askSize0(), d.askPrice0NullValue());
            appendLevel(sb, d.askPrice1(), d.askSize1(), d.askPrice1NullValue());
            appendLevel(sb, d.askPrice2(), d.askSize2(), d.askPrice2NullValue());
            appendLevel(sb, d.askPrice3(), d.askSize3(), d.askPrice3NullValue());
            appendLevel(sb, d.askPrice4(), d.askSize4(), d.askPrice4NullValue());
            return sb.toString();
        }

        private static void appendLevel(final StringBuilder sb, final long price, final long size, final long nul) {
            if (price != nul) {
                sb.append(' ').append(px(price)).append('x').append(qty(size));
            }
        }
    }

    // ========== Orders (demo) ==========

    private static void runOrders(final KalshiCredentials creds, final String ticker) throws Exception {
        requireDemo(DEMO_API);
        requireDemo(DEMO_WS_HOST);
        if (ticker == null) {
            listOpenMarkets(creds, DEMO_API);
            return;
        }
        System.out.println("== REST orderbook (demo)");
        printRestOrderbook(creds, DEMO_API, ticker);
        System.out.println("== Balance (demo)");
        System.out.println(signedGet(creds, DEMO_API, "/trade-api/v2/portfolio/balance", ""));
        final WebSocket raw = openRaw(creds, DEMO_WS_HOST + MARKET_WS_PATH, MARKET_WS_PATH, "RAW");
        raw.sendText(
                        "{\"id\":1,\"cmd\":\"subscribe\",\"params\":{\"channels\":[\"user_orders\",\"fill\"],"
                                + "\"market_tickers\":[\"" + ticker + "\"]}}",
                        true)
                .join();

        final Stack yes = new Stack(creds, listing(1, ticker + ":yes"), "YES");
        final Stack no = new Stack(creds, listing(2, ticker + ":no"), "NO");
        Thread.sleep(3_000); // both readers connected and subscribed before anything is sent

        // Priced to cross whatever rests on the other side, so each fills at the venue's price, not ours: the
        // reported cost then shows which terms Kalshi reports it in.
        yes.send(1, Side.Ask, "0.01", "YES listing ask (sell YES)");
        yes.send(2, Side.Bid, "0.99", "YES listing bid (buy YES)");
        no.send(3, Side.Bid, "0.99", "NO listing bid (buy NO) -> sent as ask YES @0.01");
        no.send(4, Side.Ask, "0.01", "NO listing ask (sell NO) -> sent as bid YES @0.99");

        yes.close();
        no.close();
        raw.abort();
    }

    // ========== Recovery (demo) ==========

    /**
     * The order lifecycle the IOC run doesn't reach (rest, amend, cancel), then what a later session's startup
     * recovery asks the venue: the real KalshiVenueOrderQuery listing, looking up and cancelling orders.
     */
    private static void runRecovery(final KalshiCredentials creds, final String ticker) throws Exception {
        requireDemo(DEMO_API);
        requireDemo(DEMO_WS_HOST);
        if (ticker == null) {
            listOpenMarkets(creds, DEMO_API);
            return;
        }
        System.out.println("== REST orderbook (demo)");
        printRestOrderbook(creds, DEMO_API, ticker);

        final WebSocket raw = openRaw(creds, DEMO_WS_HOST + MARKET_WS_PATH, MARKET_WS_PATH, "RAW");
        raw.sendText(
                        "{\"id\":1,\"cmd\":\"subscribe\",\"params\":{\"channels\":[\"user_orders\",\"fill\"],"
                                + "\"market_tickers\":[\"" + ticker + "\"]}}",
                        true)
                .join();

        final Listing yesListing = listing(1, ticker + ":yes");
        final Stack yes = new Stack(creds, yesListing, "YES");
        Thread.sleep(3_000);
        final long startedMs = System.currentTimeMillis();

        // Resting well below the market, so nothing fills.
        yes.sendLimit(11, Side.Bid, "0.05", "1.00", TimeInForce.GOOD_TILL_CANCELED, "rest a YES bid");
        yes.sendModify(11, "0.06", "2.00", "amend it to 2 @ 0.06");
        yes.sendCancel(11, "cancel it through the writer");
        yes.sendLimit(12, Side.Bid, "0.04", "1.00", TimeInForce.GOOD_TILL_CANCELED, "rest a leftover YES bid");
        // On an empty book, keep -Dioc.price above the leftover bid: crossing it self-trades and cancels the leftover.
        yes.sendLimit(
                13,
                Side.Ask,
                System.getProperty("ioc.price", "0.01"),
                "1.00",
                TimeInForce.IMMEDIATE_OR_CANCELED,
                "an IOC ask");

        final KalshiVenueOrderQuery query = new KalshiVenueOrderQuery(
                new LoggingHttpClient("QUERY"),
                requireDemo(new URI(DEMO_API).getHost()),
                new KalshiAuthSigner(creds.apiKey(), creds.privateKey()),
                new SystemEpochClock());
        final String leftoverId = yes.clientOrderId(12);

        System.out.println("\n==== listOpenOrders: the leftover should be the only resting order of ours");
        final List<VenueOrder> resting = query.listOpenOrders(yesListing);
        resting.forEach(order -> System.out.println("VENUE " + describe(order)));

        for (final long counter : new long[] {11, 13}) {
            System.out.println("\n==== getOrder " + yes.clientOrderId(counter));
            System.out.println("VENUE "
                    + query.getOrder(yesListing, yes.clientOrderId(counter), startedMs)
                            .map(KalshiLiveSmoke::describe)
                            .orElse("(not found)"));
        }

        System.out.println("\n==== cancel the leftover through the query, as recovery would");
        final VenueOrder leftover = resting.stream()
                .filter(order -> order.exchangeOrderId().equals(leftoverId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("leftover " + leftoverId + " was not listed as resting"));
        System.out.println("cancel accepted: " + query.cancel(yesListing, leftover));
        Thread.sleep(3_000);
        System.out.println("VENUE "
                + query.getOrder(yesListing, leftoverId, startedMs)
                        .map(KalshiLiveSmoke::describe)
                        .orElse("(not found)"));

        yes.close();
        raw.abort();
    }

    // ========== Partial fills (demo, two accounts) ==========

    /**
     * A resting ask filled in pieces by a second account, amended between and after the fills: partial fills, maker
     * fills, an amend whose count includes what has filled, and an amend down to the filled quantity.
     */
    private static void runPartial(final KalshiCredentials creds, final KalshiCredentials other, final String ticker)
            throws Exception {
        requireDemo(DEMO_API);
        requireDemo(DEMO_WS_HOST);
        System.out.println("== REST orderbook (demo)");
        printRestOrderbook(creds, DEMO_API, ticker);
        final WebSocket raw = openRaw(creds, DEMO_WS_HOST + MARKET_WS_PATH, MARKET_WS_PATH, "RAW");
        raw.sendText(
                        "{\"id\":1,\"cmd\":\"subscribe\",\"params\":{\"channels\":[\"user_orders\",\"fill\"],"
                                + "\"market_tickers\":[\"" + ticker + "\"]}}",
                        true)
                .join();
        final Stack yes = new Stack(creds, listing(1, ticker + ":yes"), "YES");
        Thread.sleep(3_000);

        yes.sendLimit(21, Side.Ask, "0.90", "3.00", TimeInForce.GOOD_TILL_CANCELED, "rest a YES ask of 3");
        lift(other, ticker, "0.9000", "the other account takes 1 @ 0.90");
        // Changing the resting size makes Kalshi report the post-amend counts; 1 filled + 3 working.
        yes.sendModify(21, "0.85", "4.00", "amend to 4 @ 0.85 (1 filled + 3 working)");
        lift(other, ticker, "0.8500", "the other account takes 1 @ 0.85");
        yes.sendModify(21, "0.85", "2.00", "amend down to 2, what has filled");
        yes.sendCancel(21, "cancel, in case anything is still working");

        yes.close();
        raw.abort();
    }

    /**
     * The reader's socket goes down while an order fills and is cancelled: on reconnect, both must be reported from
     * Kalshi's order list, as their user_order updates were sent to a socket that wasn't there. Kept well inside the
     * supervisor's silence timeout, so it doesn't reconnect by itself meanwhile.
     */
    private static void runReconnect(final KalshiCredentials creds, final KalshiCredentials other, final String ticker)
            throws Exception {
        requireDemo(DEMO_API);
        requireDemo(DEMO_WS_HOST);
        final Stack yes = new Stack(creds, listing(1, ticker + ":yes"), "YES");
        Thread.sleep(3_000);

        yes.sendLimit(31, Side.Ask, "0.90", "3.00", TimeInForce.GOOD_TILL_CANCELED, "rest a YES ask of 3");
        System.out.println("\n==== the reader's socket goes down");
        yes.reader.disconnect();
        lift(other, ticker, "0.9000", "while it's down, the other account takes 1 @ 0.90");
        yes.sendCancel(31, "while it's down, cancel the rest through the writer");
        System.out.println("\n==== the reader reconnects and catches up");
        yes.reader.connect();
        Thread.sleep(4_000);
        yes.sendLimit(32, Side.Ask, "0.95", "1.00", TimeInForce.IMMEDIATE_OR_CANCELED, "a later order still goes out");

        yes.close();
    }

    /** Holding no orders, the socket drops and an IOC fills before it's back: the catch-up must still report it. */
    private static void runIdleReconnect(
            final KalshiCredentials creds, final KalshiCredentials other, final String ticker) throws Exception {
        requireDemo(DEMO_API);
        requireDemo(DEMO_WS_HOST);
        final Stack yes = new Stack(creds, listing(1, ticker + ":yes"), "YES");
        Thread.sleep(3_000);

        System.out.println("\n==== the other account rests a YES ask of 1 @ 0.88");
        final String rested = signedRequest(
                other,
                "POST",
                "/trade-api/v2/portfolio/events/orders",
                "{\"ticker\":\"" + ticker
                        + "\",\"client_order_id\":\"cp" + Long.toString(System.nanoTime(), Character.MAX_RADIX)
                        + "\",\"side\":\"ask\",\"price\":\"0.8800\",\"count\":\"1.00\","
                        + "\"time_in_force\":\"good_till_canceled\",\"self_trade_prevention_type\":\"maker\"}");
        final String restedId = JSON.readTree(rested).path("order_id").asText();
        Thread.sleep(2_000);

        System.out.println("\n==== holding no orders, the reader's socket goes down");
        yes.reader.disconnect();
        yes.sendLimit(41, Side.Bid, "0.88", "1.00", TimeInForce.IMMEDIATE_OR_CANCELED, "while it's down, buy 1 @ 0.88");
        Thread.sleep(2_000);
        System.out.println("\n==== the reader reconnects and catches up");
        yes.reader.connect();
        Thread.sleep(4_000);

        System.out.println("\n==== tidy up the other account's ask, if anything is left of it");
        try {
            signedRequest(other, "DELETE", "/trade-api/v2/portfolio/events/orders/" + restedId, null);
        } catch (final IllegalStateException e) {
            System.out.println("nothing left to cancel: our IOC took it all");
        }
        yes.close();
    }

    private static void lift(final KalshiCredentials other, final String ticker, final String price, final String label)
            throws Exception {
        System.out.println("\n==== " + label);
        signedRequest(
                other,
                "POST",
                "/trade-api/v2/portfolio/events/orders",
                "{\"ticker\":\"" + ticker
                        + "\",\"client_order_id\":\"cp" + Long.toString(System.nanoTime(), Character.MAX_RADIX)
                        + "\",\"side\":\"bid\",\"price\":\"" + price + "\",\"count\":\"1.00\","
                        + "\"time_in_force\":\"immediate_or_cancel\",\"self_trade_prevention_type\":\"maker\"}");
        Thread.sleep(6_000);
    }

    // ========== Counterparty (demo, a second account) ==========

    /**
     * A second demo account acting on the market, for the startup recovery test. {@code poke} rests and cancels a
     * far-away order, so a quiet book sends the deltas a session needs before it publishes any market data;
     * {@code lift} buys YES with an IOC at {@code -Dprice}, filling a resting ask nobody is watching.
     */
    private static void runCounterparty(
            final KalshiCredentials creds, final String ticker, final String action, final String price)
            throws Exception {
        requireDemo(DEMO_API);
        if (ticker == null || action == null) {
            throw new IllegalArgumentException("-Ddemo.ticker and -Daction=poke|lift are required");
        }
        final String ordersPath = "/trade-api/v2/portfolio/events/orders";
        final String clientId = "cp" + Long.toString(System.nanoTime(), Character.MAX_RADIX);
        if (action.equals("poke")) {
            final String placed = signedRequest(
                    creds,
                    "POST",
                    ordersPath,
                    "{\"ticker\":\"" + ticker
                            + "\",\"client_order_id\":\"" + clientId + "\",\"side\":\"bid\",\"price\":\"0.0100\","
                            + "\"count\":\"1.00\",\"time_in_force\":\"good_till_canceled\","
                            + "\"self_trade_prevention_type\":\"maker\"}");
            final String orderId = JSON.readTree(placed).path("order_id").asText();
            Thread.sleep(2_000);
            signedRequest(creds, "DELETE", ordersPath + "/" + orderId, null);
        } else if (action.equals("lift")) {
            signedRequest(
                    creds,
                    "POST",
                    ordersPath,
                    "{\"ticker\":\"" + ticker + "\",\"client_order_id\":\""
                            + clientId + "\",\"side\":\"bid\",\"price\":\"" + price + "\",\"count\":\"1.00\","
                            + "\"time_in_force\":\"immediate_or_cancel\",\"self_trade_prevention_type\":\"maker\"}");
        } else {
            throw new IllegalArgumentException("-Daction must be poke or lift");
        }
    }

    private static String signedRequest(
            final KalshiCredentials creds, final String method, final String path, final String body) throws Exception {
        final KalshiAuthSigner signer = new KalshiAuthSigner(creds.apiKey(), creds.privateKey());
        signer.sign(System.currentTimeMillis(), method, path);
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(DEMO_API + path))
                .header("KALSHI-ACCESS-KEY", signer.apiKey())
                .header("KALSHI-ACCESS-TIMESTAMP", signer.timestamp())
                .header("KALSHI-ACCESS-SIGNATURE", signer.signature());
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        final HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        System.out.println(method + " " + path + (body == null ? "" : " " + body) + "\n-> " + response.statusCode()
                + ": " + response.body());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(method + " " + path + " failed");
        }
        return response.body();
    }

    private static String describe(final VenueOrder order) {
        return "exchangeOrderId=" + order.exchangeOrderId() + " venueId=" + order.venueId() + " filled="
                + qty(order.filledQty()) + " notional=" + px(order.filledNotional()) + " fees=" + px(order.fees())
                + " terminal=" + order.terminal();
    }

    /** The real writer, reader and supervisor for one listing, as the trading session wires them. */
    private static final class Stack {
        private final String name;
        private final SequencedRingBuffer<Order> orders = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        private final SequencedRingBuffer<OrderExecutionReport> reports =
                new SequencedRingBuffer<>(OrderExecutionReport::new, new GlobalSequence());
        private GnomeAgentRunner writerRunner;
        private KalshiOutboundReader reader;
        private final String sessionTag;
        private GatewayRunners gatewayRunners;

        Stack(final KalshiCredentials creds, final Listing listing, final String name) throws Exception {
            this.name = name;
            this.sessionTag =
                    "smoke" + Long.toString(System.currentTimeMillis(), Character.MAX_RADIX) + name.toLowerCase();
            final SystemEpochNanoClock nanoClock = new SystemEpochNanoClock();
            final Logger logger = new ConsoleLogger(nanoClock);
            final ManyToOneRingBuffer<OrderContext> newOrderQueue = queue();
            final ManyToOneRingBuffer<OrderContext> writerReportQueue = queue();
            final ManyToOneRingBuffer<OrderContext> releasedOrderQueue = queue();

            this.reports.handleEventsWith((seq, templateId, buffer, length) -> printReport(this.name, buffer));
            this.reports.start();

            final WebSocketClient ws = new WebSocketClientBuilder()
                    .withURI(new URI(DEMO_WS_HOST + MARKET_WS_PATH))
                    .withSocketFactory(new NativeSSLSocketFactory())
                    .withReadBufferSize(1 << 16)
                    .build();
            final KalshiOutboundReader reader = new KalshiOutboundReader(
                    logger,
                    this.reports,
                    newOrderQueue,
                    writerReportQueue,
                    releasedOrderQueue,
                    nanoClock,
                    listing,
                    ws,
                    new JsonDecoder(),
                    new KalshiAuthSigner(creds.apiKey(), creds.privateKey()),
                    new LoggingHttpClient(name + "-catchup"),
                    requireDemo(new URI(DEMO_API).getHost()),
                    0.07,
                    0.0175);
            this.reader = reader;
            final KalshiOutboundWriter writer = new KalshiOutboundWriter(
                    logger,
                    this.orders,
                    newOrderQueue,
                    writerReportQueue,
                    releasedOrderQueue,
                    new LoggingHttpClient(name),
                    requireDemo(new URI(DEMO_API).getHost()),
                    new KalshiAuthSigner(creds.apiKey(), creds.privateKey()),
                    nanoClock,
                    listing,
                    this.sessionTag);
            final OutboundGateway gateway =
                    new OutboundGateway(logger, reader, new GatewayConfig.Builder().build(), new SystemEpochClock());

            // Wired and closed as DefaultOutboundOrchestrator does: the writer stops first, then the gateway.
            this.gatewayRunners = GatewayRunners.venue(
                            reader.pauseControl,
                            new GnomeAgentRunner(reader, Throwable::printStackTrace),
                            new GnomeAgentRunner(gateway, Throwable::printStackTrace))
                    .start();
            this.writerRunner = new GnomeAgentRunner(writer, Throwable::printStackTrace);
            GnomeAgentRunner.startOnThread(this.writerRunner);
        }

        void close() throws Exception {
            this.writerRunner.close();
            closeTimed(this.name + " outbound gateway", this.gatewayRunners);
            this.reports.shutdown();
        }

        void send(final long counter, final Side side, final String price, final String label) throws Exception {
            sendLimit(counter, side, price, "1.00", TimeInForce.IMMEDIATE_OR_CANCELED, label);
        }

        /** The id KalshiOutboundWriter gives the order: its session tag and the OMS counter. */
        String clientOrderId(final long counter) {
            return this.sessionTag + "-" + counter;
        }

        void sendLimit(
                final long counter,
                final Side side,
                final String price,
                final String size,
                final TimeInForce tif,
                final String label)
                throws Exception {
            System.out.println("\n==== " + label + ": " + side + " " + size + " @ " + price + " " + tif
                    + " (client oid " + counter + ")");
            final Order order = this.orders.claim();
            order.encoder.exchangeId(2);
            order.encoder.securityId(3);
            order.encoder.price(Math.round(Double.parseDouble(price) * Statics.PRICE_SCALING_FACTOR));
            order.encoder.size(Math.round(Double.parseDouble(size) * Statics.SIZE_SCALING_FACTOR));
            order.encoder.side(side);
            order.encoder.orderType(OrderType.LIMIT);
            order.encoder.timeInForce(tif);
            order.encoder.flags().clear();
            order.encodeClientOid(counter, 1);
            this.orders.publish();
            Thread.sleep(6_000);
        }

        void sendModify(final long counter, final String price, final String size, final String label)
                throws Exception {
            System.out.println("\n==== " + label + " (client oid " + counter + ")");
            final ModifyOrder modify = new ModifyOrder();
            modify.encoder.exchangeId(2);
            modify.encoder.securityId(3);
            modify.encoder.price(Math.round(Double.parseDouble(price) * Statics.PRICE_SCALING_FACTOR));
            modify.encoder.size(Math.round(Double.parseDouble(size) * Statics.SIZE_SCALING_FACTOR));
            modify.encoder.orderType(OrderType.LIMIT);
            modify.encoder.timeInForce(TimeInForce.GOOD_TILL_CANCELED);
            modify.encoder.flags().clear();
            modify.encodeClientOid(counter, 1);
            this.orders.publishRaw(modify.buffer, ModifyOrderDecoder.TEMPLATE_ID, modify.totalMessageSize());
            Thread.sleep(6_000);
        }

        void sendCancel(final long counter, final String label) throws Exception {
            System.out.println("\n==== " + label + " (client oid " + counter + ")");
            final CancelOrder cancel = new CancelOrder();
            cancel.encoder.exchangeId(2);
            cancel.encoder.securityId(3);
            cancel.encodeClientOid(counter, 1);
            this.orders.publishRaw(cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, cancel.totalMessageSize());
            Thread.sleep(6_000);
        }

        private static ManyToOneRingBuffer<OrderContext> queue() {
            return new ManyToOneRingBuffer<>(
                    OrderContext[]::new, OrderContext::new, OrderContext.HANDOFF_QUEUE_CAPACITY);
        }
    }

    /** The writer reports only a rejection's status; this prints what Kalshi said and what was sent. */
    private static final class LoggingHttpClient extends HTTPClient {
        private final String stack;
        // -Dlose.submits: how many order submits have their answer thrown away after Kalshi took them, as a
        // timeout would, so the writer has to find out by itself whether the order exists.
        private int submitsToLose = Integer.getInteger("lose.submits", 0);

        LoggingHttpClient(final String stack) {
            this.stack = stack;
        }

        @Override
        public HTTPResponse post(
                final HTTPProtocol protocol,
                final String host,
                final GnomeString path,
                final byte[] body,
                final int bodyLength,
                final String headerKey1,
                final String headerValue1,
                final String headerKey2,
                final String headerValue2,
                final String headerKey3,
                final String headerValue3,
                final String headerKey4,
                final String headerValue4)
                throws IOException {
            final HTTPResponse response = super.post(
                    protocol,
                    host,
                    path,
                    body,
                    bodyLength,
                    headerKey1,
                    headerValue1,
                    headerKey2,
                    headerValue2,
                    headerKey3,
                    headerValue3,
                    headerKey4,
                    headerValue4);
            final ByteBuffer responseBody = response.getBody();
            if (this.submitsToLose > 0 && !path.toString().endsWith("/amend")) {
                this.submitsToLose--;
                System.out.println("HTTP " + this.stack + " POST " + host + path + " -> " + response.getStatusCode()
                        + " (answer thrown away, as if it timed out)");
                throw new IOException("simulated lost response");
            }
            System.out.println("HTTP " + this.stack + " POST " + host + path + " headers=[" + headerKey1 + ", "
                    + headerKey2 + ", " + headerKey3 + ", " + headerKey4 + "=" + headerValue4 + "] body="
                    + new String(body, 0, bodyLength, StandardCharsets.UTF_8)
                    + "\n  -> " + response.getStatusCode() + ": "
                    + (responseBody == null ? "" : StandardCharsets.UTF_8.decode(responseBody.duplicate())));
            return response;
        }

        @Override
        public HTTPResponse get(
                final HTTPProtocol protocol,
                final String host,
                final String path,
                final String headerKey1,
                final String headerValue1,
                final String headerKey2,
                final String headerValue2,
                final String headerKey3,
                final String headerValue3)
                throws IOException {
            final HTTPResponse response = super.get(
                    protocol, host, path, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3);
            System.out.println("HTTP " + this.stack + " GET " + host + path + "\n  -> " + response.getStatusCode()
                    + ": " + truncate(response.getBody()));
            return response;
        }

        @Override
        public HTTPResponse delete(
                final HTTPProtocol protocol,
                final String host,
                final String path,
                final String headerKey1,
                final String headerValue1,
                final String headerKey2,
                final String headerValue2,
                final String headerKey3,
                final String headerValue3)
                throws IOException {
            final HTTPResponse response = super.delete(
                    protocol, host, path, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3);
            System.out.println("HTTP " + this.stack + " DELETE " + host + path + "\n  -> " + response.getStatusCode()
                    + ": " + truncate(response.getBody()));
            return response;
        }

        @Override
        public HTTPResponse delete(
                final HTTPProtocol protocol,
                final String host,
                final GnomeString path,
                final String headerKey1,
                final String headerValue1,
                final String headerKey2,
                final String headerValue2,
                final String headerKey3,
                final String headerValue3)
                throws IOException {
            final HTTPResponse response = super.delete(
                    protocol, host, path, headerKey1, headerValue1, headerKey2, headerValue2, headerKey3, headerValue3);
            System.out.println("HTTP " + this.stack + " DELETE " + host + path + "\n  -> " + response.getStatusCode()
                    + ": " + truncate(response.getBody()));
            return response;
        }
    }

    private static String truncate(final ByteBuffer body) {
        if (body == null) {
            return "";
        }
        final String text = StandardCharsets.UTF_8.decode(body.duplicate()).toString();
        return text.length() > 2500 ? text.substring(0, 2500) + " ...(truncated)" : text;
    }

    private static void printReport(final String stack, final org.agrona.concurrent.UnsafeBuffer buffer) {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.wrap(buffer);
        final OrderExecutionReportDecoder d = report.decoder;
        final StringBuilder id = new StringBuilder();
        for (int i = 0; i < OrderExecutionReportEncoder.exchangeOrderIdLength(); i++) {
            final byte b = d.exchangeOrderId(i);
            if (b == 0) {
                break;
            }
            id.append((char) b);
        }
        System.out.println("REPORT " + stack + " oid=" + report.getClientOidCounter()
                + " exec=" + d.execType() + " status=" + d.orderStatus() + " reject=" + d.rejectReason()
                + " filled=" + nullableQty(d.filledQty(), OrderExecutionReportDecoder.filledQtyNullValue())
                + " fillPrice=" + nullablePx(d.fillPrice(), OrderExecutionReportDecoder.fillPriceNullValue())
                + " cum=" + qty(d.cumulativeQty()) + " leaves=" + qty(d.leavesQty())
                + " fee=" + nullablePx(d.fee(), OrderExecutionReportDecoder.feeNullValue())
                + " liquidity=" + d.liquidity() + " exchangeOrderId=" + id);
    }

    // ========== REST and raw WebSocket helpers ==========

    /**
     * The market list reports 0.01/0.99 for an empty book, so candidates are ranked by volume and only those whose
     * orderbook actually holds orders on both sides are shown.
     */
    private static void listOpenMarkets(final KalshiCredentials creds, final String apiBase) throws Exception {
        final List<JsonNode> markets = new ArrayList<>();
        String cursor = "";
        for (int page = 0; page < 10; page++) {
            final JsonNode body = JSON.readTree(signedGet(
                    creds,
                    apiBase,
                    "/trade-api/v2/markets",
                    "?status=open&limit=1000" + (cursor.isEmpty() ? "" : "&cursor=" + cursor)));
            body.path("markets").forEach(markets::add);
            cursor = body.path("cursor").asText("");
            if (cursor.isEmpty()) {
                break;
            }
        }
        markets.sort((a, b) -> Double.compare(volume(b), volume(a)));
        System.out.println("Scanned " + markets.size() + " open markets. Most traded with orders on both sides"
                + " on shard " + Integer.getInteger("shard", 0) + " (pass one with -D...ticker=):");
        int checked = 0;
        int shown = 0;
        for (final JsonNode market : markets) {
            if (shown >= 15 || checked >= 60) {
                break;
            }
            // Collateral is per exchange shard: list only the shard the balance is on.
            if (market.path("exchange_index").asInt(0) != Integer.getInteger("shard", 0)) {
                continue;
            }
            checked++;
            final String ticker = market.path("ticker").asText();
            final JsonNode book = JSON.readTree(
                            signedGet(creds, apiBase, "/trade-api/v2/markets/" + ticker + "/orderbook", "?depth=1"))
                    .path("orderbook_fp");
            if (book.path("yes_dollars").isEmpty() || book.path("no_dollars").isEmpty()) {
                continue;
            }
            System.out.println(ticker + "  shard "
                    + market.path("exchange_index").asText("?") + "  tick "
                    + market.path("price_level_structure").asText("?")
                    + "  volume_24h=" + market.path("volume_24h_fp").asText("?")
                    + "  best yes "
                    + book.path("yes_dollars").get(book.path("yes_dollars").size() - 1)
                    + "  best no "
                    + book.path("no_dollars").get(book.path("no_dollars").size() - 1));
            shown++;
        }
        if (shown == 0) {
            System.out.println("(none of the " + checked + " most traded markets has orders on both sides)");
        }
    }

    private static double volume(final JsonNode market) {
        final String v24 = market.path("volume_24h_fp").asText("");
        final String total = market.path("volume_fp").asText("");
        try {
            return Double.parseDouble(v24.isEmpty() ? (total.isEmpty() ? "0" : total) : v24) * 1e6
                    + (total.isEmpty() ? 0 : Double.parseDouble(total));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    private static void printRestOrderbook(final KalshiCredentials creds, final String apiBase, final String ticker)
            throws Exception {
        final String body = signedGet(creds, apiBase, "/trade-api/v2/markets/" + ticker + "/orderbook", "?depth=10");
        System.out.println(body.length() > 3000 ? body.substring(0, 3000) + " ...(truncated)" : body);
    }

    private static String signedGet(
            final KalshiCredentials creds, final String apiBase, final String path, final String query)
            throws Exception {
        final KalshiAuthSigner signer = new KalshiAuthSigner(creds.apiKey(), creds.privateKey());
        signer.sign(System.currentTimeMillis(), "GET", path);
        final HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path + query))
                .header("KALSHI-ACCESS-KEY", signer.apiKey())
                .header("KALSHI-ACCESS-TIMESTAMP", signer.timestamp())
                .header("KALSHI-ACCESS-SIGNATURE", signer.signature())
                .GET()
                .build();
        final HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("GET " + path + " -> " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static WebSocket openRaw(
            final KalshiCredentials creds, final String url, final String signedPath, final String label)
            throws Exception {
        final KalshiAuthSigner signer = new KalshiAuthSigner(creds.apiKey(), creds.privateKey());
        signer.sign(System.currentTimeMillis(), "GET", signedPath);
        return HTTP.newWebSocketBuilder()
                .header("KALSHI-ACCESS-KEY", signer.apiKey())
                .header("KALSHI-ACCESS-TIMESTAMP", signer.timestamp())
                .header("KALSHI-ACCESS-SIGNATURE", signer.signature())
                .buildAsync(URI.create(url), new PrintingListener(label))
                .join();
    }

    private static final class PrintingListener implements WebSocket.Listener {
        private final String label;
        private final StringBuilder partial = new StringBuilder();

        PrintingListener(final String label) {
            this.label = label;
        }

        @Override
        public CompletionStage<?> onText(final WebSocket webSocket, final CharSequence data, final boolean last) {
            this.partial.append(data);
            if (last) {
                System.out.println(this.label + ": " + this.partial);
                this.partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(final WebSocket webSocket, final Throwable error) {
            System.out.println(this.label + " error: " + error);
        }
    }

    // ========== Shared ==========

    private static KalshiCredentials credentials(final String profile, final String secretId) {
        try (SecretsManagerClient secrets = SecretsManagerClient.builder()
                .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
                .credentialsProvider(ProfileCredentialsProvider.create(profile))
                .build()) {
            return KalshiCredentials.fromJson(secrets.getSecretValue(
                            GetSecretValueRequest.builder().secretId(secretId).build())
                    .secretString());
        }
    }

    /** A key downloaded from Kalshi's site: its id, and the private key PEM file it came with. */
    private static KalshiCredentials credentialsFromFile(final String keyId, final String pemPath) throws Exception {
        if (keyId == null) {
            throw new IllegalArgumentException("-Ddemo.keyId is required with -Ddemo.keyFile");
        }
        final String pem = Files.readString(Path.of(pemPath.replaceFirst("^~", System.getProperty("user.home"))));
        return KalshiCredentials.fromJson(
                JSON.writeValueAsString(Map.of("apiKey", keyId, "privateKey", toPkcs8Pem(pem))));
    }

    /** Kalshi hands out PKCS#1 keys ({@code BEGIN RSA PRIVATE KEY}); KalshiCredentials reads PKCS#8. */
    private static String toPkcs8Pem(final String pem) throws Exception {
        if (!pem.contains("BEGIN RSA PRIVATE KEY")) {
            return pem;
        }
        final byte[] pkcs1 = Base64.getDecoder()
                .decode(pem.replace("-----BEGIN RSA PRIVATE KEY-----", "")
                        .replace("-----END RSA PRIVATE KEY-----", "")
                        .replaceAll("\\s+", ""));
        final byte[] pkcs8 = new PrivateKeyInfo(
                        new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE),
                        ASN1Primitive.fromByteArray(pkcs1))
                .getEncoded();
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pkcs8)
                + "\n-----END PRIVATE KEY-----";
    }

    /** Shutdown used to hang here; prints how long it took so a regression is obvious. */
    private static void closeTimed(final String what, final GatewayRunners runners) throws Exception {
        final long start = System.nanoTime();
        runners.close();
        System.out.println("== Closed " + what + " in " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }

    private static Listing listing(final int listingId, final String exchangeSecurityId) {
        return new Listing(
                listingId,
                new Exchange(2, "KALSHI", "Kalshi", "global", SchemaType.MBP_10),
                new Security(3, exchangeSecurityId, null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                exchangeSecurityId,
                exchangeSecurityId);
    }

    private static String requireDemo(final String hostOrUrl) {
        final String host = hostOrUrl.contains("://") ? URI.create(hostOrUrl).getHost() : hostOrUrl;
        if (host == null || !host.endsWith(DEMO_HOST_SUFFIX)) {
            throw new IllegalStateException("Refusing to place orders on a non-demo host: " + hostOrUrl);
        }
        return host;
    }

    private static String px(final long price) {
        return String.format("%.4f", (double) price / Statics.PRICE_SCALING_FACTOR);
    }

    private static String qty(final long size) {
        return String.format("%.2f", (double) size / Statics.SIZE_SCALING_FACTOR);
    }

    private static String nullablePx(final long price, final long nullValue) {
        return price == nullValue ? "-" : px(price);
    }

    private static String nullableQty(final long size, final long nullValue) {
        return size == nullValue ? "-" : qty(size);
    }

    static {
        // Kalshi's REST bodies are plain UTF-8; keep the console output readable on any platform default.
        System.setProperty("file.encoding", StandardCharsets.UTF_8.name());
    }
}
