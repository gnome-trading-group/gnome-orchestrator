package group.gnometrading.gateways.outbound;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.GatewayRunners;
import group.gnometrading.gateways.credentials.PolymarketUsCredentials;
import group.gnometrading.gateways.outbound.exchanges.polymarket.us.PolymarketUsAuthSigner;
import group.gnometrading.gateways.outbound.exchanges.polymarket.us.PolymarketUsListing;
import group.gnometrading.gateways.outbound.exchanges.polymarket.us.PolymarketUsMarketInfo;
import group.gnometrading.gateways.outbound.exchanges.polymarket.us.PolymarketUsOutboundReader;
import group.gnometrading.gateways.outbound.exchanges.polymarket.us.PolymarketUsOutboundWriter;
import group.gnometrading.gateways.outbound.exchanges.polymarket.us.PolymarketUsVenueOrderQuery;
import group.gnometrading.gateways.ratelimit.VenueRateLimiter;
import group.gnometrading.gateways.ratelimit.VenueRateLimiters;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
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
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
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
import group.gnometrading.strings.GnomeString;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.SystemNanoClock;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Drives the Polymarket US outbound gateway against the real venue, with real money, in small amounts. Credentials
 * come from the dev secret and are never printed. {@code -Dslug} is the market; {@code -Dside=short} trades its NO
 * listing.
 *
 * <p>Modes ({@code -Dmode}): {@code rest} rests a BUY far below the market and cancels it; {@code ioc} buys at
 * {@code -Dbuy.price} and sells back at {@code -Dsell.price}; {@code reconnect} rests an order, drops the reader's
 * socket, cancels and reconnects; {@code lose} rests one whose submit answer is thrown away, then cancels it;
 * {@code lose-ioc} buys and sells back as {@code ioc} does, throwing away both answers; {@code maker} rests a
 * post-only BUY at {@code -Dmaker.price} for {@code -Dwait.seconds}; {@code one} sends a single IOC
 * ({@code -Dorder.side=bid|ask}, {@code -Dprice}, {@code -Dsize}); {@code status} prints the market's open orders.
 */
public final class PolymarketUsLiveSmoke {

    private static final String API_HOST = "api.polymarket.us";
    private static final String GATEWAY_HOST = "gateway.polymarket.us";
    private static final String PRIVATE_WS = "wss://api.polymarket.us/v1/ws/private";
    private static final Set<String> ALLOWED_HOSTS = Set.of(API_HOST, GATEWAY_HOST);
    // No single order in these tests may spend more than this.
    private static final double MAX_BUY_DOLLARS = 3.0;

    private PolymarketUsLiveSmoke() {}

