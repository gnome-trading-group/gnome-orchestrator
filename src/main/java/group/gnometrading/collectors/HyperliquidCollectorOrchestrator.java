package group.gnometrading.collectors;

import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.collector.MarketUpdateCollector;
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
import group.gnometrading.sm.Listing;
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
        return ipcManager.addPublication(STREAM_NAME);
    }

    @Singleton
    @Provides
    public MarketInboundGateway provideMarketInboundGateway(
            WebSocketClient webSocketClient,
            Publication publication,
            Listing listing
    ) {
        return new HyperliquidInboundGateway(
                webSocketClient,
                publication,
                new SystemEpochNanoClock(),
                new JSONDecoder(),
                JSONWebSocketMarketInboundGateway.DEFAULT_WRITE_BUFFER_SIZE,
                listing,
                new JSONEncoder()
        );
    }

    @Override
    protected void configure() {
        MarketInboundGateway marketInboundGateway = getInstance(MarketInboundGateway.class);
        MarketUpdateCollector marketUpdateCollector = getInstance(MarketUpdateCollector.class);
        Listing listing = getInstance(Listing.class);

        final var publicationAgentRunner = new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, marketInboundGateway);
        final var subscriptionAgentRunner = new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, marketUpdateCollector);
        AgentRunner.startOnThread(publicationAgentRunner);
        AgentRunner.startOnThread(subscriptionAgentRunner);
        logger.info("Started everything up with listing {} on exchange {}!", listing.exchangeSecuritySymbol(), listing.exchangeId());
    }
}
