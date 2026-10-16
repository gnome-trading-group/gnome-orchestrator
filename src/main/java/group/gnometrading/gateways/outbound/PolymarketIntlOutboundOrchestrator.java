package group.gnometrading.gateways.outbound;

import group.gnometrading.account.VenueAccount;
import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.di.Named;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.credentials.PolymarketIntlCredentials;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlAuthHeaders;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlMarketInfo;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOrderSigner;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOutboundReader;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOutboundWriter;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlVenueOrderQuery;
import group.gnometrading.gateways.outbound.recovery.VenueOrderQuery;
import group.gnometrading.gateways.ratelimit.VenueRateLimiter;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.fee.FeeTimeline;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

public final class PolymarketIntlOutboundOrchestrator extends DefaultOutboundOrchestrator {

    @Provides
    public EpochClock provideEpochClock() {
        return SystemEpochClock.INSTANCE;
    }

    @Provides
    public EpochNanoClock provideEpochNanoClock() {
        return new SystemEpochNanoClock();
    }

    @Provides
    @Singleton
    public PolymarketIntlCredentials provideCredentials(
            final SecretsManagerClient secretsManager, final VenueAccount account) {
        final String secretJson = secretsManager
                .getSecretValue(GetSecretValueRequest.builder()
                        .secretId(account.tradingSecretName())
                        .build())
                .secretString();
        final PolymarketIntlCredentials credentials = PolymarketIntlCredentials.fromJson(secretJson);
        // The registry reconciles the wallet it has on record; signing for a different one would trade a wallet
        // nothing watches.
        if (!credentials.funderAddress().equalsIgnoreCase(String.valueOf(account.funderAddress()))) {
            throw new IllegalStateException("Account " + account.label() + " is recorded with funder "
                    + account.funderAddress() + " but its credentials sign for " + credentials.funderAddress());
        }
        return credentials;
    }

    @Provides
    @Singleton
    public PolymarketIntlOrderSigner provideOrderSigner(PolymarketIntlCredentials credentials) {
        return new PolymarketIntlOrderSigner(
                credentials.ethereumPrivateKey(),
                credentials.signerAddress(),
                credentials.funderAddress(),
                credentials.signatureType());
    }

    @Provides
    @Singleton
    public PolymarketIntlAuthHeaders provideAuthHeaders(PolymarketIntlCredentials credentials) {
        // POLY_ADDRESS is the address the API key was derived from: the signing EOA, not the funder.
        return new PolymarketIntlAuthHeaders(
                credentials.apiKey(), credentials.secret(), credentials.passphrase(), credentials.signerAddress());
    }

    /** Shared by every HTTP client on this account, in every listing's gateway: the venue limits the account. */
    @Provides
    @Singleton
    public VenueRateLimiter provideRateLimiter(final PolymarketIntlCredentials credentials) {
        return venueRateLimiter(
                VenueRateLimits.polymarketIntl(), "polymarket.intl.ratelimit.", credentials.signerAddress());
    }

    @Provides
    @Singleton
    public HTTPClient provideHttpClient(final VenueRateLimiter rateLimiter) {
        return OrderEntryHttpClients.create(rateLimiter);
    }

    /** The listing's market parameters, fetched once at startup; refuses to start on an unsupported venue version. */
    @Provides
    @Singleton
    public PolymarketIntlMarketInfo provideMarketInfo(
            HTTPClient httpClient, @Named("CLOB_HOST") String clobHost, Listing listing) throws IOException {
        // exchangeSecurityId is "{condition_id}:{token_id}"
        final String exchangeSecurityId = listing.exchangeSecurityId();
        final String conditionId = exchangeSecurityId.substring(0, exchangeSecurityId.indexOf(':'));
        return PolymarketIntlMarketInfo.load(httpClient, clobHost, conditionId);
    }

    @Provides
    @Singleton
    public URI provideUserWsUri(Properties properties) throws URISyntaxException {
        return new URI(properties.getStringProperty("polymarket.intl.user.ws.url"));
    }

