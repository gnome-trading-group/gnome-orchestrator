package group.gnometrading.gateways.inbound;

import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedEventHandler;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.shared.RiskModule;
import group.gnometrading.sm.Listing;
import java.util.ArrayList;
import java.util.List;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;

public abstract class DefaultInboundOrchestrator<T extends Schema> extends Orchestrator {

    @Override
    public final void configure() {
        install(new RiskModule());
    }

    public static final int DEFAULT_RING_BUFFER_SIZE = 1024;

    public static Class<? extends DefaultInboundOrchestrator<?>> findInboundOrchestrator(final Listing listing) {
        switch (listing.exchange().exchangeName()) {
            case "Hyperliquid" -> {
                return HyperliquidInboundOrchestrator.class;
            }
            case "Lighter" -> {
                return LighterInboundOrchestrator.class;
            }
            default -> throw new IllegalArgumentException(
                    "Unmapped exchange: " + listing.exchange().exchangeName());
        }
    }

    @Provides
    public final EpochClock provideEpochClock() {
        return SystemEpochClock.INSTANCE;
    }

    @Provides
    public final EpochNanoClock provideEpochNanoClock() {
        return new SystemEpochNanoClock();
    }

    @Provides
    @Singleton
    public final GlobalSequence provideGlobalSequence() {
        return new GlobalSequence();
    }

    @Provides
    @Singleton
    public abstract SequencedRingBuffer<T> provideSequencedRingBuffer();

    @Provides
    @Singleton
    public abstract SocketReader<T> provideSocketReader();

    @Provides
    public abstract MarketInboundGatewayConfig provideMarketInboundGatewayConfig();

    @Provides
    @Singleton
    public abstract SocketWriter provideSocketWriter();

    @Provides
    @Singleton
    public final MarketInboundGateway provideMarketInboundGateway() {
        return new MarketInboundGateway(
                getInstance(Logger.class),
                getInstance(MarketInboundGatewayConfig.class),
                getInstance(SocketReader.class),
                getInstance(EpochClock.class));
    }

    @Provides
    @Singleton
    @Named("ERROR_TIMESTAMPS")
    public final List<Long> provideErrorTimestamps() {
        return new ArrayList<>();
    }

    @Provides
    public final ErrorHandler provideInboundErrorHandler() {
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
    public final void configureGatewayForListing(SequencedEventHandler consumer) {
        Logger logger = getInstance(Logger.class);
        Listing listing = getInstance(Listing.class);
        logger.logf(LogMessage.DEBUG, "Configuring listing gateway for: %d", listing.listingId());

        SequencedRingBuffer<T> sequencedRingBuffer = getInstance(SequencedRingBuffer.class);
        SocketWriter socketWriter = getInstance(SocketWriter.class);
        SocketReader<T> socketReader = getInstance(SocketReader.class);
        MarketInboundGateway marketInboundGateway = getInstance(MarketInboundGateway.class);

        ErrorHandler errorHandler = getInstance(ErrorHandler.class);
        GnomeAgentRunner marketInboundRunner = new GnomeAgentRunner(marketInboundGateway, errorHandler);
        GnomeAgentRunner socketReaderRunner = new GnomeAgentRunner(socketReader, errorHandler);
        GnomeAgentRunner socketWriterRunner = new GnomeAgentRunner(socketWriter, errorHandler);

        sequencedRingBuffer.handleEventsWith(consumer);
        GnomeAgentRunner.startOnThread(marketInboundRunner);
        GnomeAgentRunner.startOnThread(socketReaderRunner);
        GnomeAgentRunner.startOnThread(socketWriterRunner);
        sequencedRingBuffer.start();
    }
}
