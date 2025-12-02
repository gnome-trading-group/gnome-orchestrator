package group.gnometrading.collectors;

import group.gnometrading.SecurityMaster;
import group.gnometrading.collector.BulkMarketDataCollector;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.DefaultInboundOrchestrator;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.SchemaType;
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

    private BulkMarketDataCollector createBulkMarketDataCollector(SchemaType schemaType) {
        return new BulkMarketDataCollector(
                getInstance(Logger.class),
                getInstance(Clock.class),
                getInstance(S3Client.class),
                getInstance(Listing.class),
                getInstance(String.class, "OUTPUT_BUCKET"),
                schemaType
        );
    }

    @Override
    public void configure() {
        final Logger logger = getInstance(Logger.class);
        final SecurityMaster securityMaster = this.getInstance(SecurityMaster.class);
        final Listing listing = getInstance(Listing.class);

        final Class<? extends DefaultInboundOrchestrator<?>> orchestratorClass = DefaultInboundOrchestrator.findInboundOrchestrator(listing, securityMaster);
        final DefaultInboundOrchestrator<?> orchestrator = createChildOrchestrator(orchestratorClass);
        final BulkMarketDataCollector marketDataCollector = createBulkMarketDataCollector(orchestrator.getDefaultSchemaType());
        orchestrator.configureGatewayForListing(marketDataCollector);

        logger.logf(LogMessage.DEBUG, "Started listing %s on exchange %s with schema %s on class %s",
                listing.exchangeSecuritySymbol(),
                listing.exchangeId(),
                orchestrator.getDefaultSchemaType(),
                orchestratorClass.getSimpleName()
        );
    }
}
