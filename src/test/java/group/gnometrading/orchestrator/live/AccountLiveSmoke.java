package group.gnometrading.orchestrator.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.lane.LaneVenue;
import group.gnometrading.gateways.account.reader.AccountConnection;
import group.gnometrading.gateways.account.reader.AccountProtocol;
import group.gnometrading.gateways.account.reader.AccountReader;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.account.reader.AccountState;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.gateways.ratelimit.AccountBudget;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.gateways.transport.RawDataSink;
import group.gnometrading.gateways.transport.WebSocketChannel;
import group.gnometrading.gateways.transport.WebSocketTransport;
import group.gnometrading.gateways.venues.kalshi.KalshiAccountProtocol;
import group.gnometrading.gateways.venues.kalshi.KalshiAuthSigner;
import group.gnometrading.gateways.venues.kalshi.KalshiLaneVenue;
import group.gnometrading.gateways.venues.kalshi.KalshiVenue;
import group.gnometrading.gateways.venues.kalshi.KalshiVenueOrderQuery;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlAccountProtocol;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlAuthHeaders;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlHeartbeats;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlLaneVenue;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlMarket;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlMarketInfo;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlOrderSigner;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlVenue;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlVenueOrderQuery;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsAccountProtocol;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsAuthSigner;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsLaneVenue;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsListing;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsMarket;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsMarketInfo;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsVenue;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsVenueOrderQuery;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.orchestrator.credentials.KalshiCredentials;
import group.gnometrading.orchestrator.credentials.PolymarketUsCredentials;
import group.gnometrading.orchestrator.runtime.GatewayRunners;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.fee.FeeTimeline;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;

/**
 * Manual live check of the order gateway's account path, run by hand; not a unit test. One account, one lane, one
 * reader on one account-wide socket, as the gateway runs them: {@code -Dorders} (16) post-only bids far below the
 * market, spread over the markets in {@code -Dmarkets} and four sessions, all sent at once; on Kalshi and Polymarket US
 * each is then amended up a size; then all are cancelled. It checks that every order was reported NEW, amended where
 * asked, and CANCELED exactly once, to its own session, with nothing refused; that the lane and the reader hold
 * nothing after; and that the venue lists no more open orders in those markets than before.
 *
 * <ul>
 *   <li>{@code -Dvenue=kalshi}: {@code -Dmarkets=TICKER,...} (the YES side), {@code -Dsecret} (default
 *       {@code gnome/accounts/steady-heron-01/trading}), {@code -Daws.profile}.
 *   <li>{@code -Dvenue=pmus}: {@code -Dmarkets=slug,...} (the long side), {@code -Dsecret=gnome/accounts/<label>/trading}.
 *       From a US IP.
 *   <li>{@code -Dvenue=pmintl}: {@code -Dmarkets=condition:token,...}, {@code -Dpm.keyFile}, {@code -Dpm.credsFile},
 *       {@code -Dpm.signer}, {@code -Dpm.funder}, {@code -Dpm.signatureType}. From outside the US. {@code
 *       -Dheartbeat=yes} also arms and runs the key's heartbeat (the venue then cancels every order on the key 10 s after
 *       it stops): only on a key nothing else uses.
 * </ul>
 *
 * <p>{@code -Dprice} (0.01) and {@code -Dsize} (Kalshi 1, Polymarket US 1, Polymarket the market's minimum) set the
 * bids. {@code -Dreconnect=yes} forces the event socket to reconnect between the acknowledgements and the cancels, so
 * the cancels go while it catches up. {@code -Dmode=ioc} (Polymarket US) sends IOC bids instead, a tick apart on each
 * market so none is refused as another's twin, each ending unfilled at once, and checks every order event on the
 * socket names its market. {@code -Dmode=heartbeat} (Polymarket) places nothing: a second heartbeat keeper on the
 * same key makes each send ids the venue has moved past, so its 400 naming the right id is seen. {@code -Dmode=status}
 * and {@code -Dmode=cleanup} (Kalshi) list and cancel this smoke's resting orders. Trades real money, so {@code
 * -Dconfirm=yes} is required. Prints pass/fail lines
 * and per-order report counts only: no keys and no venue ids.
 */
public final class AccountLiveSmoke {

