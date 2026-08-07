package group.gnometrading.collectors;

import group.gnometrading.SecurityMaster;
import group.gnometrading.collector.MarketDataCollector;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.DefaultInboundOrchestrator;
import group.gnometrading.gateways.inbound.SocketReader;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.services.s3.S3Client;

/** Collects one or more listings using an independent gateway and archive pipeline per listing. */
public class DelegatingCollectorOrchestrator extends Orchestrator {

    static {
        instanceClass = DelegatingCollectorOrchestrator.class;
    }

    private record ListingPipeline(
            Listing listing,
            DefaultInboundOrchestrator<?> inbound,
            MarketDataCollector normalizedCollector,
            RawMarketDataCollector rawCollector) {}

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

    @Provides
    @Named("VENUE_RAW_BUCKET")
    public final String provideVenueRawBucket(Properties properties) {
        return properties.getStringProperty("venue.raw.bucket");
    }

    @Override
    public final void configure() {
        install(new SecurityMasterModule(), new AwsModule());
        final Logger logger = getInstance(Logger.class);
        final Clock clock = getInstance(Clock.class);
        final Properties properties = getInstance(Properties.class);
        final SecurityMaster securityMaster = getInstance(SecurityMaster.class);
        final S3Client s3Client = getInstance(S3Client.class);
        final String outputBucket = getInstance(String.class, "OUTPUT_BUCKET");
        final String venueRawBucket = getInstance(String.class, "VENUE_RAW_BUCKET");
        final List<Listing> listings = resolveListings(properties, securityMaster);

        final CollectionContext collectionContext = new CollectionMetadataPublisher(
                        s3Client, clock, outputBucket, venueRawBucket)
                .publish(listings, securityMaster);

        final List<ListingPipeline> pipelines = new ArrayList<>(listings.size());
        for (Listing listing : listings) {
            final Map<Class<?>, Object> overrides = new HashMap<>();
            overrides.put(Listing.class, listing);
            final Class<? extends DefaultInboundOrchestrator<?>> inboundClass =
                    DefaultInboundOrchestrator.findInboundOrchestrator(listing);
            final DefaultInboundOrchestrator<?> inbound = createChildOrchestrator(inboundClass, overrides);
            final MarketDataCollector normalizedCollector =
                    new MarketDataCollector(logger, clock, s3Client, listing, outputBucket);
            final RawMarketDataCollector rawCollector =
                    new RawMarketDataCollector(logger, s3Client, listing, venueRawBucket, collectionContext);
            final SocketReader<?> socketReader = inbound.getSocketReader();
            socketReader.setRawMessageHandler(rawCollector);
            pipelines.add(new ListingPipeline(listing, inbound, normalizedCollector, rawCollector));
        }

        final LongSupplier[] normalizedSources = new LongSupplier[pipelines.size()];
        final LongSupplier[] rawSources = new LongSupplier[pipelines.size()];
        final BooleanSupplier[] rawHealthSources = new BooleanSupplier[pipelines.size()];
        for (int i = 0; i < pipelines.size(); i++) {
            final ListingPipeline pipeline = pipelines.get(i);
            normalizedSources[i] = () -> pipeline.normalizedCollector().lastEventNanos;
            rawSources[i] = pipeline.rawCollector()::lastMessageNanos;
            rawHealthSources[i] = pipeline.rawCollector()::isHealthy;
        }

        try {
            new HealthCheckServer(
                            8080,
                            new CollectorHealth(
                                    clock,
                                    normalizedSources,
                                    rawSources,
                                    rawHealthSources,
                                    Duration.ofSeconds(60),
                                    Duration.ofSeconds(30)))
                    .start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        for (ListingPipeline pipeline : pipelines) {
            pipeline.inbound().configureGatewayForListing(new SchemaEventAdapter(pipeline.normalizedCollector()));
            logger.logf(
                    LogMessage.DEBUG,
                    "Started listing %s (%d) on exchange %s with schema %s",
                    pipeline.listing().security().symbol(),
                    pipeline.listing().listingId(),
                    pipeline.listing().exchange().exchangeName(),
                    pipeline.listing().exchange().schemaType());
        }

        logger.logf(
                LogMessage.DEBUG,
                "Started collection %s for %d listing(s); contract metadata: %s",
                collectionContext.collectionId(),
                pipelines.size(),
                collectionContext.contractMetadataKey());
    }

    static List<Listing> resolveListings(final Properties properties, final SecurityMaster securityMaster) {
        final String configured = configuredListingIds(properties);
        final LinkedHashSet<Integer> listingIds = new LinkedHashSet<>();
        Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Integer::parseInt)
                .forEach(listingIds::add);
        if (listingIds.isEmpty()) {
            throw new IllegalArgumentException("At least one listing ID must be configured");
        }

        final List<Listing> listings = new ArrayList<>(listingIds.size());
        for (int listingId : listingIds) {
            final Listing listing = securityMaster.getListing(listingId);
            if (listing == null) {
                throw new IllegalArgumentException("Unknown listing ID: " + listingId);
            }
            listings.add(listing);
        }
        return List.copyOf(listings);
    }

    private static String configuredListingIds(final Properties properties) {
        try {
            return properties.getStringProperty("listings");
        } catch (IllegalArgumentException missingMultiListingProperty) {
            return properties.getStringProperty("listing");
        }
    }
}