    @Provides
    @Named("CLOB_HOST")
    public String provideClobHost(Properties properties) throws URISyntaxException {
        return new URI(properties.getStringProperty("polymarket.intl.clob.url")).getHost();
    }

    @Provides
    @Singleton
    public WebSocketClient provideUserWsClient(URI userWsUri) throws IOException {
        return new WebSocketClientBuilder()
                .withURI(userWsUri)
                .withSocketFactory(new NativeSSLSocketFactory())
                .withReadBufferSize(1 << 16) // 64 KiB
                .build();
    }

    @Provides
    public GatewayConfig provideGatewayConfig() {
        return new GatewayConfig.Builder()
                .withKeepAliveInterval(Duration.ofSeconds(10))
                .withMaxSilentInterval(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public VenueOrderQuery createVenueOrderQuery() {
        return new PolymarketIntlVenueOrderQuery(
                getInstance(HTTPClient.class),
                getInstance(String.class, "CLOB_HOST"),
                getInstance(PolymarketIntlAuthHeaders.class));
    }

    @Override
    public OutboundAgents startGatewayAgents(
            final SequencedRingBuffer<?> orderOutboundBuffer,
            final SequencedRingBuffer<OrderExecutionReport> execReportBuffer) {
        final PolymarketIntlCredentials credentials = getInstance(PolymarketIntlCredentials.class);
        final Logger logger = getInstance(Logger.class);
        final EpochNanoClock nanoClock = getInstance(EpochNanoClock.class);
        final EpochClock epochClock = getInstance(EpochClock.class);
        final Listing listing = getInstance(Listing.class);
        final WebSocketClient wsClient = getInstance(WebSocketClient.class);
        final String clobHost = getInstance(String.class, "CLOB_HOST");
        final GatewayConfig config = getInstance(GatewayConfig.class);
        final PolymarketIntlOrderSigner orderSigner = getInstance(PolymarketIntlOrderSigner.class);
        final PolymarketIntlAuthHeaders authHeaders = getInstance(PolymarketIntlAuthHeaders.class);
        final HTTPClient httpClient = getInstance(HTTPClient.class);

        final ManyToOneRingBuffer<OrderContext> newOrderQueue = createOrderContextQueue();
        final ManyToOneRingBuffer<OrderContext> writerReportQueue = createOrderContextQueue();
        final ManyToOneRingBuffer<OrderContext> releasedOrderQueue = createOrderContextQueue();

        final PolymarketIntlMarketInfo marketInfo = getInstance(PolymarketIntlMarketInfo.class);

        final PolymarketIntlOutboundReader reader = new PolymarketIntlOutboundReader(
                logger,
                execReportBuffer,
                newOrderQueue,
                writerReportQueue,
                releasedOrderQueue,
                nanoClock,
                listing,
                wsClient,
                new JsonDecoder(),
                credentials.apiKey(),
                credentials.secret(),
                credentials.passphrase(),
                getInstance(FeeTimeline.class),
                // The reconnect catch-up runs on the supervisor's thread, so it can't share the writer's client or
                // auth headers.
                new PolymarketIntlVenueOrderQuery(
                        OrderEntryHttpClients.create(getInstance(VenueRateLimiter.class)),
                        clobHost,
                        new PolymarketIntlAuthHeaders(
                                credentials.apiKey(),
                                credentials.secret(),
                                credentials.passphrase(),
                                credentials.signerAddress())));

        final PolymarketIntlOutboundWriter writer = new PolymarketIntlOutboundWriter(
                logger,
                orderOutboundBuffer,
                newOrderQueue,
                writerReportQueue,
                releasedOrderQueue,
                httpClient,
                clobHost,
                orderSigner,
                authHeaders,
                marketInfo,
                nanoClock,
                listing);

        return startAgents(reader, writer, config, logger, epochClock);
    }
}
