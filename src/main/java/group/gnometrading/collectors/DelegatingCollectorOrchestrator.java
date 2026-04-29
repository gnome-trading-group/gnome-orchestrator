package group.gnometrading.collectors;

import group.gnometrading.SecurityMaster;
import group.gnometrading.collector.MarketDataCollector;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.DefaultInboundOrchestrator;
import group.gnometrading.health.HealthCheckServer;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.resources.Properties;
import group.gnometrading.sequencer.SchemaEventAdapter;
import group.gnometrading.shared.AwsModule;
import group.gnometrading.shared.SecurityMasterModule;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.services.s3.S3Client;

public class DelegatingCollectorOrchestrator extends Orchestrator {

    static {
        instanceClass = DelegatingCollectorOrchestrator.class;
    }

    @Provides
    public final Clock provideClock() {
        return Clock.systemUTC();
    }

    @Provides
    public final EpochNanoClock provideEpochNanoClock() {
        return new SystemEpochNanoClock();
    }

    @Provides
    @Singleton
    public final Logger provideLogger(EpochNanoClock epochClock) {
        return new ConsoleLogger(epochClock);
    }

    @Provides
    @Named("LISTING_ID")
    public final Integer provideListingId(Properties properties) {
        return properties.getIntProperty("listing");
    }

    @Provides
    public final Listing provideListing(SecurityMaster securityMaster, @Named("LISTING_ID") Integer listingId) {
        return securityMaster.getListing(listingId);
    }

    @Provides
    @Named("OUTPUT_BUCKET")
    public final String provideOutputBucket(Properties properties) {
        return properties.getStringProperty("output.bucket");
    }

    @Provides
    public final MarketDataCollector provideMarketDataCollector(
            Logger logger,
            Clock clock,
            S3Client s3Client,
            Listing listing,
            @Named("OUTPUT_BUCKET") String outputBucket) {
        return new MarketDataCollector(logger, clock, s3Client, listing, outputBucket);
    }

    @Override
    public final void configure() {
        install(new SecurityMasterModule(), new AwsModule());
        final Logger logger = getInstance(Logger.class);
        final Listing listing = getInstance(Listing.class);

        final Class<? extends DefaultInboundOrchestrator<?>> orchestratorClass =
                DefaultInboundOrchestrator.findInboundOrchestrator(listing);
        final DefaultInboundOrchestrator<?> orchestrator = createChildOrchestrator(orchestratorClass);
        final MarketDataCollector marketDataCollector = getInstance(MarketDataCollector.class);
        orchestrator.configureGatewayForListing(new SchemaEventAdapter(marketDataCollector));

        final long maxStaleNanos = TimeUnit.SECONDS.toNanos(90);
        try {
            new HealthCheckServer(8080, () -> {
                        long lastEvent = marketDataCollector.lastEventNanos;
                        return lastEvent == 0L || (System.nanoTime() - lastEvent) < maxStaleNanos;
                    })
                    .start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        logger.logf(
                LogMessage.DEBUG,
                "Started listing %s on exchange %s with schema %s on class %s",
                listing.security().symbol(),
                listing.exchange().exchangeName(),
                listing.exchange().schemaType(),
                orchestratorClass.getSimpleName());
    }
}