    public static void main(final String[] args) throws Exception {
        final String mode = System.getProperty("mode", "rest");
        final PolymarketUsCredentials credentials = credentials();
        final Listing listing = listing(required("slug") + ":" + System.getProperty("side", "long"));
        final PolymarketUsMarketInfo marketInfo = PolymarketUsMarketInfo.load(
                new HTTPClient(), GATEWAY_HOST, new PolymarketUsListing(listing).marketSlug());
        System.out.println("market " + listing.exchangeSecurityId() + ": " + marketInfo);

        if (mode.equals("status")) {
            final PolymarketUsVenueOrderQuery query = new PolymarketUsVenueOrderQuery(
                    new LoggingHttpClient("status", 0), API_HOST, signer(credentials), System::currentTimeMillis);
            System.out.println("open orders: " + query.listOpenOrders(listing));
            if (System.getProperty("order") != null) {
                System.out.println("order: " + query.getOrder(listing, required("order"), 0));
            }
            return;
        }
        if (mode.equals("ratelimit")) {
            rateLimitBurst(credentials, marketInfo);
            return;
        }
        if (mode.equals("idle")) {
            idleThenOrder(credentials, listing, marketInfo, Integer.getInteger("idle", 300));
            return;
        }
        final int submitsToLose = mode.startsWith("lose") ? (mode.equals("lose-ioc") ? 2 : 1) : 0;
        final Stack stack = new Stack(credentials, listing, marketInfo, submitsToLose, null, "");
        Thread.sleep(4_000);
        try {
            switch (mode) {
                case "rest" -> {
                    stack.send(
                            1,
                            Side.Bid,
                            System.getProperty("price", "0.010"),
                            System.getProperty("size", "1"),
                            TimeInForce.GOOD_TILL_CANCELED,
                            "rest a BUY far below");
                    stack.cancel(1, "cancel it");
                }
                case "outage-fill" -> {
                    System.out.println("\n==== the reader's socket goes down");
                    stack.reader.disconnect();
                    stack.send(
                            61,
                            Side.Bid,
                            required("buy.price"),
                            System.getProperty("size", "1"),
                            TimeInForce.GOOD_TILL_CANCELED,
                            "while it's down, a BUY that crosses and fills");
                    System.out.println("\n==== the reader reconnects and catches up");
                    stack.reader.connect();
                    Thread.sleep(6_000);
                    stack.send(
                            62,
                            Side.Ask,
                            required("sell.price"),
                            System.getProperty("size", "1"),
                            TimeInForce.IMMEDIATE_OR_CANCELED,
                            "sell back");
                }
                case "multi" -> {
                    for (int i = 0; i < 5; i++) {
                        stack.send(
                                80 + i,
                                Side.Bid,
                                String.format("%.3f", 0.010 + 0.001 * i),
                                "1",
                                TimeInForce.GOOD_TILL_CANCELED,
                                false,
                                "rest #" + i,
                                1_000);
                    }
                    Thread.sleep(4_000);
                    for (int i = 4; i >= 0; i--) {
                        stack.cancel(80 + i, "cancel #" + i, 1_000);
                    }
                    Thread.sleep(6_000);
                }
                case "burst" -> {
                    for (int i = 0; i < 10; i++) {
                        stack.send(
                                90 + i,
                                Side.Bid,
                                "0.010",
                                "1",
                                TimeInForce.IMMEDIATE_OR_CANCELED,
                                false,
                                "identical IOC #" + i,
                                0);
                    }
                    Thread.sleep(10_000);
                }
                case "big" -> {
                    // Rests far below the market: the venue cuts it to what the balance covers, and nothing fills.
                    stack.allowBigBuys = true;
                    stack.send(
                            95,
                            Side.Bid,
                            "0.050",
                            "1000",
                            TimeInForce.GOOD_TILL_CANCELED,
                            "a BUY the balance can't cover");
                    stack.cancel(95, "cancel it");
                }
                case "modify" -> {
                    final String price = System.getProperty("price", "0.010");
                    stack.allowBigBuys = true;
                    stack.send(
                            1,
                            Side.Bid,
                            price,
                            System.getProperty("size", "3"),
                            TimeInForce.GOOD_TILL_CANCELED,
                            "rest a BUY");
                    stack.modify(
                            1, System.getProperty("up.price", "0.011"), System.getProperty("up.size", "4"), "amend up");
                    stack.modify(
                            1,
                            System.getProperty("up.price", "0.011"),
                            System.getProperty("down.size", "2"),
                            "amend down");
                    stack.cancel(1, "cancel what's left");
                }
                case "fok" -> {
                    stack.send(71, Side.Bid, "0.010", "1", TimeInForce.FILL_OR_KILL, "FOK that can't fill");
                    stack.send(72, Side.Bid, required("buy.price"), "1", TimeInForce.FILL_OR_KILL, "FOK that fills");
                    stack.send(
                            73, Side.Ask, required("sell.price"), "1", TimeInForce.IMMEDIATE_OR_CANCELED, "sell back");
                }
                case "ioc", "lose-ioc" -> {
                    final String size = System.getProperty("size", "1");
                    stack.send(11, Side.Bid, required("buy.price"), size, TimeInForce.IMMEDIATE_OR_CANCELED, "buy");
                    stack.send(
                            12,
                            Side.Ask,
                            required("sell.price"),
                            System.getProperty("sell.size", size),
                            TimeInForce.IMMEDIATE_OR_CANCELED,
                            "sell back");
                }
                case "reconnect" -> {
                    stack.send(31, Side.Bid, "0.010", "1", TimeInForce.GOOD_TILL_CANCELED, "rest a BUY far below");
                    System.out.println("\n==== the reader's socket goes down");
                    stack.reader.disconnect();
                    stack.cancel(31, "while it's down, cancel through the writer");
                    System.out.println("\n==== the reader reconnects and catches up");
                    stack.reader.connect();
                    Thread.sleep(6_000);
                }
                case "one" -> {
                    // One IOC on its own, to see what the venue makes of an order with nothing behind it.
                    final Side side = required("order.side").equals("ask") ? Side.Ask : Side.Bid;
                    stack.send(
                            61,
                            side,
                            required("price"),
                            System.getProperty("size", "1"),
                            TimeInForce.IMMEDIATE_OR_CANCELED,
                            "one IOC " + side);
                    Thread.sleep(3_000);
                }
                case "lose" -> {
                    stack.send(41, Side.Bid, "0.010", "1", TimeInForce.GOOD_TILL_CANCELED, "rest, answer lost");
                    stack.cancel(41, "cancel it: the writer must have found its venue id");
                }
                case "maker" -> {
                    stack.send(
                            51,
                            Side.Bid,
                            required("maker.price"),
                            System.getProperty("maker.size", "1"),
                            TimeInForce.GOOD_TILL_CANCELED,
                            true,
                            "rest a post-only BUY at the touch");
                    Thread.sleep(Long.getLong("wait.seconds", 300) * 1_000L);
                    stack.cancel(51, "cancel what's left");
                }
                default -> throw new IllegalArgumentException("unknown -Dmode=" + mode);
            }
        } finally {
            stack.close();
        }
    }

