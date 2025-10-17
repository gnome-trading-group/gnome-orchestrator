package group.gnometrading.collectors;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.JSONWebSocketWriter;
import group.gnometrading.gateways.inbound.MarketInboundGatewayConfig;
import group.gnometrading.gateways.inbound.SocketReader;
import group.gnometrading.gateways.inbound.SocketWriter;
import group.gnometrading.gateways.inbound.exchanges.hyperliquid.HyperliquidSocketReader;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.MBP10Schema;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.shared.PropertiesModule;
import group.gnometrading.sm.Listing;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class HyperliquidCollectorOrchestrator extends DefaultCollectorOrchestrator<MBP10Schema> implements PropertiesModule {

    static {
        instanceClass = HyperliquidCollectorOrchestrator.class;
    }

    @Provides
    public URI provideURI(Properties properties) throws URISyntaxException {
        return new URI(properties.getStringProperty("hyperliquid.ws.url"));
    }

    @Provides
    @Singleton
    public WebSocketClient provideWSClient(URI uri) throws IOException {
        return new WebSocketClientBuilder()
                .withURI(uri)
                .withSocketFactory(new NativeSSLSocketFactory())
                .withReadBufferSize(1 << 19) // 512 kb
                .build();
    }

    @Override
    protected SocketWriter createSocketWriter() {
        WebSocketClient webSocketClient = getInstance(WebSocketClient.class);
        return new JSONWebSocketWriter(webSocketClient, new JSONEncoder());
    }

    @Override
    protected SocketReader<MBP10Schema> createSocketReader(
            Logger logger,
            RingBuffer<MBP10Schema> ringBuffer,
            SocketWriter socketWriter,
            Listing listing
    ) {
        WebSocketClient webSocketClient = getInstance(WebSocketClient.class);
        EpochNanoClock epochNanoClock = getInstance(EpochNanoClock.class);
        return new HyperliquidSocketReader(
                logger,
                ringBuffer,
                epochNanoClock,
                socketWriter,
                webSocketClient,
                new JSONDecoder(),
                listing
        );
    }

    @Override
    protected EventFactory<MBP10Schema> createEventFactory() {
        return MBP10Schema::new;
    }

    @Override
    protected SchemaType defaultSchemaType() {
        return SchemaType.MBP_10;
    }

    @Override
    protected MarketInboundGatewayConfig getInboundGatewayConfig() {
        return new MarketInboundGatewayConfig.Builder()
                .build();
    }
}
