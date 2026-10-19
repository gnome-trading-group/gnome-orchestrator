package group.gnometrading.collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.SecurityMaster;
import group.gnometrading.collector.CycleUploader;
import group.gnometrading.collector.MarketDataCollector;
import group.gnometrading.collector.RawDataCollector;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.DefaultInboundOrchestrator;
import group.gnometrading.health.HealthCheckServer;
import group.gnometrading.logging.AsyncLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.resources.Properties;
import group.gnometrading.sequencer.SchemaEventAdapter;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.shared.AwsModule;
import group.gnometrading.shared.SecurityMasterModule;
import group.gnometrading.sm.Listing;
import group.gnometrading.trading.AgentRuntimeInstaller;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.services.s3.S3Client;

public class DelegatingCollectorOrchestrator extends Orchestrator {

    static {
        instanceClass = DelegatingCollectorOrchestrator.class;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        return new AsyncLogger(epochClock);
    }

    @Provides
    @Named("OUTPUT_BUCKET")
    public final String provideOutputBucket(Properties properties) {
        return properties.getStringProperty("output.bucket");
    }

    @Provides
    @Named("RAW_CAPTURE_BUCKET")
    public final String provideRawCaptureBucket(Properties properties) {
        return properties.getStringProperty("raw.capture.bucket");
    }

    @Provides
    @Named("LISTING_IDS")
    public final int[] provideListingIds(Properties properties) {
        if (properties.hasProperty("listings")) {
            try {
                return MAPPER.readValue(properties.getStringProperty("listings"), int[].class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse listings property", e);
            }
        }
        return new int[] {properties.getIntProperty("listing")};
    }

    @Provides
    @Named("LATENCY_PROBE_INTERVAL_NANOS")
    public final Long provideLatencyProbeIntervalNanos(Properties properties) {
        if (properties.hasProperty("latency.probe.interval.micros")) {
            return TimeUnit.MICROSECONDS.toNanos(properties.getIntProperty("latency.probe.interval.micros"));
        }
        return TimeUnit.MILLISECONDS.toNanos(1);
    }

    @Override
    public final void configure() {
        install(new SecurityMasterModule(), new AwsModule());
        final Logger logger = getInstance(Logger.class);
        // Before any agent thread starts. Collectors run on fractional Fargate CPUs, where anything spinning burns
        // the quota and the throttled task delays every socket read, inflating receive timestamps.
        AgentRuntimeInstaller.installStandard(logger);
        SchedulingLatencyProbe.start(logger, getInstance(Long.class, "LATENCY_PROBE_INTERVAL_NANOS"));

        final SecurityMaster securityMaster = getInstance(SecurityMaster.class);
        final String outputBucket = getInstance(String.class, "OUTPUT_BUCKET");
        final String rawCaptureBucket = getInstance(String.class, "RAW_CAPTURE_BUCKET");
        final int[] listingIds = getInstance(int[].class, "LISTING_IDS");
        final Clock clock = getInstance(Clock.class);
        final CycleUploader uploader = new CycleUploader(logger, getInstance(S3Client.class));

        final MarketDataCollector[] collectors = new MarketDataCollector[listingIds.length];
        final List<SequencedPoller> pollers = new ArrayList<>(listingIds.length);
        final List<DefaultInboundOrchestrator<?>> inbounds = new ArrayList<>(listingIds.length);
        for (int i = 0; i < listingIds.length; i++) {
            final Listing listing = securityMaster.getListing(listingIds[i]);
            final Class<? extends DefaultInboundOrchestrator<?>> orchestratorClass =
                    DefaultInboundOrchestrator.findInboundOrchestrator(listing);
            final DefaultInboundOrchestrator<?> orchestrator =
                    createChildOrchestrator(orchestratorClass, Map.of(Listing.class, listing));

            final RawDataCollector rawCollector =
                    new RawDataCollector(logger, clock, uploader, listing, rawCaptureBucket);
            orchestrator.setRawDataSink(rawCollector::capture);

            final MarketDataCollector collector =
                    new MarketDataCollector(logger, clock, uploader, listing, outputBucket);
            collectors[i] = collector;
            // Created before the gateway starts: a poller only sees what is published after it exists.
            pollers.add(orchestrator.getSequencedRingBuffer().createPoller(new SchemaEventAdapter(collector)));
            inbounds.add(orchestrator);

            logger.logf(
                    LogMessage.DEBUG,
                    "Configured listing %s on exchange %s with schema %s on class %s",
                    listing.security().symbol(),
                    listing.exchange().exchangeName(),
                    listing.exchange().schemaType(),
                    orchestratorClass.getSimpleName());
        }

        GnomeAgentRunner.startOnThread(
                new GnomeAgentRunner(new MarketDataCollectorAgent(pollers), collectorErrorHandler(logger)));
        for (DefaultInboundOrchestrator<?> inbound : inbounds) {
            inbound.startGatewayAgents();
        }

        final long maxStaleNanos = TimeUnit.SECONDS.toNanos(90);
        try {
            new HealthCheckServer(8080, () -> isHealthy(collectors, clock, maxStaleNanos)).start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A failed write leaves the poller retrying the same event forever, so exit and let ECS replace the task
     * rather than stop recording silently.
     */
    private static ErrorHandler collectorErrorHandler(final Logger logger) {
        return error -> {
            logger.logf(LogMessage.FATAL_ERROR_EXITING, "Collector error: %s", error);
            System.exit(1);
        };
    }

    // lastEventNanos is wall-clock time, so it has to be compared with wall-clock time, not System.nanoTime().
    private static boolean isHealthy(
            final MarketDataCollector[] collectors, final Clock clock, final long maxStaleNanos) {
        final long[] lastEventNanos = new long[collectors.length];
        for (int i = 0; i < collectors.length; i++) {
            lastEventNanos[i] = collectors[i].lastEventNanos;
        }
        return isHealthy(lastEventNanos, TimeUnit.MILLISECONDS.toNanos(clock.millis()), maxStaleNanos);
    }

    /** Healthy while any listing is still receiving, or before any has received anything. */
    static boolean isHealthy(final long[] lastEventNanos, final long nowNanos, final long maxStaleNanos) {
        boolean anyEvent = false;
        for (long last : lastEventNanos) {
            if (last == 0L) {
                continue;
            }
            anyEvent = true;
            if (nowNanos - last < maxStaleNanos) {
                return true;
            }
        }
        return !anyEvent;
    }
}
