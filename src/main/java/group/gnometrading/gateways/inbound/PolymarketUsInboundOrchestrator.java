package group.gnometrading.gateways.inbound;

import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.credentials.PolymarketUsCredentials;
import group.gnometrading.gateways.inbound.exchanges.polymarket.us.PolymarketUsInboundReader;
import group.gnometrading.gateways.outbound.exchanges.polymarket.us.PolymarketUsAuthSigner;
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
import java.time.Duration;
import org.agrona.concurrent.EpochNanoClock;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

public class PolymarketUsInboundOrchestrator extends DefaultInboundOrchestrator<Mbp10Schema> {

    static {
        instanceClass = PolymarketUsInboundOrchestrator.class;
    }

    @Provides
    public final URI provideUri(Properties properties) throws URISyntaxException {
        return new URI(properties.getStringProperty("polymarket.us.ws.url"));
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
    public final InboundSocketWriter provideSocketWriter() {
        WebSocketClient webSocketClient = getInstance(WebSocketClient.class);
        return new InboundJsonWebSocketWriter(webSocketClient, new JsonEncoder());
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
    public final PolymarketUsCredentials providePolymarketUsCredentials(
            SecretsManagerClient secretsManager, Listing listing) {
        String secretName = "gnome/exchange-credentials/polymarket-us";
        String secretJson = secretsManager
                .getSecretValue(
                        GetSecretValueRequest.builder().secretId(secretName).build())
                .secretString();
        return PolymarketUsCredentials.fromJson(secretJson);
    }

    @Override
    @Provides
    @Singleton
    @SuppressWarnings("unchecked")
    public final InboundSocketReader<Mbp10Schema> provideSocketReader() {
        PolymarketUsCredentials credentials = getInstance(PolymarketUsCredentials.class);
        try {
            return new PolymarketUsInboundReader(
                    getInstance(Logger.class),
                    getInstance(SequencedRingBuffer.class),
                    getInstance(EpochNanoClock.class),
                    getInstance(InboundSocketWriter.class),
                    getInstance(Listing.class),
                    getInstance(WebSocketClient.class),
                    new JsonDecoder(),
                    new PolymarketUsAuthSigner(credentials.apiKey(), credentials.privateKey()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create Polymarket US auth signer", e);
        }
    }

    @Override
    @Provides
    public final GatewayConfig provideGatewayConfig() {
        // The heartbeat interval is undocumented; quiet markets only send heartbeats.
        return new GatewayConfig.Builder()
                .withMaxSilentInterval(Duration.ofSeconds(60))
                .build();
    }
}
