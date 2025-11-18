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
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.shared.AWSModule;
import group.gnometrading.shared.PropertiesModule;
import group.gnometrading.shared.SecurityMasterModule;
import group.gnometrading.sm.Listing;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

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
    @Singleton
    @Named("LISTINGS")
    public List<Listing> provideListings(Properties properties, SecurityMaster securityMaster) {
        List<Listing> output = new ArrayList<>();
        String listingIds = properties.getStringProperty("listing.ids");
        for (String listingId : listingIds.split(",")) {
            output.add(securityMaster.getListing(Integer.parseInt(listingId)));
        }
        return output;
    }

    @Provides
    @Named("OUTPUT_BUCKET")
    public String provideOutputBucket(Properties properties) {
        return properties.getStringProperty("output.bucket");
    }

    private MarketDataCollector createMarketDataCollector(Listing listing, SchemaType schemaType) {
        return new MarketDataCollector(
                getInstance(Logger.class),
                getInstance(Clock.class),
                getInstance(S3Client.class),
                listing,
                getInstance(String.class, "OUTPUT_BUCKET"),
                schemaType
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public void configure() {
        final Logger logger = getInstance(Logger.class);
        final List<Listing> listings = this.getInstance(List.class, "LISTINGS");
        final SecurityMaster securityMaster = this.getInstance(SecurityMaster.class);

        for (Listing listing : listings) {
            final Class<? extends DefaultInboundOrchestrator<?>> orchestratorClass = DefaultInboundOrchestrator.findInboundOrchestrator(listing, securityMaster);
            final DefaultInboundOrchestrator<?> orchestrator = createChildOrchestrator(orchestratorClass);
            final MarketDataCollector marketDataCollector = createMarketDataCollector(listing, orchestrator.defaultSchemaType());
            orchestrator.configureGatewayForListing(listing, marketDataCollector);

            logger.logf(LogMessage.DEBUG, "Started listing %s on exchange %s with schema %s on class %s",
                    listing.exchangeSecuritySymbol(),
                    listing.exchangeId(),
                    orchestrator.defaultSchemaType(),
                    orchestratorClass.getSimpleName()
            );
        }
        logger.logf(LogMessage.DEBUG, "Finished configuring collectors");
    }
}
