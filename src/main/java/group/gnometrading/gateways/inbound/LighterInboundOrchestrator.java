package group.gnometrading.gateways.inbound;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.exchanges.lighter.LighterSocketReader;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import org.agrona.concurrent.EpochNanoClock;

public class LighterInboundOrchestrator extends DefaultInboundOrchestrator<Mbp10Schema> {

    static {
        instanceClass = LighterInboundOrchestrator.class;
    }

    @Provides
    public final URI provideUri(Properties properties) throws URISyntaxException {
        return new URI(properties.getStringProperty("lighter.ws.url"));
    }

    @Provides
    public final GnomeSocketFactory provideSocketFactory() {
        return new NativeSSLSocketFactory();
    }

    @Provides
    @Singleton
    public final WebSocketClient provideWsClient(URI uri, GnomeSocketFactory socketFactory) throws IOException {
        return new WebSocketClientBuilder()
                .withURI(uri)
                .withSocketFactory(socketFactory)
                .withReadBufferSize(1 << 19) // 512 kb
                .build();
    }

    @Override
    @Provides
    @Singleton
    public final SocketWriter provideSocketWriter() {
        WebSocketClient webSocketClient = getInstance(WebSocketClient.class);
        return new JsonWebSocketWriter(webSocketClient, new JsonEncoder());
    }

    @Override
    @Provides
    @Singleton
    @SuppressWarnings("unchecked")
    public final SocketReader<Mbp10Schema> provideSocketReader() {
        return new LighterSocketReader(
                getInstance(Logger.class),
                getInstance(RingBuffer.class),
                getInstance(EpochNanoClock.class),
                getInstance(SocketWriter.class),
                getInstance(Listing.class),
                getInstance(WebSocketClient.class),
                new JsonDecoder());
    }

    @Override
    @Provides
    public final EventFactory<Mbp10Schema> provideEventFactory() {
        return Mbp10Schema::new;
    }

    @Override
    @Provides
    public final MarketInboundGatewayConfig provideMarketInboundGatewayConfig() {
        return new MarketInboundGatewayConfig.Builder().build();
    }
}
