package group.gnometrading.gateways.outbound;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.di.Named;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.credentials.PolymarketIntlCredentials;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlAuthHeaders;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOrderSigner;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOutboundReader;
import group.gnometrading.gateways.outbound.exchanges.polymarket.intl.PolymarketIntlOutboundWriter;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.agrona.ErrorHandler;
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
    public PolymarketIntlCredentials provideCredentials(SecretsManagerClient secretsManager) {
        final String secretJson = secretsManager
                .getSecretValue(GetSecretValueRequest.builder()
                        .secretId("gnome/exchange-credentials/polymarket-intl")
                        .build())
                .secretString();
        return PolymarketIntlCredentials.fromJson(secretJson);
    }

    @Provides
    @Singleton
    public PolymarketIntlOrderSigner provideOrderSigner(PolymarketIntlCredentials credentials) {
        return new PolymarketIntlOrderSigner(credentials.ethereumPrivateKey(), credentials.signerAddress());
    }

    @Provides
    @Singleton
    public PolymarketIntlAuthHeaders provideAuthHeaders(PolymarketIntlCredentials credentials) {
        return new PolymarketIntlAuthHeaders(
                credentials.apiKey(), credentials.secret(), credentials.passphrase(), credentials.proxyWalletAddress());
    }

    @Provides
    @Singleton
    public HTTPClient provideHttpClient() {
        return new HTTPClient();
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
    public GnomeAgent startGatewayAgents(
            final SequencedRingBuffer<?> orderOutboundBuffer,
            final SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            final ErrorHandler errorHandler) {
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

        final Properties properties = getInstance(Properties.class);
        final double takerFee = properties.getDoubleProperty("polymarket.intl.taker.fee");
        final double makerFee = properties.getDoubleProperty("polymarket.intl.maker.fee");

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
                takerFee,
                makerFee);

        final PolymarketIntlOutboundWriter writer = new PolymarketIntlOutboundWriter(
                orderOutboundBuffer,
                newOrderQueue,
                writerReportQueue,
                releasedOrderQueue,
                httpClient,
                clobHost,
                orderSigner,
                authHeaders,
                listing);

        return startAgents(reader, writer, config, logger, epochClock, errorHandler);
    }
}
