package group.gnometrading.collectors;

import group.gnometrading.SecurityMaster;
import group.gnometrading.collector.MarketDataCollector;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.DefaultInboundOrchestrator;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.resources.Properties;
import group.gnometrading.shared.AWSModule;
import group.gnometrading.shared.PropertiesModule;
import group.gnometrading.shared.SecurityMasterModule;
import group.gnometrading.sm.Listing;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Clock;

public class DelegatingCollectorOrchestrator extends Orchestrator implements SecurityMasterModule, PropertiesModule, AWSModule {

    static {
        instanceClass = DelegatingCollectorOrchestrator.class;
    }

    @Provides
    public Clock provideClock() {
        return Clock.systemUTC();
    }

    @Provides
    public EpochNanoClock provideEpochNanoClock() {
        return new SystemEpochNanoClock();
    }

    @Provides
    @Singleton
    public Logger provideLogger(EpochNanoClock epochClock) {
        return new ConsoleLogger(epochClock);
    }

    @Provides
    @Named("LISTING_ID")
    public Integer provideListingId(Properties properties) {
        return properties.getIntProperty("listing");
    }

    @Provides
    public Listing provideListing(SecurityMaster securityMaster, @Named("LISTING_ID") Integer listingId) {
        return securityMaster.getListing(listingId);
    }

    @Provides
    @Named("OUTPUT_BUCKET")
    public String provideOutputBucket(Properties properties) {
        return properties.getStringProperty("output.bucket");
    }

    @Provides
    public MarketDataCollector provideMarketDataCollector(
            Logger logger,
            Clock clock,
            S3Client s3Client,
            Listing listing,
            @Named("OUTPUT_BUCKET") String outputBucket
    ) {
        return new MarketDataCollector(logger, clock, s3Client, listing, outputBucket);
    }

    @Override
    public void configure() {
        final Logger logger = getInstance(Logger.class);
        final Listing listing = getInstance(Listing.class);

        final Class<? extends DefaultInboundOrchestrator<?>> orchestratorClass = DefaultInboundOrchestrator.findInboundOrchestrator(listing);
        final DefaultInboundOrchestrator<?> orchestrator = createChildOrchestrator(orchestratorClass);
        final MarketDataCollector marketDataCollector = getInstance(MarketDataCollector.class);
        orchestrator.configureGatewayForListing(marketDataCollector);

        logger.logf(LogMessage.DEBUG, "Started listing %s on exchange %s with schema %s on class %s",
                listing.security().symbol(),
                listing.exchange().exchangeName(),
                listing.exchange().schemaType(),
                orchestratorClass.getSimpleName()
        );
    }
}
