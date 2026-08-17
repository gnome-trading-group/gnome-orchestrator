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
import java.util.Map;
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
    @Named("OUTPUT_BUCKET")
    public final String provideOutputBucket(Properties properties) {
        return properties.getStringProperty("output.bucket");
    }

    @Provides
    @Named("LISTING_IDS")
    public final int[] provideListingIds(Properties properties) {
        if (properties.hasProperty("listings")) {
            String[] parts = properties.getStringProperty("listings").split(",");
            int[] ids = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                ids[i] = Integer.parseInt(parts[i].trim());
            }
            return ids;
        }
        return new int[] {properties.getIntProperty("listing")};
    }

    @Override
    public final void configure() {
        install(new SecurityMasterModule(), new AwsModule());
        final Logger logger = getInstance(Logger.class);
        final SecurityMaster securityMaster = getInstance(SecurityMaster.class);
        final String outputBucket = getInstance(String.class, "OUTPUT_BUCKET");
        final int[] listingIds = getInstance(int[].class, "LISTING_IDS");

        final MarketDataCollector[] collectors = new MarketDataCollector[listingIds.length];
        for (int i = 0; i < listingIds.length; i++) {
            final Listing listing = securityMaster.getListing(listingIds[i]);
            final Class<? extends DefaultInboundOrchestrator<?>> orchestratorClass =
                    DefaultInboundOrchestrator.findInboundOrchestrator(listing);
            final DefaultInboundOrchestrator<?> orchestrator =
                    createChildOrchestrator(orchestratorClass, Map.of(Listing.class, listing));

            final MarketDataCollector collector = new MarketDataCollector(
                    logger, getInstance(Clock.class), getInstance(S3Client.class), listing, outputBucket);
            collectors[i] = collector;

            orchestrator.configureGatewayForListing(new SchemaEventAdapter(collector));

            logger.logf(
                    LogMessage.DEBUG,
                    "Started listing %s on exchange %s with schema %s on class %s",
                    listing.security().symbol(),
                    listing.exchange().exchangeName(),
                    listing.exchange().schemaType(),
                    orchestratorClass.getSimpleName());
        }

        final long maxStaleNanos = TimeUnit.SECONDS.toNanos(90);
        try {
            new HealthCheckServer(8080, () -> {
                        for (MarketDataCollector c : collectors) {
                            long last = c.lastEventNanos;
                            if (last != 0L && (System.nanoTime() - last) < maxStaleNanos) {
                                return true;
                            }
                        }
                        for (MarketDataCollector c : collectors) {
                            if (c.lastEventNanos != 0L) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
