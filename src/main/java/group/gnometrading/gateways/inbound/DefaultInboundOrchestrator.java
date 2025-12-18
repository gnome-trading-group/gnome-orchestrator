package group.gnometrading.gateways.inbound;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import group.gnometrading.SecurityMaster;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.Schema;
import group.gnometrading.shared.SecurityMasterModule;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;

import java.util.ArrayList;
import java.util.List;

public abstract class DefaultInboundOrchestrator<T extends Schema> extends Orchestrator implements SecurityMasterModule {

    public static final int DEFAULT_RING_BUFFER_SIZE = 1024;

    public static Class<? extends DefaultInboundOrchestrator<?>> findInboundOrchestrator(
            final Listing listing,
            final SecurityMaster securityMaster
    ) {
        final Exchange exchange = securityMaster.getExchange(listing.exchangeId());
        switch (exchange.exchangeName()) {
            case "Hyperliquid" -> {
                return HyperliquidInboundOrchestrator.class;
            }
            case "Lighter" -> {
                return LighterInboundOrchestrator.class;
            }
            default -> throw new IllegalArgumentException("Unmapped exchange: " + exchange.exchangeName());
        }
    }

    @Provides
    public EpochClock provideEpochClock() {
        return new SystemEpochClock();
    }

    @Provides
    public EpochNanoClock provideEpochNanoClock() {
        return new SystemEpochNanoClock();
    }

    @Provides
    @Singleton
    public Disruptor<T> provideDisruptor() {
        return new Disruptor<>(
                provideEventFactory(),
                DEFAULT_RING_BUFFER_SIZE,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new YieldingWaitStrategy()
        );
    }

    @Provides
    @Singleton
    public RingBuffer<T> provideRingBuffer(Disruptor<T> disruptor) {
        return disruptor.getRingBuffer();
    }

    @Provides
    @Singleton
    public abstract SocketReader<T> provideSocketReader();

    @Provides
    public abstract MarketInboundGatewayConfig provideMarketInboundGatewayConfig();

    @Provides
    @Singleton
    public abstract SocketWriter provideSocketWriter();

    @Provides
    public abstract EventFactory<T> provideEventFactory();

    @Provides
    @Singleton
    public MarketInboundGateway provideMarketInboundGateway() {
        return new MarketInboundGateway(
                getInstance(Logger.class),
                getInstance(MarketInboundGatewayConfig.class),
                getInstance(SocketReader.class),
                getInstance(EpochClock.class)
        );
    }

    @Provides
    @Singleton
    @Named("ERROR_TIMESTAMPS")
    public List<Long> provideErrorTimestamps() {
        return new ArrayList<>();
    }

    @Provides
    public ErrorHandler provideInboundErrorHandler() {
        MarketInboundGateway gateway = getInstance(MarketInboundGateway.class);
        Logger logger = getInstance(Logger.class);
        List<Long> errorTimestamps = getInstance(List.class, "ERROR_TIMESTAMPS");

        return (error) -> {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Error occurred in market inbound gateway: %s", error.getMessage());


            long currentTime = System.currentTimeMillis();
            synchronized (errorTimestamps) {
                errorTimestamps.add(currentTime);

                // Remove errors older than 1 minute
                errorTimestamps.removeIf(timestamp -> currentTime - timestamp > 60_000);

                if (errorTimestamps.size() >= 10) {
                    logger.log(LogMessage.FATAL_ERROR_EXITING);
                    System.exit(1);
                    return;
                }
                gateway.forceReconnect();
            }
        };
    }

    @SuppressWarnings("unchecked")
    public void configureGatewayForListing(EventHandler<? super T> consumer) {
        Logger logger = getInstance(Logger.class);
        Listing listing = getInstance(Listing.class);
        logger.logf(LogMessage.DEBUG, "Configuring listing gateway for: %d", listing.listingId());

        Disruptor<T> disruptor = getInstance(Disruptor.class);
        SocketWriter socketWriter = getInstance(SocketWriter.class);
        SocketReader<T> socketReader = getInstance(SocketReader.class);
        MarketInboundGateway marketInboundGateway = getInstance(MarketInboundGateway.class);

        ErrorHandler errorHandler = getInstance(ErrorHandler.class);
        GnomeAgentRunner marketInboundRunner = new GnomeAgentRunner(marketInboundGateway, errorHandler);
        GnomeAgentRunner socketReaderRunner = new GnomeAgentRunner(socketReader, errorHandler);
        GnomeAgentRunner socketWriterRunner = new GnomeAgentRunner(socketWriter, errorHandler);

        disruptor.handleEventsWith(consumer);
        GnomeAgentRunner.startOnThread(marketInboundRunner);
        GnomeAgentRunner.startOnThread(socketReaderRunner);
        GnomeAgentRunner.startOnThread(socketWriterRunner);
        disruptor.start();
    }
}
