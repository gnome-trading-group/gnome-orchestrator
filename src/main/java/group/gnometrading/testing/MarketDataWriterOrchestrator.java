package group.gnometrading.testing;

import group.gnometrading.SecurityMaster;
import group.gnometrading.aeron.MarketDataIdle;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.feeds.FeedSources;
import group.gnometrading.feeds.FeedVenues;
import group.gnometrading.feeds.MarketDataCredentials;
import group.gnometrading.feeds.WebSocketFeedOpener;
import group.gnometrading.gateways.ProcessExit;
import group.gnometrading.gateways.feed.Feeds;
import group.gnometrading.logging.AsyncLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.md.Source;
import group.gnometrading.md.Stream;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sequencer.StreamRecordEventAdapter;
import group.gnometrading.shared.AwsModule;
import group.gnometrading.shared.SecretCache;
import group.gnometrading.shared.SecurityMasterModule;
import group.gnometrading.trading.AgentRuntimeInstaller;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochNanoClock;

public class MarketDataWriterOrchestrator extends Orchestrator {

    static {
        instanceClass = MarketDataWriterOrchestrator.class;
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
    @Named("LISTING_ID")
    public final Integer provideListingId(Properties properties) {
        return properties.getIntProperty("listing");
    }

    @Provides
    public final Stream provideStream(SecurityMaster securityMaster, @Named("LISTING_ID") Integer listingId) {
        return securityMaster.streamForListing(listingId);
    }

    @Provides
    @Named("OUTPUT_PATH")
    public final String provideFilePath(Properties properties) {
        return properties.getStringProperty("output");
    }

    @Provides
    @Singleton
    public final MarketDataWriter provideMarketDataWriter(Logger logger, @Named("OUTPUT_PATH") String outputPath) {
        return new MarketDataWriter(logger, outputPath);
    }

    @Provides
    public final ErrorHandler provideErrorHandler() {
        Logger logger = getInstance(Logger.class);
        return (error) -> {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Market data writer failed, exiting: %s", error);
            System.exit(0);
        };
    }

    @Override
    public final void configure() {
        install(new SecurityMasterModule(), new AwsModule());
        final Logger logger = getInstance(Logger.class);
        AgentRuntimeInstaller.installStandard(logger, MarketDataIdle.DEFAULT);
        final MarketDataWriter writer = getInstance(MarketDataWriter.class);
        final SecurityMaster securityMaster = getInstance(SecurityMaster.class);
        final Stream stream = getInstance(Stream.class);
        final Source source = securityMaster.getSource(stream.sourceId());

        final Feeds feeds = new Feeds(logger, null);
        final FeedSources sources = new FeedSources(
                feeds,
                new FeedVenues.Context(
                        logger,
                        getInstance(Properties.class),
                        getInstance(SecretCache.class),
                        MarketDataCredentials.SESSION,
                        getInstance(EpochNanoClock.class)),
                false,
                ProcessExit.SYSTEM,
                code -> WebSocketFeedOpener.RawCaptures.NONE,
                WebSocketFeedOpener.Clients.TLS);
        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(
                Mbp10Schema::new,
                new GlobalSequence(),
                SequencedRingBuffer.DEFAULT_SIZE,
                SequencedRingBuffer.Producers.TAKING_TURNS);
        final SequencedPoller poller = ring.createPoller(new StreamRecordEventAdapter(writer));
        final GnomeAgent drain = poller::poll;
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(drain, getInstance(ErrorHandler.class)));

        sources.ensure(source);
        try {
            feeds.addStream(stream.streamId(), source.code(), stream.nativeSubjectId(), ring, System.nanoTime());
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't start stream " + stream.streamId(), e);
        }
        // Rolls and quarantine retries; this tool runs for as long as it is left to.
        final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleWithFixedDelay(
                () -> {
                    try {
                        feeds.tick(System.nanoTime());
                    } catch (Exception e) {
                        logger.logf(LogMessage.UNKNOWN_ERROR, "Feed tick failed: %s", e);
                    }
                },
                1,
                1,
                TimeUnit.SECONDS);
    }
}
