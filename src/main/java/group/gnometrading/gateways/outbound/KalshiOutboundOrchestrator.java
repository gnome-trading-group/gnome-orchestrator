package group.gnometrading.gateways.outbound;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.di.Named;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.credentials.KalshiCredentials;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiAuthSigner;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiOutboundReader;
import group.gnometrading.gateways.outbound.exchanges.kalshi.KalshiOutboundWriter;
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

public final class KalshiOutboundOrchestrator extends DefaultOutboundOrchestrator {

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
    public KalshiCredentials provideCredentials(final SecretsManagerClient secretsManager) {
        final String secretJson = secretsManager
                .getSecretValue(GetSecretValueRequest.builder()
                        .secretId("gnome/exchange-credentials/kalshi")
                        .build())
                .secretString();
        return KalshiCredentials.fromJson(secretJson);
    }

    @Provides
    @Named("WRITER")
    public KalshiAuthSigner provideWriterAuthSigner(final KalshiCredentials credentials) throws IOException {
        return new KalshiAuthSigner(credentials.apiKey(), credentials.privateKey());
    }

    @Provides
    @Named("READER")
    public KalshiAuthSigner provideReaderAuthSigner(final KalshiCredentials credentials) throws IOException {
        return new KalshiAuthSigner(credentials.apiKey(), credentials.privateKey());
    }

    @Provides
    @Singleton
    public HTTPClient provideHttpClient() {
        return new HTTPClient();
    }

    @Provides
    @Named("API_HOST")
    public String provideApiHost(final Properties properties) throws URISyntaxException {
        return new URI(properties.getStringProperty("kalshi.api.url")).getHost();
    }

    @Provides
    @Singleton
    public URI provideUserWsUri(final Properties properties) throws URISyntaxException {
        return new URI(properties.getStringProperty("kalshi.user.ws.url"));
    }

    @Provides
    @Singleton
    public WebSocketClient provideUserWsClient(final URI userWsUri) throws IOException {
        return new WebSocketClientBuilder()
                .withURI(userWsUri)
                .withSocketFactory(new NativeSSLSocketFactory())
                .withReadBufferSize(1 << 16) // 64 KiB
                .build();
    }

    @Provides
    public GatewayConfig provideGatewayConfig() {
        return new GatewayConfig.Builder()
                .withMaxSilentInterval(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public GnomeAgent startGatewayAgents(
            final SequencedRingBuffer<?> orderOutboundBuffer,
            final SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            final ErrorHandler errorHandler) {
        final Logger logger = getInstance(Logger.class);
        final EpochNanoClock nanoClock = getInstance(EpochNanoClock.class);
        final EpochClock epochClock = getInstance(EpochClock.class);
        final Listing listing = getInstance(Listing.class);
        final WebSocketClient wsClient = getInstance(WebSocketClient.class);
        final String apiHost = getInstance(String.class, "API_HOST");
        final GatewayConfig config = getInstance(GatewayConfig.class);
        final KalshiAuthSigner writerSigner = getInstance(KalshiAuthSigner.class, "WRITER");
        final KalshiAuthSigner readerSigner = getInstance(KalshiAuthSigner.class, "READER");
        final HTTPClient httpClient = getInstance(HTTPClient.class);

        final ManyToOneRingBuffer<OrderContext> contextQueue = createOrderContextQueue();
        final ManyToOneRingBuffer<OrderContext> rejectQueue = createOrderContextQueue();
        final ManyToOneRingBuffer<OrderContext> completionQueue = createOrderContextQueue();

        final KalshiOutboundReader reader = new KalshiOutboundReader(
                logger,
                execReportBuffer,
                contextQueue,
                rejectQueue,
                completionQueue,
                nanoClock,
                listing,
                wsClient,
                new JsonDecoder(),
                readerSigner);

        final KalshiOutboundWriter writer = new KalshiOutboundWriter(
                orderOutboundBuffer,
                contextQueue,
                rejectQueue,
                completionQueue,
                httpClient,
                apiHost,
                writerSigner,
                nanoClock,
                listing);

        return startAgents(reader, writer, config, logger, epochClock, errorHandler);
    }
}