    /**
     * {@code -Dgateways} gateways on one account, each with its own session and each sending {@code -Dcount} post-only
     * bids of one share at a cent from its own thread, through one shared rate limiter as a trading session wires them.
     * A writer sends one request at a time, so it takes several to outrun the budget. Then every order is cancelled.
     * Counts what the OMS would have heard.
     */
    private static void rateLimitBurst(
            final PolymarketUsCredentials credentials, final PolymarketUsMarketInfo marketInfo) throws Exception {
        final int gateways = Integer.getInteger("gateways", 4);
        final int count = Integer.getInteger("count", 20);
        // IOC bids at a cent find nothing to match and end at once, so they tie up no balance.
        final TimeInForce tif =
                Boolean.getBoolean("ioc") ? TimeInForce.IMMEDIATE_OR_CANCELED : TimeInForce.GOOD_TILL_CANCELED;
        final Listing listing = listing(required("slug") + ":long");
        final VenueRateLimiter limiter = new VenueRateLimiters(
                        new ConsoleLogger(new SystemEpochNanoClock()), SystemNanoClock.INSTANCE, new SystemEpochClock())
                .forAccount(VenueRateLimits.polymarketUs(), credentials.apiKey());
        final Stack[] stacks = new Stack[gateways];
        for (int g = 0; g < gateways; g++) {
            // -Dungated=true is the control: the same burst with nothing holding it back, to see what the venue does.
            stacks[g] = new Stack(
                    credentials, listing, marketInfo, 0, Boolean.getBoolean("ungated") ? null : limiter, "-" + g);
        }
        Thread.sleep(4_000);
        try {
            final long start = System.nanoTime();
            runOnEach(stacks, (stack, g) -> {
                for (int i = 0; i < count; i++) {
                    final long oid = 1_000L * (g + 1) + i;
                    stack.send(oid, Side.Bid, "0.01", "1", tif, tif == TimeInForce.GOOD_TILL_CANCELED, "bid " + oid, 0);
                }
            });
            final long deadline = System.nanoTime() + 30_000_000_000L;
            while (finished() < gateways * count && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            final long millis = (System.nanoTime() - start) / 1_000_000;
            System.out.println("\n#### " + finished() + " of " + gateways * count + " orders finished in " + millis
                    + " ms, about "
                    + (finished() - count(ExecType.REJECT + "/RATE_LIMITED")) * 1000L / Math.max(1, millis)
                    + " sent per second");
            Thread.sleep(5_000);
            System.out.println("#### after the burst: " + REPORT_COUNTS);
            runOnEach(stacks, (stack, g) -> {
                for (int i = 0; i < count; i++) {
                    final long oid = 1_000L * (g + 1) + i;
                    stack.cancel(oid, "cancel " + oid, 0);
                }
            });
            Thread.sleep(15_000);
            System.out.println("#### after the cancels: " + REPORT_COUNTS);
        } finally {
            for (final Stack stack : stacks) {
                stack.close();
            }
        }
        final PolymarketUsVenueOrderQuery query = new PolymarketUsVenueOrderQuery(
                OrderEntryHttpClients.create(limiter), API_HOST, signer(credentials), System::currentTimeMillis);
        System.out.println("#### still open: " + query.listOpenOrders(listing));
    }

    private interface StackWork {
        void run(Stack stack, int index) throws Exception;
    }

    private static void runOnEach(final Stack[] stacks, final StackWork work) throws Exception {
        final Thread[] threads = new Thread[stacks.length];
        for (int g = 0; g < stacks.length; g++) {
            final int index = g;
            threads[g] = new Thread(() -> {
                try {
                    work.run(stacks[index], index);
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            });
            threads[g].start();
        }
        for (final Thread thread : threads) {
            thread.join();
        }
    }

    private static final Map<String, AtomicInteger> REPORT_COUNTS = new ConcurrentSkipListMap<>();
    // When each order's acknowledgement arrived, by client oid, to time the order from being published.
    private static final Map<Long, Long> ACKED_NANOS = new ConcurrentHashMap<>();

    /**
     * Whether an order after a quiet spell is lost to a connection that died while idle: a post-only bid of one share
     * at a cent, cancelled, then {@code idleSeconds} with nothing sent, then the same again, each timed from
     * publishing to the venue's acknowledgement. Through Cloudflare an idle connection was silently dead by five
     * minutes.
     */
    private static void idleThenOrder(
            final PolymarketUsCredentials credentials,
            final Listing listing,
            final PolymarketUsMarketInfo marketInfo,
            final int idleSeconds)
            throws Exception {
        final VenueRateLimiter limiter = new VenueRateLimiters(
                        new ConsoleLogger(new SystemEpochNanoClock()), SystemNanoClock.INSTANCE, new SystemEpochClock())
                .forAccount(VenueRateLimits.polymarketUs(), credentials.apiKey());
        final Stack stack = new Stack(credentials, listing, marketInfo, 0, limiter, "");
        Thread.sleep(4_000);
        try {
            final long warm = timeBid(stack, 1);
            stack.cancel(1, "cancel 1", 3_000);
            System.out.println("\n#### idle for " + idleSeconds + " s");
            Thread.sleep(idleSeconds * 1_000L);
            final long afterIdle = timeBid(stack, 2);
            stack.cancel(2, "cancel 2", 5_000);
            System.out.println("\n#### publish to ack: warm " + warm / 1_000_000 + " ms, after " + idleSeconds
                    + " s idle " + afterIdle / 1_000_000 + " ms; reports " + REPORT_COUNTS);
        } finally {
            stack.close();
        }
        final PolymarketUsVenueOrderQuery query = new PolymarketUsVenueOrderQuery(
                OrderEntryHttpClients.create(limiter), API_HOST, signer(credentials), System::currentTimeMillis);
        System.out.println("#### still open: " + query.listOpenOrders(listing));
    }

    private static long timeBid(final Stack stack, final long oid) throws Exception {
        final long start = System.nanoTime();
        stack.send(oid, Side.Bid, "0.01", "1", TimeInForce.GOOD_TILL_CANCELED, true, "bid " + oid, 0);
        final long deadline = start + 20_000_000_000L;
        while (!ACKED_NANOS.containsKey(oid) && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        final Long acked = ACKED_NANOS.get(oid);
        return acked == null ? -1 : acked - start;
    }

    /** Orders with a final report: for IOCs, every one sent or refused. */
    private static int finished() {
        return count(ExecType.CANCEL.name())
                + count(ExecType.FILL.name())
                + count(ExecType.EXPIRE.name())
                + REPORT_COUNTS.entrySet().stream()
                        .filter(e -> e.getKey().startsWith(ExecType.REJECT.name() + "/"))
                        .mapToInt(e -> e.getValue().get())
                        .sum();
    }

    private static int count(final String key) {
        final AtomicInteger value = REPORT_COUNTS.get(key);
        return value == null ? 0 : value.get();
    }

    private static final class Stack {
        private final SequencedRingBuffer<Order> orders = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        private final SequencedRingBuffer<OrderExecutionReport> reports =
                new SequencedRingBuffer<>(OrderExecutionReport::new, new GlobalSequence());
        // Printed from a thread of its own, as reports arrive, while the main thread drives the orders.
        private GnomeAgentRunner reportsRunner;
        private final GnomeAgentRunner writerRunner;
        private final GatewayRunners gatewayRunners;
        final PolymarketUsOutboundReader reader;
        boolean allowBigBuys;

        Stack(
                final PolymarketUsCredentials credentials,
                final Listing listing,
                final PolymarketUsMarketInfo marketInfo,
                final int submitsToLose,
                final VenueRateLimiter rateLimiter,
                final String sessionSuffix)
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
                    .withURI(new URI(PRIVATE_WS))
                    .withSocketFactory(new NativeSSLSocketFactory())
                    .withReadBufferSize(1 << 16)
                    .build();
            this.reader = new PolymarketUsOutboundReader(
                    logger,
                    this.reports,
                    newOrderQueue,
                    writerReportQueue,
                    releasedOrderQueue,
                    nanoClock,
                    listing,
                    ws,
                    new JsonDecoder(),
                    signer(credentials),
                    new PolymarketUsVenueOrderQuery(
                            rateLimiter == null
                                    ? new LoggingHttpClient("catchup", 0)
                                    : OrderEntryHttpClients.create(rateLimiter),
                            API_HOST,
                            signer(credentials),
                            System::currentTimeMillis));
            final String sessionTag = "smoke-" + System.currentTimeMillis() + sessionSuffix;
            System.out.println("session " + sessionTag);
            final PolymarketUsOutboundWriter writer = new PolymarketUsOutboundWriter(
                    logger,
                    this.orders,
                    newOrderQueue,
                    writerReportQueue,
                    releasedOrderQueue,
                    rateLimiter == null
                            ? new LoggingHttpClient("writer", submitsToLose)
                            : OrderEntryHttpClients.create(rateLimiter),
                    API_HOST,
                    signer(credentials),
                    marketInfo,
                    nanoClock,
                    listing,
                    sessionTag);
            final GatewayConfig config = new GatewayConfig.Builder()
                    .withMaxSilentInterval(Duration.ofSeconds(30))
                    .build();
            final OutboundGateway gateway = new OutboundGateway(logger, this.reader, config, new SystemEpochClock());
            this.gatewayRunners = GatewayRunners.venue(
                            listing.listingId(),
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
            send(counter, side, price, size, tif, postOnly, label, 8_000);
        }

        void send(
                final long counter,
                final Side side,
                final String price,
                final String size,
                final TimeInForce tif,
                final boolean postOnly,
                final String label,
                final long waitMillis)
                throws Exception {
            final double dollars = Double.parseDouble(price) * Double.parseDouble(size);
            if (side == Side.Bid && dollars > MAX_BUY_DOLLARS && !this.allowBigBuys) {
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
            Thread.sleep(waitMillis);
        }

        void modify(final long counter, final String price, final String size, final String label) throws Exception {
            System.out.println("\n==== " + label + ": " + size + " @ " + price + " (client oid " + counter + ")");
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
            Thread.sleep(8_000);
        }

        void cancel(final long counter, final String label) throws Exception {
            cancel(counter, label, 8_000);
        }

        void cancel(final long counter, final String label, final long waitMillis) throws Exception {
            System.out.println("\n==== " + label + " (client oid " + counter + ")");
            final CancelOrder cancel = new CancelOrder();
            cancel.encoder.exchangeId(2);
            cancel.encoder.securityId(3);
            cancel.encodeClientOid(counter, 1);
            this.orders.publishRaw(cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, cancel.totalMessageSize());
            Thread.sleep(waitMillis);
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

    /** Prints each request and the venue's answer, without the auth headers' values. */
    private static final class LoggingHttpClient extends HTTPClient {
        private final String role;
        private int submitsToLose;

        LoggingHttpClient(final String role, final int submitsToLose) {
            this.role = role;
            this.submitsToLose = submitsToLose;
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
                final String v4)
                throws IOException {
            requireVenue(host);
            final HTTPResponse response =
                    super.post(protocol, host, path, body, bodyLength, k1, v1, k2, v2, k3, v3, k4, v4);
            final boolean submit = path.toString().equals("/v1/orders");
            if (submit && this.submitsToLose > 0) {
                this.submitsToLose--;
                System.out.println("HTTP " + this.role + " POST " + path + " -> " + response.getStatusCode()
                        + " (answer thrown away, as if it timed out)");
                throw new IOException("simulated lost response");
            }
            System.out.println("HTTP " + this.role + " POST " + path + " body="
                    + new String(body, 0, bodyLength, StandardCharsets.UTF_8) + "\n  -> " + response.getStatusCode()
                    + ": " + text(response.getBody()));
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
                final String v3)
                throws IOException {
            requireVenue(host);
            final HTTPResponse response = super.get(protocol, host, path, k1, v1, k2, v2, k3, v3);
            System.out.println("HTTP " + this.role + " GET " + path + "\n  -> " + response.getStatusCode() + ": "
                    + text(response.getBody()));
            return response;
        }
    }

    private static void requireVenue(final String host) {
        if (!ALLOWED_HOSTS.contains(host)) {
            throw new IllegalStateException("Refusing a request to " + host);
        }
    }

    /** The venue's answer without the market metadata every order repeats. */
    private static String text(final ByteBuffer body) {
        if (body == null) {
            return "";
        }
        final String text = StandardCharsets.UTF_8
                .decode(body.duplicate())
                .toString()
                .replaceAll("\"marketMetadata\":\\{\"slug\".*?\"eventId\":\"[0-9]*\"\\}", "\"marketMetadata\":{...}");
        return text.length() > 3000 ? text.substring(0, 3000) + " ...(truncated)" : text;
    }

    private static void printReport(final org.agrona.concurrent.UnsafeBuffer buffer) {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.wrap(buffer);
        final OrderExecutionReportDecoder d = report.decoder;
        final StringBuilder id = new StringBuilder();
        for (int i = 0; i < OrderExecutionReportDecoder.exchangeOrderIdLength() && d.exchangeOrderId(i) != 0; i++) {
            id.append((char) d.exchangeOrderId(i));
        }
        REPORT_COUNTS
                .computeIfAbsent(
                        d.execType()
                                + (d.execType() == ExecType.REJECT || d.execType() == ExecType.CANCEL_REJECT
                                        ? "/" + d.rejectReason()
                                        : ""),
                        key -> new AtomicInteger())
                .incrementAndGet();
        if (d.execType() == ExecType.NEW) {
            ACKED_NANOS.putIfAbsent(report.getClientOidCounter(), System.nanoTime());
        }
        System.out.println("REPORT oid=" + report.getClientOidCounter() + " id=" + id + " exec=" + d.execType()
                + " status=" + d.orderStatus() + " reject=" + d.rejectReason()
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

    private static PolymarketUsAuthSigner signer(final PolymarketUsCredentials credentials) throws IOException {
        return new PolymarketUsAuthSigner(credentials.apiKey(), credentials.privateKey());
    }

    static PolymarketUsCredentials credentials() {
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

    private static Listing listing(final String exchangeSecurityId) {
        return new Listing(
                1,
                new Exchange(2, "POLYMARKET_US", "Polymarket US", "us", SchemaType.MBP_10),
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
