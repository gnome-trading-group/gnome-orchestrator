package group.gnometrading.collectors;

import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.di.Provides;
import group.gnometrading.gateways.JSONWebSocketMarketInboundGateway;
import group.gnometrading.gateways.MarketInboundGateway;
import group.gnometrading.gateways.exchanges.hyperliquid.HyperliquidInboundGateway;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sm.Listing;
import io.aeron.Publication;
import org.agrona.concurrent.SystemEpochNanoClock;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class HyperliquidCollectorOrchestrator extends DefaultCollectorOrchestrator {

    static {
        instanceClass = HyperliquidCollectorOrchestrator.class;
    }

    @Provides
    public URI provideURI(Properties properties) throws URISyntaxException {
        return new URI(properties.getStringProperty("hyperliquid.ws.url"));
    }

    @Provides // Do not make this a singleton...unless you want problems, son.
    public WebSocketClient provideWSClient(URI uri) throws IOException {
        return new WebSocketClientBuilder()
                .withURI(uri)
                .withSocketFactory(new NativeSSLSocketFactory())
                .withReadBufferSize(1 << 19) // 512 kb
                .build();
    }

    @Override
    protected MarketInboundGateway createInboundGateway(Publication publication, Listing listing) {
        WebSocketClient webSocketClient = getInstance(WebSocketClient.class);
        return new HyperliquidInboundGateway(
                publication,
                new SystemEpochNanoClock(),
                defaultSchemaType(),
                webSocketClient,
                new JSONDecoder(),
                new JSONEncoder(),
                JSONWebSocketMarketInboundGateway.DEFAULT_WRITE_BUFFER_SIZE,
                listing
        );
    }

    @Override
    protected SchemaType defaultSchemaType() {
        return SchemaType.MBP_10;
    }
}
