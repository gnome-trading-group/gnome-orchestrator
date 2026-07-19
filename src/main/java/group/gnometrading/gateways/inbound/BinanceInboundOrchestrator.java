package group.gnometrading.gateways.inbound;

import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.credentials.BinanceCredentials;
import group.gnometrading.gateways.fix.FixConfig;
import group.gnometrading.gateways.fix.FixSocketMessageClient;
import group.gnometrading.gateways.fix.FixTimestampPrecision;
import group.gnometrading.gateways.fix.FixVersion;
import group.gnometrading.gateways.inbound.exchanges.binance.BinanceFixSocketReader;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.net.InetSocketAddress;
import org.agrona.concurrent.EpochNanoClock;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

public class BinanceInboundOrchestrator extends DefaultInboundOrchestrator<Mbp10Schema> {

    private static final int FIX_READ_BUFFER_SIZE = 1 << 17; // 128 kb
    private static final int FIX_WRITE_BUFFER_SIZE = 1 << 12; // 4 kb

    static {
        instanceClass = BinanceInboundOrchestrator.class;
    }

    @Provides
    public final FixConfig provideFixConfig(Properties properties, BinanceCredentials credentials) {
        return new FixConfig.Builder()
                .withSessionVersion(FixVersion.FIXT_1_1)
                .withApplicationVersion(FixVersion.FIX_5_0SP2)
                .withSenderCompID(properties.getStringProperty("binance.fix.sender_comp_id"))
                .withTargetCompID(properties.getStringProperty("binance.fix.target_comp_id"))
                .withHeartbeatSeconds(30)
                .withDefaultPrecision(FixTimestampPrecision.MICROSECONDS)
                .build();
    }

    @Provides
    public final InetSocketAddress provideFixAddress(Properties properties) {
        String host = properties.getStringProperty("binance.fix.host");
        int port = properties.getIntProperty("binance.fix.port");
        return new InetSocketAddress(host, port);
    }

    @Provides
    public final GnomeSocketFactory provideSocketFactory() {
        return new NativeSSLSocketFactory();
    }

    @Provides
    @Singleton
    public final FixSocketMessageClient provideFixClient(
            InetSocketAddress address, GnomeSocketFactory socketFactory, FixConfig fixConfig) throws IOException {
        return new FixSocketMessageClient(
                address, socketFactory, fixConfig, FIX_READ_BUFFER_SIZE, FIX_WRITE_BUFFER_SIZE);
    }

    @Provides
    @Singleton
    public final BinanceCredentials provideBinanceCredentials(SecretsManagerClient secretsManager, Listing listing) {
        String secretName =
                "exchange-credentials/" + listing.exchange().exchangeName().toLowerCase();
        String secretJson = secretsManager
                .getSecretValue(
                        GetSecretValueRequest.builder().secretId(secretName).build())
                .secretString();
        return BinanceCredentials.fromJson(secretJson);
    }

    @Override
    @Provides
    @Singleton
    public final SocketWriter provideSocketWriter() {
        return new NoOpSocketWriter();
    }

    @Override
    @Provides
    @Singleton
    @SuppressWarnings("unchecked")
    public final SequencedRingBuffer<Mbp10Schema> provideSequencedRingBuffer() {
        return new SequencedRingBuffer<>(Mbp10Schema::new, getInstance(GlobalSequence.class));
    }

    @Override
    @Provides
    @Singleton
    @SuppressWarnings("unchecked")
    public final SocketReader<Mbp10Schema> provideSocketReader() {
        BinanceCredentials credentials = getInstance(BinanceCredentials.class);
        return new BinanceFixSocketReader(
                getInstance(Logger.class),
                getInstance(SequencedRingBuffer.class),
                getInstance(EpochNanoClock.class),
                getInstance(FixSocketMessageClient.class),
                getInstance(Listing.class),
                getInstance(FixConfig.class),
                credentials.privateKey(),
                credentials.apiKey());
    }

    @Override
    @Provides
    public final MarketInboundGatewayConfig provideMarketInboundGatewayConfig() {
        return new MarketInboundGatewayConfig.Builder().build();
    }
}
