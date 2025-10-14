package group.gnometrading.collectors;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import group.gnometrading.SecurityMaster;
import group.gnometrading.collector.BulkMarketDataCollector;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.MarketInboundGateway;
import group.gnometrading.gateways.inbound.MarketInboundGatewayConfig;
import group.gnometrading.gateways.inbound.SocketReader;
import group.gnometrading.gateways.inbound.SocketWriter;
import group.gnometrading.schemas.Schema;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.shared.AWSModule;
import group.gnometrading.shared.SecurityMasterModule;
import group.gnometrading.sm.Listing;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class DefaultCollectorOrchestrator<T extends Schema> extends Orchestrator implements AWSModule, SecurityMasterModule {

    private static final Logger logger = LoggerFactory.getLogger(DefaultCollectorOrchestrator.class);
    public static final int DEFAULT_RING_BUFFER_SIZE = 1024;

    @Provides
    @Named("OUTPUT_BUCKET")
    public String provideBucketName() {
        return System.getenv("OUTPUT_BUCKET");
    }

    @Provides
    @Named("CONTROLLER_URL")
    public String provideControllerURL() {
        return System.getenv("CONTROLLER_URL");
    }

    @Provides
    @Named("CONTROLLER_API_KEY")
    public String provideControllerAPIKey() {
        return System.getenv("CONTROLLER_API_KEY");
    }

    @Provides
    public Clock provideClock() {
        return Clock.systemUTC();
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
    @Named("LISTINGS")
    public List<Listing> provideListings(SecurityMaster securityMaster) {
        List<Listing> output = new ArrayList<>();
        String listingIds = System.getenv("LISTING_IDS");
        for (String listingId : listingIds.split(",")) {
            output.add(securityMaster.getListing(Integer.parseInt(listingId)));
        }
        return output;
    }

    private BulkMarketDataCollector createBulkMarketDataCollector(Listing listing) {
        return new BulkMarketDataCollector(
                getInstance(Clock.class),
                getInstance(S3Client.class),
                listing,
                getInstance(String.class, "OUTPUT_BUCKET"),
                defaultSchemaType()
        );
    }

    private Disruptor<T> createDisruptor() {
        return new Disruptor<>(
                createEventFactory(),
                DEFAULT_RING_BUFFER_SIZE,
                DaemonThreadFactory.INSTANCE,
                ProducerType.SINGLE,
                new YieldingWaitStrategy()
        );
    }

    protected abstract SocketReader<T> createSocketReader(
            RingBuffer<T> ringBuffer,
            SocketWriter socketWriter,
            Listing listing
    );

    protected abstract MarketInboundGatewayConfig getInboundGatewayConfig();

    protected abstract SocketWriter createSocketWriter();

    protected abstract EventFactory<T> createEventFactory();

    protected abstract SchemaType defaultSchemaType();

    private ErrorHandler handleInboundError(MarketInboundGateway gateway, AtomicInteger errorCounter) {
        return (error) -> {
          logger.info("Unknown error occurred in market inbound gateway", error);
          int errorNum = errorCounter.incrementAndGet();
          if (errorNum > 5) {
              logger.info("Maximum errors thrown. Exiting program");
              System.exit(1);
              return;
          }
          gateway.forceReconnect();
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public void configure() {
        logger.info("Beginning collector for: {}", this.getClass().getSimpleName());
        List<Listing> listings = this.getInstance(List.class, "LISTINGS");

        for (Listing listing : listings) {
            Disruptor<T> disruptor = createDisruptor();

            SocketWriter socketWriter = createSocketWriter();
            SocketReader<T> socketReader = createSocketReader(disruptor.getRingBuffer(), socketWriter, listing);
            EpochClock epochClock = getInstance(EpochClock.class);
            MarketInboundGatewayConfig config = getInboundGatewayConfig();

            MarketInboundGateway marketInboundGateway = new MarketInboundGateway(
                    config, socketReader, epochClock
            );
            BulkMarketDataCollector bulkMarketDataCollector = createBulkMarketDataCollector(listing);

            AtomicInteger errorCounter = new AtomicInteger(0);
            GnomeAgentRunner marketInboundRunner = new GnomeAgentRunner(marketInboundGateway, handleInboundError(marketInboundGateway, errorCounter));
            GnomeAgentRunner socketReaderRunner = new GnomeAgentRunner(socketReader, handleInboundError(marketInboundGateway, errorCounter));
            GnomeAgentRunner socketWriterRunner = new GnomeAgentRunner(socketWriter, handleInboundError(marketInboundGateway, errorCounter));

            disruptor.handleEventsWith(bulkMarketDataCollector);
            GnomeAgentRunner.startOnThread(marketInboundRunner);
            GnomeAgentRunner.startOnThread(socketReaderRunner);
            GnomeAgentRunner.startOnThread(socketWriterRunner);

            disruptor.start();

            logger.info("Started listing {} on exchange {} with schema {} on class {}",
                    listing.exchangeSecuritySymbol(),
                    listing.exchangeId(),
                    defaultSchemaType(),
                    marketInboundGateway.getClass().getSimpleName()
            );
        }

        logger.info("Finished configuring collector");
    }
}
