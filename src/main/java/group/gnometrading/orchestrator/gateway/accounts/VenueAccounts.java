package group.gnometrading.orchestrator.gateway.accounts;

import group.gnometrading.account.VenueAccount;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.lane.LaneVenue;
import group.gnometrading.gateways.account.reader.AccountConnection;
import group.gnometrading.gateways.account.reader.AccountProtocol;
import group.gnometrading.gateways.account.reader.AccountReader;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.gateways.ratelimit.AccountBudget;
import group.gnometrading.gateways.ratelimit.BudgetGate;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
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
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPClientBuilder;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.orchestrator.credentials.KalshiCredentials;
import group.gnometrading.orchestrator.credentials.PolymarketIntlCredentials;
import group.gnometrading.orchestrator.credentials.PolymarketUsCredentials;
import group.gnometrading.orchestrator.gateway.core.AccountLink;
import group.gnometrading.orchestrator.gateway.core.CrossKey;
import group.gnometrading.orchestrator.gateway.core.CrossKeys;
import group.gnometrading.orchestrator.runtime.ProcessExit;
import group.gnometrading.resources.Properties;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.fee.FeeTimeline;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemNanoClock;

/**
 * Builds each account an order gateway holds, per venue, from the account's trading secret: the same venue pieces a
 * session's own order entry uses, shared across every session on the account. Nothing connects until the account
 * {@linkplain GatewayAccount#start starts}. Startup only; allocates freely.
 */
public final class VenueAccounts {

    /** What every account is built with; {@code secrets} reads a secret's value by name. */
    public record Context(
            Logger logger,
            Properties properties,
            Function<String, String> secrets,
            GatewaySettings settings,
            EpochClock epochClock,
            EpochNanoClock nanoClock,
            ProcessExit exit,
            CrossKeys crossKeys) {}

