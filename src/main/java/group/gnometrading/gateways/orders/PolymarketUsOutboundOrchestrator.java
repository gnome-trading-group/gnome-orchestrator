package group.gnometrading.gateways.orders;

import group.gnometrading.account.VenueAccount;
import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.di.Named;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.credentials.PolymarketUsCredentials;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.gateways.ratelimit.VenueRateLimiter;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsAuthSigner;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsListing;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsMarketInfo;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsOutboundReader;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsOutboundWriter;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsVenue;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsVenueOrderQuery;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.shared.SessionTag;
import group.gnometrading.sm.Listing;
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

public final class PolymarketUsOutboundOrchestrator extends DefaultOutboundOrchestrator {

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
    public PolymarketUsCredentials provideCredentials(
            final SecretsManagerClient secretsManager, final VenueAccount account) {
        final String secretJson = secretsManager
                .getSecretValue(GetSecretValueRequest.builder()
                        .secretId(account.tradingSecretName())
                        .build())
                .secretString();
        return PolymarketUsCredentials.fromJson(secretJson);
    }

    @Provides
    @Named("WRITER")
    @Singleton
    public PolymarketUsAuthSigner provideWriterSigner(final PolymarketUsCredentials credentials) throws IOException {
        return new PolymarketUsAuthSigner(credentials.apiKey(), credentials.privateKey());
    }

    /** Shared by every HTTP client on this account, in every listing's gateway: the venue limits the account. */
    @Provides
    @Singleton
    public VenueRateLimiter provideRateLimiter(final PolymarketUsCredentials credentials) {
        return venueRateLimiter(PolymarketUsVenue.rateLimits(), "polymarket.us.ratelimit.", credentials.apiKey());
    }

    @Provides
    @Singleton
    public HTTPClient provideHttpClient(final VenueRateLimiter rateLimiter) {
        return OrderEntryHttpClients.create(rateLimiter);
    }

    @Provides
    @Named("API_HOST")
    public String provideApiHost(final Properties properties) throws URISyntaxException {
        return new URI(properties.getStringProperty("polymarket.us.api.url")).getHost();
    }

    /** The market's tick, read once at startup from the public gateway. */
    @Provides
    @Singleton
    public PolymarketUsMarketInfo provideMarketInfo(
            final HTTPClient httpClient, final Properties properties, final Listing listing)
            throws IOException, URISyntaxException {
        final String gatewayHost = new URI(properties.getStringProperty("polymarket.us.gateway.url")).getHost();
        return PolymarketUsMarketInfo.load(httpClient, gatewayHost, new PolymarketUsListing(listing).marketSlug());
    }

    @Provides
    @Singleton
    public WebSocketClient providePrivateWsClient(final Properties properties) throws IOException, URISyntaxException {
        return new WebSocketClientBuilder()
                .withURI(new URI(properties.getStringProperty("polymarket.us.private.ws.url")))
                .withSocketFactory(new NativeSSLSocketFactory())
                .withReadBufferSize(1 << 16) // 64 KiB
                .build();
    }

    @Provides
    public GatewayConfig provideGatewayConfig() {
        // The venue pings the private socket every ten seconds or so.
        return new GatewayConfig.Builder()
                .withMaxSilentInterval(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public VenueOrderQuery createVenueOrderQuery() {
        final EpochClock clock = getInstance(EpochClock.class);
        return new PolymarketUsVenueOrderQuery(
                getInstance(HTTPClient.class),
                getInstance(String.class, "API_HOST"),
                getInstance(PolymarketUsAuthSigner.class, "WRITER"),
                clock::time);
    }

    @Override
    public OutboundAgents startGatewayAgents(
            final SequencedRingBuffer<?> orderOutboundBuffer,
            final SequencedRingBuffer<OrderExecutionReport> execReportBuffer) {
        final PolymarketUsCredentials credentials = getInstance(PolymarketUsCredentials.class);
        final Logger logger = getInstance(Logger.class);
        final EpochNanoClock nanoClock = getInstance(EpochNanoClock.class);
        final EpochClock epochClock = getInstance(EpochClock.class);
        final Listing listing = getInstance(Listing.class);
        final String apiHost = getInstance(String.class, "API_HOST");
        final GatewayConfig config = getInstance(GatewayConfig.class);
        final Properties properties = getInstance(Properties.class);

        final ManyToOneRingBuffer<OrderContext> newOrderQueue = createOrderContextQueue();
        final ManyToOneRingBuffer<OrderContext> writerReportQueue = createOrderContextQueue();
        final ManyToOneRingBuffer<OrderContext> releasedOrderQueue = createOrderContextQueue();

        // The reader signs its handshake on the supervisor's thread and catches up there over REST, so it has its own
        // signer and HTTP client.
        final PolymarketUsAuthSigner readerSigner = newSigner(credentials);
        final PolymarketUsOutboundReader reader = new PolymarketUsOutboundReader(
                logger,
                execReportBuffer,
                newOrderQueue,
                writerReportQueue,
                releasedOrderQueue,
                nanoClock,
                listing,
                getInstance(WebSocketClient.class),
                new JsonDecoder(),
                readerSigner,
                new PolymarketUsVenueOrderQuery(
                        OrderEntryHttpClients.create(getInstance(VenueRateLimiter.class)),
                        apiHost,
                        newSigner(credentials),
                        epochClock::time));

        final PolymarketUsOutboundWriter writer = new PolymarketUsOutboundWriter(
                logger,
                orderOutboundBuffer,
                newOrderQueue,
                writerReportQueue,
                releasedOrderQueue,
                getInstance(HTTPClient.class),
                apiHost,
                getInstance(PolymarketUsAuthSigner.class, "WRITER"),
                getInstance(PolymarketUsMarketInfo.class),
                nanoClock,
                listing,
                SessionTag.of(properties));

        return startAgents(reader, writer, config, logger, epochClock);
    }

    private static PolymarketUsAuthSigner newSigner(final PolymarketUsCredentials credentials) {
        try {
            return new PolymarketUsAuthSigner(credentials.apiKey(), credentials.privateKey());
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to create a Polymarket US signer", e);
        }
    }
}
