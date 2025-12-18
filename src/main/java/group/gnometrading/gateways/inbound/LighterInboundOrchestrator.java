package group.gnometrading.gateways.inbound;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import group.gnometrading.codecs.json.JSONDecoder;
import group.gnometrading.codecs.json.JSONEncoder;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.exchanges.lighter.LighterSocketReader;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.MBP10Schema;
import group.gnometrading.sm.Listing;
import org.agrona.concurrent.EpochNanoClock;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class LighterInboundOrchestrator extends DefaultInboundOrchestrator<MBP10Schema> {

    static {
        instanceClass = LighterInboundOrchestrator.class;
    }


    @Provides
    public URI provideURI(Properties properties) throws URISyntaxException {
        return new URI(properties.getStringProperty("lighter.ws.url"));
    }

    @Provides
    public GnomeSocketFactory provideSocketFactory() {
        return new NativeSSLSocketFactory();
    }

    @Provides
    @Singleton
    public WebSocketClient provideWSClient(URI uri, GnomeSocketFactory socketFactory) throws IOException {
        return new WebSocketClientBuilder()
                .withURI(uri)
                .withSocketFactory(socketFactory)
                .withReadBufferSize(1 << 19) // 512 kb
                .build();
    }

    @Override
    @Provides
    @Singleton
    public SocketWriter provideSocketWriter() {
        WebSocketClient webSocketClient = getInstance(WebSocketClient.class);
        return new JSONWebSocketWriter(webSocketClient, new JSONEncoder());
    }

    @Override
    @Provides
    @Singleton
    @SuppressWarnings("unchecked")
    public SocketReader<MBP10Schema> provideSocketReader() {
        return new LighterSocketReader(
                getInstance(Logger.class),
                getInstance(RingBuffer.class),
                getInstance(EpochNanoClock.class),
                getInstance(SocketWriter.class),
                getInstance(Listing.class),
                getInstance(WebSocketClient.class),
                new JSONDecoder()
        );
    }

    @Override
    @Provides
    public EventFactory<MBP10Schema> provideEventFactory() {
        return MBP10Schema::new;
    }

    @Override
    @Provides
    public MarketInboundGatewayConfig provideMarketInboundGatewayConfig() {
        return new MarketInboundGatewayConfig.Builder()
                .build();
    }

}
