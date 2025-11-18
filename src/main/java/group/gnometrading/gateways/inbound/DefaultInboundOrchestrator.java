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
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.Schema;
import group.gnometrading.schemas.SchemaType;
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
                createEventFactory(),
                DEFAULT_RING_BUFFER_SIZE,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new YieldingWaitStrategy()
        );
    }

    protected abstract SocketReader<T> createSocketReader(
            Logger logger,
            RingBuffer<T> ringBuffer,
            SocketWriter socketWriter,
            Listing listing
    );

    protected abstract MarketInboundGatewayConfig getInboundGatewayConfig();

    protected abstract SocketWriter createSocketWriter();

    protected abstract EventFactory<T> createEventFactory();

    public abstract SchemaType defaultSchemaType();

    private ErrorHandler handleInboundError(MarketInboundGateway gateway, Logger logger, List<Long> errorTimestamps) {
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
    public void configureGatewayForListing(Listing listing, EventHandler<? super T> consumer) {
        Logger logger = getInstance(Logger.class);
        logger.logf(LogMessage.DEBUG, "Configuring listing gateway for: %d", listing.listingId());

        Disruptor<T> disruptor = getInstance(Disruptor.class);

        SocketWriter socketWriter = createSocketWriter();
        SocketReader<T> socketReader = createSocketReader(logger, disruptor.getRingBuffer(), socketWriter, listing);
        EpochClock epochClock = getInstance(EpochClock.class);
        MarketInboundGatewayConfig config = getInboundGatewayConfig();

        MarketInboundGateway marketInboundGateway = new MarketInboundGateway(
                config, socketReader, epochClock
        );

        List<Long> errorTimestamps = new ArrayList<>();
        GnomeAgentRunner marketInboundRunner = new GnomeAgentRunner(marketInboundGateway, handleInboundError(marketInboundGateway, logger, errorTimestamps));
        GnomeAgentRunner socketReaderRunner = new GnomeAgentRunner(socketReader, handleInboundError(marketInboundGateway, logger, errorTimestamps));
        GnomeAgentRunner socketWriterRunner = new GnomeAgentRunner(socketWriter, handleInboundError(marketInboundGateway, logger, errorTimestamps));

        disruptor.handleEventsWith(consumer);
        GnomeAgentRunner.startOnThread(marketInboundRunner);
        GnomeAgentRunner.startOnThread(socketReaderRunner);
        GnomeAgentRunner.startOnThread(socketWriterRunner);
        disruptor.start();
    }
}