    // Polymarket US catch-up looks orders up this many at a time, each on a client and signer of its own.
    private static final int PM_US_CATCH_UP_QUERIES = 4;
    // Two, so a beat with no answer is sent again at once on a connection already open.
    private static final int HEARTBEAT_CONNECTIONS = 2;
    private static final int MAX_BODY_LENGTH = 2048;
    private static final int MAX_PATH_LENGTH = 512;
    private static final int COMMAND_QUEUE = 4096;
    private static final int NOTICE_QUEUE = 4096;
    private static final int REPORT_QUEUE = 8192;
    private static final int SOCKET_READ_BUFFER = 1 << 20;
    private static final int SOCKET_WRITE_BUFFER = 1 << 14;
    private static final long READ_SETTLE_NANOS = TimeUnit.SECONDS.toNanos(2);
    // Polymarket US lists every open order in one unpaged answer, about 1 KB each, so the default 32 KB holds only
    // some 30: room for the account's most in-flight orders. Only for that listing: each client holds it twice over.
    private static final int LISTING_READ_BUFFER = 1 << 23;
    // How long a read beside the lane waits for the account's budget before it fails, as a request with no answer
    // would: catch-up tries again, a session's listing is answered FAILED.
    private static final long GATED_MAX_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);

    /** An account's limits, as configured, and the one budget its lane and every client beside it draw on. */
    private record Budget(VenueRateLimits limits, AccountBudget budget) {

        /**
         * A client whose every request draws on the budget as {@code type}: the account's limits are per account,
         * not per connection, so a read beside the lane spends what the lane would otherwise send.
         */
        HTTPClient client(final RequestClass type) {
            return builder(type).build();
        }

        /** As {@link #client}, with room to read a venue's unpaged listing of every open order on the account. */
        HTTPClient listingClient(final RequestClass type) {
            return builder(type).withReadBufferSize(LISTING_READ_BUFFER).build();
        }

        private HTTPClientBuilder builder(final RequestClass type) {
            return HTTPClient.builder()
                    .withRequestGate(new BudgetGate(this.budget, type, GATED_MAX_WAIT_NANOS, SystemNanoClock.INSTANCE))
                    .captureHeaders(this.limits.capturedHeaders());
        }
    }

    private final Context context;

    public VenueAccounts(final Context context) {
        this.context = context;
    }

    public GatewayAccount build(final GatewayVenue venue, final VenueAccount account)
            throws IOException, URISyntaxException {
        final String secret = this.context.secrets().apply(account.tradingSecretName());
        return switch (venue) {
            case KALSHI -> kalshi(account, KalshiCredentials.fromJson(secret));
            case POLYMARKET_US -> polymarketUs(account, PolymarketUsCredentials.fromJson(secret));
            case POLYMARKET_INTL -> polymarketIntl(account, PolymarketIntlCredentials.fromJson(secret));
        };
    }

    private GatewayAccount kalshi(final VenueAccount account, final KalshiCredentials creds)
            throws IOException, URISyntaxException {
        final String apiHost = host("kalshi.api.url");
        final Budget budget = budget(KalshiVenue.rateLimits(), "kalshi.ratelimit.");
        final KalshiLaneVenue venue = new KalshiLaneVenue(
                this.context.logger(),
                new KalshiAuthSigner(creds.apiKey(), creds.privateKey()),
                KalshiVenue.SELF_TRADE_PREVENTION);
        final KalshiAccountProtocol protocol = new KalshiAccountProtocol(
                new KalshiAuthSigner(creds.apiKey(), creds.privateKey()),
                budget.client(RequestClass.CATCH_UP),
                apiHost,
                this.context.nanoClock());
        final KalshiAuthSigner loadSigner = new KalshiAuthSigner(creds.apiKey(), creds.privateKey());
        // One for every listing loaded: loads come one at a time, from the control thread.
        final HTTPClient loadClient = budget.client(RequestClass.BACKGROUND);
        final AccountLink.Markets markets = new AccountLink.Markets() {
            @Override
            public CrossKey load(final Listing listing, final FeeTimeline fees) throws IOException {
                venue.loadMarket(listing, loadClient, apiHost, loadSigner, System::currentTimeMillis);
                protocol.addMarket(
                        listing.exchange().exchangeId(), listing.security().securityId(), fees);
                return CrossKey.of(
                        listing.exchange().exchangeId(), KalshiVenue.crossMarketOf(listing.exchangeSecurityId()));
            }

            @Override
            public void addSession(final long slot, final String sessionId) {
                venue.addSession(slot, sessionId);
            }

            @Override
            public void removeSession(final long slot) {
                venue.removeSession(slot);
            }
        };
        return assemble(
                account,
                venue,
                protocol,
                apiHost,
                uri("kalshi.user.ws.url"),
                budget,
                new GatewayConfig.Builder()
                        .withMaxSilentInterval(Duration.ofSeconds(30))
                        .build(),
                markets,
                null,
                null,
                new KalshiVenueOrderQuery(
                        budget.client(RequestClass.BACKGROUND),
                        apiHost,
                        new KalshiAuthSigner(creds.apiKey(), creds.privateKey()),
                        this.context.epochClock()));
    }

    private GatewayAccount polymarketUs(final VenueAccount account, final PolymarketUsCredentials creds)
            throws IOException, URISyntaxException {
        final String apiHost = host("polymarket.us.api.url");
        final String gatewayHost = host("polymarket.us.gateway.url");
        final Budget budget = budget(PolymarketUsVenue.rateLimits(), "polymarket.us.ratelimit.");
        final PolymarketUsLaneVenue venue = new PolymarketUsLaneVenue(
                this.context.logger(), new PolymarketUsAuthSigner(creds.apiKey(), creds.privateKey()));
        final List<PolymarketUsVenueOrderQuery> catchUpQueries = new ArrayList<>();
        for (int i = 0; i < PM_US_CATCH_UP_QUERIES; i++) {
            // The first lists the account's open orders; the rest only look orders up one by one.
            catchUpQueries.add(new PolymarketUsVenueOrderQuery(
                    i == 0 ? budget.listingClient(RequestClass.CATCH_UP) : budget.client(RequestClass.CATCH_UP),
                    apiHost,
                    new PolymarketUsAuthSigner(creds.apiKey(), creds.privateKey()),
                    System::currentTimeMillis));
        }
        final PolymarketUsAccountProtocol protocol = new PolymarketUsAccountProtocol(
                this.context.logger(),
                new PolymarketUsAuthSigner(creds.apiKey(), creds.privateKey()),
                catchUpQueries,
                this.context.nanoClock(),
                this.context.settings().maxOrders());
        // The public market API, keyed by nothing of the account's, so not drawn on its budget. One for every listing
        // loaded: loads come one at a time, from the control thread.
        final HTTPClient loadClient = new HTTPClient();
        final AccountLink.Markets markets = (listing, fees) -> {
            final PolymarketUsMarketInfo info =
                    PolymarketUsMarketInfo.load(loadClient, gatewayHost, new PolymarketUsListing(listing).marketSlug());
            final int exchangeId = listing.exchange().exchangeId();
            final long securityId = listing.security().securityId();
            venue.addMarket(exchangeId, securityId, new PolymarketUsMarket(listing, info));
            protocol.addMarket(exchangeId, securityId, listing);
            return CrossKey.of(exchangeId, PolymarketUsVenue.crossMarketOf(listing.exchangeSecurityId()));
        };
        return assemble(
                account,
                venue,
                protocol,
                apiHost,
                uri("polymarket.us.private.ws.url"),
                budget,
                new GatewayConfig.Builder()
                        .withMaxSilentInterval(Duration.ofSeconds(30))
                        .build(),
                markets,
                null,
                null,
                new PolymarketUsVenueOrderQuery(
                        budget.listingClient(RequestClass.BACKGROUND),
                        apiHost,
                        new PolymarketUsAuthSigner(creds.apiKey(), creds.privateKey()),
                        System::currentTimeMillis));
    }

    private GatewayAccount polymarketIntl(final VenueAccount account, final PolymarketIntlCredentials creds)
            throws IOException, URISyntaxException {
        // The registry reconciles the wallet it has on record; signing for a different one would trade a wallet
        // nothing watches.
        if (!creds.funderAddress().equalsIgnoreCase(String.valueOf(account.funderAddress()))) {
            throw new IllegalStateException("Account " + account.label() + " is recorded with funder "
                    + account.funderAddress() + " but its credentials sign for " + creds.funderAddress());
        }
        final String clobHost = host("polymarket.intl.clob.url");
        final Budget budget = budget(PolymarketIntlVenue.rateLimits(), "polymarket.intl.ratelimit.");
        final PolymarketIntlLaneVenue venue = new PolymarketIntlLaneVenue(
                this.context.logger(),
                new PolymarketIntlOrderSigner(
                        creds.ethereumPrivateKey(),
                        creds.signerAddress(),
                        creds.funderAddress(),
                        creds.signatureType()),
                authHeaders(creds));
        final PolymarketIntlAccountProtocol protocol = new PolymarketIntlAccountProtocol(
                this.context.logger(),
                creds.apiKey(),
                creds.secret(),
                creds.passphrase(),
                new PolymarketIntlVenueOrderQuery(budget.client(RequestClass.CATCH_UP), clobHost, authHeaders(creds)),
                this.context.nanoClock());
        // One for every listing loaded: loads come one at a time, from the control thread.
        final HTTPClient loadClient = budget.client(RequestClass.BACKGROUND);
        final AccountLink.Markets markets = (listing, fees) -> {
            final String exchangeSecurityId = listing.exchangeSecurityId();
            final int colon = exchangeSecurityId.indexOf(':');
            final PolymarketIntlMarketInfo info =
                    PolymarketIntlMarketInfo.load(loadClient, clobHost, exchangeSecurityId.substring(0, colon));
            final int exchangeId = listing.exchange().exchangeId();
            final long securityId = listing.security().securityId();
            venue.addMarket(exchangeId, securityId, PolymarketIntlMarket.of(listing, info));
            protocol.addMarket(exchangeId, securityId, exchangeSecurityId.substring(colon + 1), fees);
            return CrossKey.of(exchangeId, PolymarketIntlVenue.crossMarketOf(exchangeSecurityId, info));
        };
        final String name = name(account);
        final AsyncHTTPConnections heartbeatConnections = AsyncHTTPConnections.builder(HTTPProtocol.HTTPS, clobHost)
                .withConnections(HEARTBEAT_CONNECTIONS)
                .build();
        final Function<BooleanSupplier, PolymarketIntlHeartbeats> heartbeats =
                laneAlive -> new PolymarketIntlHeartbeats(
                        name,
                        this.context.logger(),
                        heartbeatConnections,
                        new HTTPConnector(List.of(heartbeatConnections), HTTPConnector.Listener.NONE),
                        authHeaders(creds),
                        laneAlive,
                        System::nanoTime,
                        this.context.epochClock(),
                        PolymarketIntlHeartbeats.INTERVAL_NANOS,
                        PolymarketIntlHeartbeats.REQUEST_TIMEOUT_NANOS,
                        PolymarketIntlHeartbeats.AT_RISK_NANOS);
        return assemble(
                account,
                venue,
                protocol,
                clobHost,
                uri("polymarket.intl.user.ws.url"),
                budget,
                new GatewayConfig.Builder()
                        .withKeepAliveInterval(Duration.ofSeconds(10))
                        .withMaxSilentInterval(Duration.ofSeconds(30))
                        .build(),
                markets,
                heartbeats,
                heartbeatConnections,
                new PolymarketIntlVenueOrderQuery(
                        budget.client(RequestClass.BACKGROUND), clobHost, authHeaders(creds)));
    }

    /** POLY_ADDRESS is the address the API key was derived from: the signing EOA, not the funder. One per thread. */
    private static PolymarketIntlAuthHeaders authHeaders(final PolymarketIntlCredentials creds) {
        return new PolymarketIntlAuthHeaders(creds.apiKey(), creds.secret(), creds.passphrase(), creds.signerAddress());
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private <M> GatewayAccount assemble(
            final VenueAccount account,
            final LaneVenue<M> venue,
            final AccountProtocol<WebSocketChannel> protocol,
            final String apiHost,
            final URI eventsUri,
            final Budget budget,
            final GatewayConfig socketConfig,
            final AccountLink.Markets markets,
            final Function<BooleanSupplier, PolymarketIntlHeartbeats> heartbeats,
            final AsyncHTTPConnections heartbeatConnections,
            final VenueOrderQuery orderQuery)
            throws IOException {
        final int maxOrders = this.context.settings().maxOrders();
        final GatewayAccount.Parts parts = new GatewayAccount.Parts();
        parts.account = account;
        parts.name = name(account);
        parts.logger = this.context.logger();
        parts.exit = this.context.exit();
        parts.markets = markets;
        parts.crossKeys = this.context.crossKeys();
        parts.orderQuery = orderQuery;
        parts.commands = new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, COMMAND_QUEUE);
        parts.reports = new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, REPORT_QUEUE);
        final ManyToOneRingBuffer<OrderContext> notices =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, NOTICE_QUEUE);
        // Each order is released once and has its venue id learned at most once.
        final ManyToOneRingBuffer<OrderContext> released =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, 2 * maxOrders);
        parts.connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTPS, apiHost)
                .withConnections(this.context.settings().laneConnections())
                .captureHeaders(budget.limits().capturedHeaders())
                .build();
        parts.connector = new HTTPConnector(List.of(parts.connections), HTTPConnector.Listener.NONE);
        parts.budget = budget.budget();
        parts.lane = new AccountLane<>(
                parts.name,
                venue,
                parts.connections,
                budget.budget(),
                AccountLane.Timing.defaults(),
                System::nanoTime,
                this.context.epochClock(),
                maxOrders,
                MAX_BODY_LENGTH,
                MAX_PATH_LENGTH,
                parts.commands,
                notices,
                released);
        parts.lane.caps(new AccountLane.Caps(
                this.context.properties().getIntProperty("gateway.session.max.open.orders"),
                this.context.properties().getIntProperty("gateway.session.max.new.per.second"),
                this.context.properties().getIntProperty("gateway.account.max.open.orders")));
        parts.reader =
                new AccountReader(protocol, this.context.nanoClock(), maxOrders, notices, released, parts.reports);
        final WebSocketClient ws = new WebSocketClientBuilder()
                .withURI(eventsUri)
                .withSocketFactory(new NativeSSLSocketFactory())
                .withReadBufferSize(SOCKET_READ_BUFFER)
                .build();
        parts.connection = new AccountConnection<>(
                parts.name,
                this.context.logger(),
                new WebSocketTransport(ws, SOCKET_WRITE_BUFFER),
                protocol,
                parts.reader,
                socketConfig,
                this.context.epochClock(),
                this.context.nanoClock(),
                ConnectLimiter.UNLIMITED,
                READ_SETTLE_NANOS);
        if (heartbeats != null) {
            final GatewayAccount[] built = new GatewayAccount[1];
            final PolymarketIntlHeartbeats keeper = heartbeats.apply(new LaneLiveness(
                    () -> built[0] == null ? 0 : built[0].lanePasses(), System::nanoTime, LaneLiveness.STALL_NANOS));
            parts.heartbeats = keeper;
            parts.heartbeatConnections = heartbeatConnections;
            built[0] = new GatewayAccount(parts);
            return built[0];
        }
        return new GatewayAccount(parts);
    }

    /** The venue's limits with this stage's overrides under {@code prefix}, and a budget over them. */
    private Budget budget(final VenueRateLimits defaults, final String prefix) {
        final VenueRateLimits limits =
                defaults.withOverrides(this.context.properties().getPropertiesByPrefix(prefix));
        return new Budget(limits, new AccountBudget(limits, System.nanoTime()));
    }

    private static String name(final VenueAccount account) {
        return "account-" + account.accountId();
    }

    private String host(final String property) throws URISyntaxException {
        return uri(property).getHost();
    }

    private URI uri(final String property) throws URISyntaxException {
        return new URI(this.context.properties().getStringProperty(property));
    }
}