    private static final String KALSHI_API = "external-api.kalshi.com";
    private static final String KALSHI_WS = "wss://external-api-ws.kalshi.com/trade-api/ws/v2";
    private static final String PM_INTL_CLOB = "clob.polymarket.com";
    private static final String PM_INTL_WS = "wss://ws-subscriptions-clob.polymarket.com/ws/user";
    private static final String PM_US_API = "api.polymarket.us";
    private static final String PM_US_GATEWAY = "gateway.polymarket.us";
    private static final String PM_US_WS = "wss://api.polymarket.us/v1/ws/private";
    private static final int SESSIONS = 4;
    private static final int EXCHANGE_ID = 2;
    private static final int MAX_ORDERS = 64;
    private static final int CONNECTIONS = 8;
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long PRICE_STEP = Statics.PRICE_SCALING_FACTOR / 1_000;

    /** What the smoke saw of one order. */
    private static final class Seen {
        final List<String> events = new CopyOnWriteArrayList<>();
        // When its first report came, on System.nanoTime.
        volatile long firstReportNanos;
        volatile long slot;
        volatile int news;
        volatile int cancels;
        volatile int terminals;
        volatile int refusals;
    }

    private interface OpenOrders {
        int count(Listing listing) throws Exception;
    }

    private AccountLiveSmoke() {}

    public static void main(final String[] args) throws Exception {
        final String mode = System.getProperty("mode", "run");
        if (!mode.equals("status") && !"yes".equals(System.getProperty("confirm"))) {
            throw new IllegalArgumentException("Trades real money: -Dconfirm=yes is required");
        }
        final String venue = required("venue");
        final String[] markets = required("markets").split(",");
        final int orders = Integer.getInteger("orders", 16);
        final boolean ok;
        switch (venue) {
            case "kalshi" -> ok = kalshi(markets, orders);
            case "pmus" -> ok = polymarketUs(markets, orders);
            case "pmintl" -> ok = polymarketIntl(markets, orders);
            default -> throw new IllegalArgumentException("-Dvenue is kalshi, pmus or pmintl");
        }
        System.out.println(ok ? "\nPASS" : "\nFAIL");
        System.exit(ok ? 0 : 1);
    }

    // ----- venues -----

    private static boolean kalshi(final String[] tickers, final int orders) throws Exception {
        final KalshiCredentials creds = KalshiLiveSmoke.credentials(
                System.getProperty("aws.profile", "dev"),
                System.getProperty("secret", "gnome/accounts/steady-heron-01/trading"));
        final Logger logger = new ConsoleLogger(new SystemEpochNanoClock());
        final KalshiLaneVenue venue =
                new KalshiLaneVenue(logger, new KalshiAuthSigner(creds.apiKey(), creds.privateKey()), "maker");
        final KalshiAccountProtocol protocol = new KalshiAccountProtocol(
                new KalshiAuthSigner(creds.apiKey(), creds.privateKey()),
                new HTTPClient(),
                KALSHI_API,
                new SystemEpochNanoClock());
        final KalshiAuthSigner loadSigner = new KalshiAuthSigner(creds.apiKey(), creds.privateKey());
        final List<Listing> listings = new ArrayList<>();
        for (int i = 0; i < tickers.length; i++) {
            final Listing listing = listing(i, "KALSHI", tickers[i] + ":yes", tickers[i]);
            venue.loadMarket(listing, new HTTPClient(), KALSHI_API, loadSigner, System::currentTimeMillis);
            protocol.addMarket(EXCHANGE_ID, listing.security().securityId(), FeeTimeline.EMPTY);
            listings.add(listing);
        }
        final String run = Long.toString(System.currentTimeMillis() / 1000, Character.MAX_RADIX);
        for (int slot = 1; slot <= SESSIONS; slot++) {
            venue.addSession(slot, "smk" + run + slot);
        }
        final KalshiVenueOrderQuery query = new KalshiVenueOrderQuery(
                new HTTPClient(),
                KALSHI_API,
                new KalshiAuthSigner(creds.apiKey(), creds.privateKey()),
                new SystemEpochClock());
        final String mode = System.getProperty("mode", "run");
        if (!mode.equals("run")) {
            return kalshiOpenOrders(query, listings, mode.equals("cleanup"));
        }
        return run(
                "kalshi",
                logger,
                venue,
                protocol,
                KALSHI_API,
                KALSHI_WS,
                KalshiVenue.rateLimits(),
                new GatewayConfig.Builder().build(),
                listings,
                orders,
                new long[SESSIONS + 1],
                System.getProperty("size", "1"),
                true,
                listing -> query.listOpenOrders(listing).size(),
                null,
                0);
    }

