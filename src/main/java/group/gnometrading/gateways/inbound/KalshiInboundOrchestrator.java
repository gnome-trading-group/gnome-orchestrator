package group.gnometrading.gateways.inbound;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.exchanges.kalshi.KalshiSocketReader;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.agrona.concurrent.EpochNanoClock;

public class KalshiInboundOrchestrator extends DefaultInboundOrchestrator<Mbp10Schema> {

    static {
        instanceClass = KalshiInboundOrchestrator.class;
    }

    @Provides
    public final URI provideUri(Properties properties) throws URISyntaxException {
        return new URI(properties.getStringProperty("kalshi.ws.url"));
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
                .withReadBufferSize(1 << 18) // 256 kb
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
    public final SequencedRingBuffer<Mbp10Schema> provideSequencedRingBuffer() {
        return new SequencedRingBuffer<>(Mbp10Schema::new, getInstance(GlobalSequence.class));
    }

    @Provides
    @Singleton
    public final PrivateKey providePrivateKey(Properties properties) throws Exception {
        String keyPath = properties.getStringProperty("kalshi.private.key.path");
        String pem = Files.readString(Path.of(keyPath));
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    @Override
    @Provides
    @Singleton
    @SuppressWarnings("unchecked")
    public final SocketReader<Mbp10Schema> provideSocketReader() {
        Properties properties = getInstance(Properties.class);
        return new KalshiSocketReader(
                getInstance(Logger.class),
                getInstance(SequencedRingBuffer.class),
                getInstance(EpochNanoClock.class),
                getInstance(SocketWriter.class),
                getInstance(Listing.class),
                getInstance(WebSocketClient.class),
                new JsonDecoder(),
                properties.getStringProperty("kalshi.api.key"),
                getInstance(PrivateKey.class));
    }

    @Override
    @Provides
    public final MarketInboundGatewayConfig provideMarketInboundGatewayConfig() {
        return new MarketInboundGatewayConfig.Builder().build();
    }
}
