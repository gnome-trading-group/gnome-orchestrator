package group.gnometrading.collectors;

import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.collector.BulkMarketDataCollector;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.JSONWebSocketMarketInboundGateway;
import group.gnometrading.gateways.MarketInboundGateway;
import group.gnometrading.gateways.exchanges.hyperliquid.HyperliquidInboundGateway;
import group.gnometrading.ipc.IPCManager;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sm.Listing;
import group.gnometrading.utils.AgentUtils;
import io.aeron.Publication;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class HyperliquidCollectorOrchestrator extends DefaultCollectorOrchestrator {

    static {
        instanceClass = HyperliquidCollectorOrchestrator.class;
    }

    private static final Logger logger = LoggerFactory.getLogger(HyperliquidCollectorOrchestrator.class);

    @Provides
    public URI provideURI(Properties properties) throws URISyntaxException {
        return new URI(properties.getStringProperty("hyperliquid.ws.url"));
    }

    @Singleton
    @Provides
    public WebSocketClient provideWSClient(URI uri) throws IOException {
        return new WebSocketClientBuilder()
                .withURI(uri)
                .withSocketFactory(new NativeSSLSocketFactory())
                .withReadBufferSize(1 << 19) // 512 kb
                .build();
    }

    @Provides
    @Singleton
    public Publication providePublication(IPCManager ipcManager) {
        return ipcManager.addExclusivePublication(STREAM_NAME);
    }

    @Singleton
    @Provides
    public MarketInboundGateway provideMarketInboundGateway(
            WebSocketClient webSocketClient,
            Publication publication,
            SchemaType schemaType,
            Listing listing
    ) {
        return new HyperliquidInboundGateway(
                publication,
                new SystemEpochNanoClock(),
                schemaType,
                webSocketClient,
                new JSONDecoder(),
                new JSONEncoder(),
                JSONWebSocketMarketInboundGateway.DEFAULT_WRITE_BUFFER_SIZE,
                listing
        );
    }

    @Provides
    public SchemaType provideSchemaType() {
        return SchemaType.MBP_10;
    }

    public void handleInboundError(Throwable error) {
        logger.info("Unknown error occurred in market inbound gateway", error);

        logger.info("Marking the inbound gateway for reconnection...");
        MarketInboundGateway marketInboundGateway = getInstance(MarketInboundGateway.class);
        marketInboundGateway.markReconnect();
    }

    public void handleOutboundError(Throwable error) {
        logger.info("Unknown error occurred in market update collector", error);
        // TODO: What should we do here?
    }

    @Override
    protected void configure() {
        MarketInboundGateway marketInboundGateway = getInstance(MarketInboundGateway.class);
        BulkMarketDataCollector marketUpdateCollector = getInstance(BulkMarketDataCollector.class);
        Listing listing = getInstance(Listing.class);
        SchemaType schemaType = getInstance(SchemaType.class);

        final var publicationAgentRunner = new AgentRunner(new YieldingIdleStrategy(), this::handleInboundError, null, marketInboundGateway);
        final var subscriptionAgentRunner = new AgentRunner(new YieldingIdleStrategy(), this::handleOutboundError, null, marketUpdateCollector);

        AgentUtils.startRunnerWithShutdownProtection(publicationAgentRunner);
        AgentUtils.startRunnerWithShutdownProtection(subscriptionAgentRunner);

        logger.info("Started everything up with listing {} on exchange {} with schema {}", listing.exchangeSecuritySymbol(), listing.exchangeId(), schemaType);
    }
}
