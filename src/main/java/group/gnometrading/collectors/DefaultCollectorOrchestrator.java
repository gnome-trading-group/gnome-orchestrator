package group.gnometrading.collectors;

import group.gnometrading.SecurityMaster;
import group.gnometrading.collector.BulkMarketDataCollector;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.MarketInboundGateway;
import group.gnometrading.ipc.IPCManager;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.shared.AWSModule;
import group.gnometrading.shared.SecurityMasterModule;
import group.gnometrading.sm.Listing;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class DefaultCollectorOrchestrator extends Orchestrator implements AWSModule, SecurityMasterModule {

    private static final Logger logger = LoggerFactory.getLogger(DefaultCollectorOrchestrator.class);

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
    @Singleton
    public Aeron provideAeron() {
        final MediaDriver.Context mediaDriverCtx = new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .aeronDirectoryName("/dev/shm/aeron");

        MediaDriver driver = MediaDriver.launch(mediaDriverCtx);
        return Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
    }

    @Provides
    @Singleton
    public IPCManager provideIPCManager(Aeron aeron) {
        return new IPCManager(aeron);
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

    private BulkMarketDataCollector createBulkMarketDataCollector(
            Subscription subscription,
            Listing listing
    ) {
        return new BulkMarketDataCollector(
                subscription,
                getInstance(Clock.class),
                getInstance(S3Client.class),
                listing,
                getInstance(String.class, "OUTPUT_BUCKET"),
                defaultSchemaType()
        );
    }

    protected abstract MarketInboundGateway createInboundGateway(
        Publication publication,
        Listing listing
    );

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
          gateway.markReconnect();
        };
    }

    private void handleOutboundError(Throwable error) {
        logger.info("Unknown error occurred in market update collector", error);
        // TODO: What shutdown protection should we add?
        System.exit(1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void configure() {
        logger.info("Beginning collector for: {}", this.getClass().getSimpleName());
        List<Listing> listings = this.getInstance(List.class, "LISTINGS");
        IPCManager ipcManager = getInstance(IPCManager.class);

        for (Listing listing : listings) {
            String streamName = "collector#" + listing.listingId();

            MarketInboundGateway marketInboundGateway = createInboundGateway(ipcManager.addExclusivePublication(streamName), listing);
            BulkMarketDataCollector bulkMarketDataCollector = createBulkMarketDataCollector(ipcManager.addSubscription(streamName), listing);

            AtomicInteger errorCounter = new AtomicInteger(0);
            final var publicationAgentRunner = new AgentRunner(new YieldingIdleStrategy(), this.handleInboundError(marketInboundGateway, errorCounter), null, marketInboundGateway);
            final var subscriptionAgentRunner = new AgentRunner(new YieldingIdleStrategy(), this::handleOutboundError, null, bulkMarketDataCollector);

            AgentRunner.startOnThread(publicationAgentRunner);
            AgentRunner.startOnThread(subscriptionAgentRunner);

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
