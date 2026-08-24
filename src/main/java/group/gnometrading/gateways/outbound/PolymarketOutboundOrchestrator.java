package group.gnometrading.gateways.outbound;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Named;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.credentials.PolymarketCredentials;
import group.gnometrading.gateways.outbound.exchanges.polymarket.PolymarketAuthHeaders;
import group.gnometrading.gateways.outbound.exchanges.polymarket.PolymarketOrderSigner;
import group.gnometrading.gateways.outbound.exchanges.polymarket.PolymarketOutboundReader;
import group.gnometrading.gateways.outbound.exchanges.polymarket.PolymarketOutboundWriter;
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

public final class PolymarketOutboundOrchestrator extends DefaultOutboundOrchestrator {

    private static final int QUEUE_CAPACITY = 1 << 7; // 128 slots

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
    public PolymarketCredentials provideCredentials(SecretsManagerClient secretsManager) {
        final String secretJson = secretsManager
                .getSecretValue(GetSecretValueRequest.builder()
                        .secretId("gnome/exchange-credentials/polymarket")
                        .build())
                .secretString();
        return PolymarketCredentials.fromJson(secretJson);
    }

    @Provides
    @Singleton
    public PolymarketOrderSigner provideOrderSigner(PolymarketCredentials credentials) {
        return new PolymarketOrderSigner(credentials.ethereumPrivateKey(), credentials.signerAddress());
    }

    @Provides
    @Singleton
    public PolymarketAuthHeaders provideAuthHeaders(PolymarketCredentials credentials) {
        return new PolymarketAuthHeaders(
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
        return new URI(properties.getStringProperty("polymarket.user.ws.url"));
    }

    @Provides
    @Named("CLOB_HOST")
    public String provideClobHost(Properties properties) throws URISyntaxException {
        return new URI(properties.getStringProperty("polymarket.clob.url")).getHost();
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
        final PolymarketCredentials credentials = getInstance(PolymarketCredentials.class);
        final Logger logger = getInstance(Logger.class);
        final EpochNanoClock nanoClock = getInstance(EpochNanoClock.class);
        final EpochClock epochClock = getInstance(EpochClock.class);
        final Listing listing = getInstance(Listing.class);
        final WebSocketClient wsClient = getInstance(WebSocketClient.class);
        final String clobHost = getInstance(String.class, "CLOB_HOST");
        final GatewayConfig config = getInstance(GatewayConfig.class);
        final PolymarketOrderSigner orderSigner = getInstance(PolymarketOrderSigner.class);
        final PolymarketAuthHeaders authHeaders = getInstance(PolymarketAuthHeaders.class);
        final HTTPClient httpClient = getInstance(HTTPClient.class);

        final ManyToOneRingBuffer<OrderContext> contextQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, QUEUE_CAPACITY);
        final ManyToOneRingBuffer<OrderContext> rejectQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, QUEUE_CAPACITY);
        final ManyToOneRingBuffer<OrderContext> completionQueue =
                new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, QUEUE_CAPACITY);

        final PolymarketOutboundReader reader = new PolymarketOutboundReader(
                logger,
                execReportBuffer,
                contextQueue,
                rejectQueue,
                completionQueue,
                nanoClock,
                listing,
                wsClient,
                new JsonDecoder(),
                credentials.apiKey(),
                credentials.secret(),
                credentials.passphrase());

        final PolymarketOutboundWriter writer = new PolymarketOutboundWriter(
                orderOutboundBuffer,
                contextQueue,
                rejectQueue,
                completionQueue,
                httpClient,
                clobHost,
                orderSigner,
                authHeaders,
                listing);

        final OutboundGateway gateway = new OutboundGateway(logger, reader, config, epochClock);

        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(gateway, errorHandler));
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(reader, errorHandler));

        return writer;
    }
}