    private static boolean polymarketUs(final String[] slugs, final int orders) throws Exception {
        final PolymarketUsCredentials creds = PolymarketUsLiveSmoke.credentials();
        final Logger logger = new ConsoleLogger(new SystemEpochNanoClock());
        final PolymarketUsLaneVenue venue =
                new PolymarketUsLaneVenue(logger, new PolymarketUsAuthSigner(creds.apiKey(), creds.privateKey()));
        // Catch-up looks orders up four at a time, each on a client and signer of its own.
        final List<PolymarketUsVenueOrderQuery> catchUpQueries = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            catchUpQueries.add(new PolymarketUsVenueOrderQuery(
                    new HTTPClient(),
                    PM_US_API,
                    new PolymarketUsAuthSigner(creds.apiKey(), creds.privateKey()),
                    System::currentTimeMillis));
        }
        final PolymarketUsAccountProtocol protocol = new PolymarketUsAccountProtocol(
                logger,
                new PolymarketUsAuthSigner(creds.apiKey(), creds.privateKey()),
                catchUpQueries,
                new SystemEpochNanoClock(),
                MAX_ORDERS);
        final List<Listing> listings = new ArrayList<>();
        for (int i = 0; i < slugs.length; i++) {
            final Listing listing = listing(i, "POLYMARKET_US", slugs[i] + ":long", slugs[i]);
            final PolymarketUsMarketInfo info = PolymarketUsMarketInfo.load(
                    new HTTPClient(), PM_US_GATEWAY, new PolymarketUsListing(listing).marketSlug());
            venue.addMarket(EXCHANGE_ID, listing.security().securityId(), new PolymarketUsMarket(listing, info));
            protocol.addMarket(EXCHANGE_ID, listing.security().securityId(), listing);
            listings.add(listing);
        }
        // Stamps are made from the session's registry seq; a fresh range per run keeps them apart from earlier runs'.
        final long[] slots = new long[SESSIONS + 1];
        final long base = (System.currentTimeMillis() / 1000) % 90_000;
        for (int session = 1; session <= SESSIONS; session++) {
            slots[session] = base + session;
        }
        final PolymarketUsVenueOrderQuery query = new PolymarketUsVenueOrderQuery(
                new HTTPClient(),
                PM_US_API,
                new PolymarketUsAuthSigner(creds.apiKey(), creds.privateKey()),
                System::currentTimeMillis);
        return run(
                "pmus",
                logger,
                venue,
                protocol,
                PM_US_API,
                PM_US_WS,
                PolymarketUsVenue.rateLimits(),
                new GatewayConfig.Builder().build(),
                listings,
                orders,
                slots,
                System.getProperty("size", "1"),
                true,
                listing -> query.listOpenOrders(listing).size(),
                null,
                0);
    }

    private static boolean polymarketIntl(final String[] markets, final int orders) throws Exception {
        final JsonNode creds = JSON.readTree(Files.readString(Path.of(required("pm.credsFile"))));
        final String keyHex = Files.readString(Path.of(required("pm.keyFile"))).strip();
        final byte[] key = HexFormat.of().parseHex(keyHex.startsWith("0x") ? keyHex.substring(2) : keyHex);
        final String apiKey = creds.path("apiKey").asText();
        final String secret = creds.path("secret").asText();
        final String passphrase = creds.path("passphrase").asText();
        final String signer = required("pm.signer");
        final Logger logger = new ConsoleLogger(new SystemEpochNanoClock());
        if ("heartbeat".equals(System.getProperty("mode"))) {
            return heartbeatRivals(logger, () -> new PolymarketIntlAuthHeaders(apiKey, secret, passphrase, signer));
        }
        final PolymarketIntlLaneVenue venue = new PolymarketIntlLaneVenue(
                logger,
                new PolymarketIntlOrderSigner(
                        key, signer, required("pm.funder"), Integer.parseInt(required("pm.signatureType"))),
                new PolymarketIntlAuthHeaders(apiKey, secret, passphrase, signer));
        final PolymarketIntlAccountProtocol protocol = new PolymarketIntlAccountProtocol(
                logger,
                apiKey,
                secret,
                passphrase,
                new PolymarketIntlVenueOrderQuery(
                        new HTTPClient(),
                        PM_INTL_CLOB,
                        new PolymarketIntlAuthHeaders(apiKey, secret, passphrase, signer)),
                new SystemEpochNanoClock());
        final List<Listing> listings = new ArrayList<>();
        long minSize = 0;
        int minAgeSeconds = 0;
        for (int i = 0; i < markets.length; i++) {
            final String condition = markets[i].substring(0, markets[i].indexOf(':'));
            final String token = markets[i].substring(markets[i].indexOf(':') + 1);
            final Listing listing = listing(i, "POLYMARKET", markets[i], condition);
            final PolymarketIntlMarketInfo info =
                    PolymarketIntlMarketInfo.load(new HTTPClient(), PM_INTL_CLOB, condition);
            venue.addMarket(EXCHANGE_ID, listing.security().securityId(), PolymarketIntlMarket.of(listing, info));
            protocol.addMarket(EXCHANGE_ID, listing.security().securityId(), token, FeeTimeline.EMPTY);
            minSize = Math.max(minSize, info.minOrderSize());
            minAgeSeconds = Math.max(minAgeSeconds, info.minOrderAgeSeconds());
            listings.add(listing);
        }
        final PolymarketIntlVenueOrderQuery query = new PolymarketIntlVenueOrderQuery(
                new HTTPClient(), PM_INTL_CLOB, new PolymarketIntlAuthHeaders(apiKey, secret, passphrase, signer));
        final PolymarketIntlAuthHeaders heartbeatAuth =
                new PolymarketIntlAuthHeaders(apiKey, secret, passphrase, signer);
        return run(
                "pmintl",
                logger,
                venue,
                protocol,
                PM_INTL_CLOB,
                PM_INTL_WS,
                PolymarketIntlVenue.rateLimits(),
                new GatewayConfig.Builder()
                        .withKeepAliveInterval(Duration.ofSeconds(10))
                        .withMaxSilentInterval(Duration.ofSeconds(30))
                        .build(),
                listings,
                orders,
                new long[SESSIONS + 1],
                System.getProperty("size", Double.toString((double) minSize / Statics.SIZE_SCALING_FACTOR)),
                false,
                listing -> query.listOpenOrders(listing).size(),
                "yes".equals(System.getProperty("heartbeat")) ? heartbeatAuth : null,
                minAgeSeconds);
    }

    /**
     * {@code -Dmode=status}: every resting order in the markets, read-only, and which are this smoke's (their client
     * order id starts {@code smk}). {@code -Dmode=cleanup}: cancels this smoke's, and nothing else.
     */
    private static boolean kalshiOpenOrders(
            final KalshiVenueOrderQuery query, final List<Listing> listings, final boolean cancel) throws Exception {
        int left = 0;
        for (int i = 0; i < listings.size(); i++) {
            final Listing listing = listings.get(i);
            final List<VenueOrder> open = query.listOpenOrders(listing);
            int ours = 0;
            for (final VenueOrder order : open) {
                if (order.exchangeOrderId() == null || !order.exchangeOrderId().startsWith("smk")) {
                    continue;
                }
                ours++;
                if (cancel) {
                    final boolean done = query.cancel(listing, order);
                    System.out.println("  cancel " + order.exchangeOrderId() + ": " + (done ? "ok" : "refused"));
                    left += done ? 0 : 1;
                } else {
                    System.out.println("  resting " + order.exchangeOrderId() + " filled " + order.filledQty());
                    left++;
                }
            }
            System.out.printf(
                    "market %d (%s): %d resting, %d of them this smoke's%n",
                    i + 1, listing.exchangeSecurityId(), open.size(), ours);
        }
        return left == 0;
    }

    /**
     * {@code -Dmode=heartbeat}: no orders. One keeper arms the key; a second then starts on the same key with no id,
     * so each in turn sends an id the venue has moved past and must take the one the venue names in its 400. Neither
     * may ever be at risk; then the second stops and the first carries on alone.
     */
    private static boolean heartbeatRivals(final Logger logger, final Supplier<PolymarketIntlAuthHeaders> auth)
            throws Exception {
        final List<String> failures = new ArrayList<>();
        final AsyncHTTPConnections firstConnections = AsyncHTTPConnections.builder(HTTPProtocol.HTTPS, PM_INTL_CLOB)
                .withConnections(2)
                .build();
        final AsyncHTTPConnections secondConnections = AsyncHTTPConnections.builder(HTTPProtocol.HTTPS, PM_INTL_CLOB)
                .withConnections(2)
                .build();
        final PolymarketIntlHeartbeats first = keeper("first", logger, firstConnections, auth.get());
        final PolymarketIntlHeartbeats second = keeper("second", logger, secondConnections, auth.get());
        final GnomeAgentRunner firstRunner = start(first);
        GnomeAgentRunner secondRunner = null;
        boolean everAtRisk = false;
        try {
            check(failures, await(first::armed), "first keeper armed the key");
            secondRunner = start(second);
            final long rivalryEnds = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < rivalryEnds) {
                everAtRisk |= first.atRisk() || second.atRisk();
                Thread.sleep(50);
            }
            System.out.printf(
                    "rivals: first %d accepted / %d mismatched, second %d accepted / %d mismatched%n",
                    first.accepted(), first.mismatches(), second.accepted(), second.mismatches());
            check(failures, second.armed(), "second keeper accepted");
            check(failures, first.mismatches() + second.mismatches() > 0, "the venue answered a stale id with its own");
            secondRunner.close();
            secondRunner = null;
            final long acceptedAlone = first.accepted();
            final long aloneEnds = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < aloneEnds) {
                everAtRisk |= first.atRisk();
                Thread.sleep(50);
            }
            check(failures, first.accepted() > acceptedAlone, "first keeper carries on alone");
            check(failures, !everAtRisk, "neither keeper was ever at risk");
        } finally {
            if (secondRunner != null) {
                secondRunner.close();
            }
            firstRunner.close();
            firstConnections.close();
            secondConnections.close();
        }
        return failures.isEmpty();
    }

    private static PolymarketIntlHeartbeats keeper(
            final String name,
            final Logger logger,
            final AsyncHTTPConnections connections,
            final PolymarketIntlAuthHeaders auth) {
        return new PolymarketIntlHeartbeats(
                name,
                logger,
                connections,
                new HTTPConnector(List.of(connections), HTTPConnector.Listener.NONE),
                auth,
                () -> true,
                System::nanoTime,
                new SystemEpochClock(),
                PolymarketIntlHeartbeats.INTERVAL_NANOS,
                PolymarketIntlHeartbeats.REQUEST_TIMEOUT_NANOS,
                PolymarketIntlHeartbeats.AT_RISK_NANOS);
    }

    // ----- the run -----

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static <M> boolean run(
            final String name,
            final Logger logger,
            final LaneVenue<M> venue,
            final AccountProtocol<WebSocketChannel> protocol,
            final String apiHost,
            final String wsUri,
            final VenueRateLimits limits,
            final GatewayConfig config,
            final List<Listing> listings,
            final int orders,
            final long[] slotsBySession,
            final String size,
            final boolean amend,
            final OpenOrders openOrders,
            final PolymarketIntlAuthHeaders heartbeatAuth,
            final int minOrderAgeSeconds)
            throws Exception {
        for (int session = 1; session <= SESSIONS; session++) {
            if (slotsBySession[session] == 0) {
                slotsBySession[session] = session;
            }
        }
        final int[] openBefore = new int[listings.size()];
        for (int i = 0; i < listings.size(); i++) {
            openBefore[i] = openOrders.count(listings.get(i));
        }
        final ManyToOneRingBuffer<LaneCommand> commands =
                new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, 256);
        final ManyToOneRingBuffer<OrderContext> notices =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 1024);
        final ManyToOneRingBuffer<OrderContext> released =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 4 * MAX_ORDERS);
        final ManyToOneRingBuffer<AccountReport> reports =
                new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 1024);
        final AsyncHTTPConnections connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTPS, apiHost)
                .withConnections(CONNECTIONS)
                .captureHeaders(limits.capturedHeaders())
                .build();
        final AccountLane<M> lane = new AccountLane<>(
                name,
                venue,
                connections,
                new AccountBudget(limits, System.nanoTime()),
                AccountLane.Timing.defaults(),
                System::nanoTime,
                new SystemEpochClock(),
                MAX_ORDERS,
                2048,
                512,
                commands,
                notices,
                released);
        final AccountReader reader =
                new AccountReader(protocol, new SystemEpochNanoClock(), MAX_ORDERS, notices, released, reports);
        final WebSocketClient ws = new WebSocketClientBuilder()
                .withURI(new URI(wsUri))
                .withSocketFactory(new NativeSSLSocketFactory())
                .withReadBufferSize(1 << 20)
                .build();
        final AccountConnection<WebSocketChannel> connection = new AccountConnection<>(
                name,
                logger,
                new WebSocketTransport(ws, 1 << 14),
                protocol,
                reader,
                config,
                new SystemEpochClock(),
                new SystemEpochNanoClock(),
                ConnectLimiter.UNLIMITED,
                TimeUnit.SECONDS.toNanos(2));

        final long[] burstNanos = {0};
        final FrameCheck frames = new FrameCheck();
        connection.setRawSink(frames);
        final boolean ioc = "ioc".equals(System.getProperty("mode"));
        final Map<Long, Seen> seen = new ConcurrentHashMap<>();
        final List<GnomeAgentRunner> runners = new ArrayList<>();
        runners.add(start(() -> {
            reports.read(report -> record(seen, report), Integer.MAX_VALUE);
            return 0;
        }));
        runners.add(start(connection.writer()));
        // Reader and supervisor together, closed as the gateway closes them: the reader first, then the socket.
        final GatewayRunners socket = GatewayRunners.venue(
                        0,
                        connection.pauseControl(),
                        new GnomeAgentRunner(connection.reader(), Throwable::printStackTrace),
                        new GnomeAgentRunner(connection.supervisor(), Throwable::printStackTrace))
                .start();
        // Counted as they open, so the burst waits for them all, as a gateway's are warm long before any order.
        final AtomicInteger opened = new AtomicInteger();
        runners.add(start(new HTTPConnector(List.of(connections), new HTTPConnector.Listener() {
            @Override
            public void onOpened(final String host, final int connection) {
                opened.incrementAndGet();
            }
        })));
        runners.add(start(lane));
        AsyncHTTPConnections heartbeatConnections = null;
        PolymarketIntlHeartbeats heartbeats = null;
        if (heartbeatAuth != null) {
            heartbeatConnections = AsyncHTTPConnections.builder(HTTPProtocol.HTTPS, apiHost)
                    .withConnections(2)
                    .build();
            heartbeats = new PolymarketIntlHeartbeats(
                    name,
                    logger,
                    heartbeatConnections,
                    new HTTPConnector(List.of(heartbeatConnections), HTTPConnector.Listener.NONE),
                    heartbeatAuth,
                    () -> true,
                    System::nanoTime,
                    new SystemEpochClock(),
                    PolymarketIntlHeartbeats.INTERVAL_NANOS,
                    PolymarketIntlHeartbeats.REQUEST_TIMEOUT_NANOS,
                    PolymarketIntlHeartbeats.AT_RISK_NANOS);
            runners.add(start(heartbeats));
        }

        final List<String> failures = new ArrayList<>();
        try {
            check(failures, await(() -> connection.state() == AccountState.READY), "event socket READY");
            final long warming = System.nanoTime();
            check(failures, await(() -> opened.get() >= CONNECTIONS), "all " + CONNECTIONS + " order connections open");
            System.out.printf(
                    "     (order connections opened in %d ms)%n",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - warming));
            if (heartbeats != null) {
                final PolymarketIntlHeartbeats beating = heartbeats;
                check(failures, await(beating::armed), "heartbeat accepted and armed");
            }
            final long price = scaled(System.getProperty("price", "0.01"), Statics.PRICE_SCALING_FACTOR);
            final long qty = scaled(size, Statics.SIZE_SCALING_FACTOR);
            System.out.printf(
                    ioc
                            ? "%n== %d IOC bids of %s from %s up, a tick apart on each market, across %d markets and %d"
                                    + " sessions, all at once%n"
                            : "%n== %d post-only bids of %s at %s across %d markets and %d sessions, all at once%n",
                    orders,
                    size,
                    System.getProperty("price", "0.01"),
                    listings.size(),
                    SESSIONS);
            final int openedBeforeBurst = opened.get();
            burstNanos[0] = System.nanoTime();
            for (int counter = 1; counter <= orders; counter++) {
                final Listing listing = listings.get(counter % listings.size());
                send(
                        commands,
                        LaneCommand.NEW,
                        slotsBySession[1 + counter % SESSIONS],
                        counter,
                        listing,
                        ioc ? price + (counter / listings.size() % 4) * PRICE_STEP : price,
                        qty,
                        ioc ? TimeInForce.IMMEDIATE_OR_CANCELED : TimeInForce.GOOD_TILL_CANCELED);
            }
            check(
                    failures,
                    await(() -> count(seen, orders, s -> s.news >= 1 || s.terminals > 0)),
                    "every order acknowledged");
            final List<Long> firstReports = new ArrayList<>();
            for (long counter = 1; counter <= orders; counter++) {
                final Seen order = seen.get(counter);
                if (order != null) {
                    firstReports.add(TimeUnit.NANOSECONDS.toMillis(order.firstReportNanos - burstNanos[0]));
                }
            }
            firstReports.sort(null);
            System.out.println("     first report per order, ms after the burst: " + firstReports);
            System.out.println(
                    "     order connections reopened during the burst: " + (opened.get() - openedBeforeBurst));

            if (ioc) {
                // Nothing is offered that low: each IOC ends unfilled at once, matched by its terms until its id is
                // known.
                check(failures, await(() -> count(seen, orders, s -> s.terminals > 0)), "every IOC ended");
            }

            if (amend && !ioc) {
                System.out.println("\n== each amended up one size");
                final long amended = qty + scaled(size, Statics.SIZE_SCALING_FACTOR);
                for (int counter = 1; counter <= orders; counter++) {
                    final Listing listing = listings.get(counter % listings.size());
                    send(
                            commands,
                            LaneCommand.AMEND,
                            slotsBySession[1 + counter % SESSIONS],
                            counter,
                            listing,
                            price,
                            amended,
                            TimeInForce.GOOD_TILL_CANCELED);
                }
                check(
                        failures,
                        await(() -> count(seen, orders, s -> s.news >= 2 || s.terminals > 0 || s.refusals > 0)),
                        "every amend acknowledged");
            }

            if (!ioc && "yes".equals(System.getProperty("reconnect"))) {
                System.out.println("\n== event socket forced to reconnect; the cancels go while it catches up");
                connection.supervisor().forceReconnect();
            }
            // A venue may refuse to cancel an order younger than its minimum age.
            Thread.sleep(ioc ? 0 : minOrderAgeSeconds * 1_000L);
            if (!ioc) {
                System.out.println("\n== all cancelled");
            }
            for (int counter = 1; !ioc && counter <= orders; counter++) {
                final Listing listing = listings.get(counter % listings.size());
                send(
                        commands,
                        LaneCommand.CANCEL,
                        slotsBySession[1 + counter % SESSIONS],
                        counter,
                        listing,
                        0,
                        0,
                        TimeInForce.GOOD_TILL_CANCELED);
            }
            check(failures, await(() -> count(seen, orders, s -> s.terminals > 0)), "every order ended");
            check(failures, await(() -> lane.workingOrders() == 0), "lane holds nothing");
            check(failures, await(() -> reader.heldOrders() == 0), "reader holds nothing");
            if (heartbeats != null) {
                check(failures, !heartbeats.atRisk(), "heartbeat never at risk");
            }
        } finally {
            for (int i = runners.size() - 1; i >= 1; i--) {
                runners.get(i).close();
            }
            KalshiLiveSmoke.closeTimed(name + " event socket", socket);
            runners.get(0).close();
            connections.close();
            if (heartbeatConnections != null) {
                heartbeatConnections.close();
            }
        }

        for (int counter = 1; counter <= orders; counter++) {
            final Seen order = seen.get((long) counter);
            final long slot = slotsBySession[1 + counter % SESSIONS];
            if (order == null) {
                check(failures, false, "order " + counter + ": no reports");
                continue;
            }
            System.out.printf("order %2d session %d: %s%n", counter, order.slot, order.events);
            final int expectedNews = amend ? 2 : 1;
            final boolean asExpected = ioc
                    ? order.terminals == 1 && order.refusals == 0
                    : order.news == expectedNews && order.cancels == 1 && order.terminals == 1 && order.refusals == 0;
            if (order.slot != slot || !asExpected) {
                check(failures, false, "order " + counter + ": " + order.events);
            }
        }
        if (name.equals("kalshi")) {
            // Whether order updates carry a sequence that would show a missed one, per subscription.
            System.out.printf(
                    "kalshi order updates: %d, carrying seq: %d, seq gaps: %d (subscriptions: %d)%n",
                    frames.kalshiUpdates, frames.kalshiWithSeq, frames.kalshiSeqGaps, frames.lastSeqBySid.size());
        }
        if (name.equals("pmus")) {
            // Every order event names its market: the reader tells look-alike IOCs on different markets apart by it.
            System.out.printf(
                    "order events on the socket: %d, naming their market: %d%n", frames.events, frames.withMarket);
            check(
                    failures,
                    frames.events > 0 && frames.withMarket == frames.events,
                    "every order event names its market");
        }
        // The venue's order listing lags its order stream (Kalshi's by seconds), so it is given time to catch up.
        for (int i = 0; i < listings.size(); i++) {
            final Listing listing = listings.get(i);
            final int before = openBefore[i];
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int after = openOrders.count(listing);
            while (after > before && System.nanoTime() < deadline) {
                Thread.sleep(1_000);
                after = openOrders.count(listing);
            }
            check(failures, after <= before, "market " + (i + 1) + ": no open orders left (" + after + ")");
        }
        return failures.isEmpty();
    }

    private static void record(final Map<Long, Seen> seen, final AccountReport report) {
        final long counter = report.report.getClientOidCounter();
        final ExecType type = report.report.decoder.execType();
        final Seen order = seen.computeIfAbsent(counter, k -> new Seen());
        if (order.firstReportNanos == 0) {
            order.firstReportNanos = System.nanoTime();
        }
        order.slot = report.sessionSlot;
        final RejectReason reason = report.report.decoder.rejectReason();
        order.events.add(type + (reason == RejectReason.NULL_VAL ? "" : "(" + reason + ")"));
        switch (type) {
            case NEW -> order.news++;
            case CANCEL -> {
                order.cancels++;
                order.terminals++;
            }
            case FILL, REJECT -> order.terminals++;
            case CANCEL_REJECT -> order.refusals++;
            default -> {}
        }
    }

    private static void send(
            final ManyToOneRingBuffer<LaneCommand> commands,
            final int kind,
            final long slot,
            final long counter,
            final Listing listing,
            final long price,
            final long size,
            final TimeInForce timeInForce) {
        final int idx = commands.tryClaim();
        if (idx < 0) {
            throw new IllegalStateException("command ring full");
        }
        final LaneCommand command = commands.indexAt(idx);
        command.reset();
        command.kind = kind;
        command.sessionSlot = slot;
        command.clientOidCounter = counter;
        command.clientOidStrategyId = 1;
        command.exchangeId = EXCHANGE_ID;
        command.securityId = listing.security().securityId();
        command.side = Side.Bid;
        command.price = price;
        command.size = size;
        command.timeInForce = timeInForce;
        command.orderType = OrderType.LIMIT;
        // Post-only only on resting orders: an IOC that can't take is just cancelled.
        command.postOnly = timeInForce == TimeInForce.GOOD_TILL_CANCELED;
        commands.commit(idx);
    }

    /**
     * Reads the raw frames, never keeps them: counts Polymarket US order events and those naming their market, and
     * Kalshi's order updates, those carrying a {@code seq}, and the gaps between consecutive ones per subscription.
     */
    private static final class FrameCheck implements RawDataSink {
        private static final Pattern SID = Pattern.compile("\"sid\":(\\d+)");
        private static final Pattern SEQ = Pattern.compile("\"seq\":(\\d+)");
        volatile int events;
        volatile int withMarket;
        volatile int kalshiUpdates;
        volatile int kalshiWithSeq;
        volatile int kalshiSeqGaps;
        final Map<Long, Long> lastSeqBySid = new ConcurrentHashMap<>();

        @Override
        public void capture(final long recvTimestamp, final ByteBuffer buffer) {
            final String frame =
                    StandardCharsets.UTF_8.decode(buffer.duplicate()).toString();
            if (frame.contains("orderSubscriptionUpdate")) {
                this.events++;
                if (frame.contains("\"marketSlug\":\"")) {
                    this.withMarket++;
                }
            }
            if (frame.contains("\"type\":\"user_order\"")) {
                this.kalshiUpdates++;
                final Matcher seq = SEQ.matcher(frame);
                if (seq.find()) {
                    this.kalshiWithSeq++;
                    final Matcher sid = SID.matcher(frame);
                    final long sidValue = sid.find() ? Long.parseLong(sid.group(1)) : -1;
                    final long seqValue = Long.parseLong(seq.group(1));
                    final Long last = this.lastSeqBySid.put(sidValue, seqValue);
                    if (last != null && seqValue != last + 1) {
                        this.kalshiSeqGaps++;
                    }
                }
            }
        }
    }

    private interface Condition {
        boolean test(Seen seen);
    }

    private static boolean count(final Map<Long, Seen> seen, final int orders, final Condition condition) {
        for (long counter = 1; counter <= orders; counter++) {
            final Seen order = seen.get(counter);
            if (order == null || !condition.test(order)) {
                return false;
            }
        }
        return true;
    }

    private static boolean await(final BooleanSupplier done) throws InterruptedException {
        final long deadline = System.nanoTime() + WAIT_NANOS;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    private static void check(final List<String> failures, final boolean ok, final String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            failures.add(what);
        }
    }

    private static GnomeAgentRunner start(final GnomeAgent agent) {
        final GnomeAgentRunner runner = new GnomeAgentRunner(agent, Throwable::printStackTrace);
        GnomeAgentRunner.startOnThread(runner);
        return runner;
    }

    private static long scaled(final String value, final long scale) {
        return Math.round(Double.parseDouble(value) * scale);
    }

    private static Listing listing(
            final int index, final String exchange, final String exchangeSecurityId, final String symbol) {
        return new Listing(
                index + 1,
                new Exchange(EXCHANGE_ID, exchange, exchange, "global"),
                new Security(index + 3, symbol, null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
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
}
