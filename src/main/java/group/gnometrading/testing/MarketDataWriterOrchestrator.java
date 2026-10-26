package group.gnometrading.testing;

import group.gnometrading.SecurityMaster;
import group.gnometrading.collectors.MarketDataCollectorAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.DefaultInboundOrchestrator;
import group.gnometrading.logging.AsyncLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.md.Stream;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.resources.Properties;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.StreamRecordEventAdapter;
import group.gnometrading.shared.AwsModule;
import group.gnometrading.shared.SecurityMasterModule;
import group.gnometrading.sm.Listing;
import group.gnometrading.trading.AgentRuntimeInstaller;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
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
    public final Listing provideListing(SecurityMaster securityMaster, @Named("LISTING_ID") Integer listingId) {
        return securityMaster.getListing(listingId);
    }

    @Provides
    public final Stream provideStream(SecurityMaster securityMaster, @Named("LISTING_ID") Integer listingId) {
        return securityMaster.streamForListing(listingId);
    }

    @Provides
    @Named("HOST")
    public final String provideHost(Properties properties) {
        return properties.getStringProperty("host");
    }

    @Provides
    @Named("PORT")
    public final Integer providePort(Properties properties) {
        return properties.getIntProperty("port");
    }

    @Provides
    @Named("OUTPUT_PATH")
    public final String provideFilePath(Properties properties) {
        return properties.getStringProperty("output");
    }

    @Provides
    public final URI provideUri(@Named("HOST") String host, @Named("PORT") Integer port) throws URISyntaxException {
        return new URI(null, host + ":" + port, null, null, null);
    }

    @Provides
    public final GnomeSocketFactory provideSocketFactory() {
        return new NativeSocketFactory();
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
            logger.logf(LogMessage.UNKNOWN_ERROR, "Exiting due to expected socket closure: %s", error);
            System.exit(0);
        };
    }

    @Override
    public final void configure() {
        install(new SecurityMasterModule(), new AwsModule());
        AgentRuntimeInstaller.installStandard(getInstance(Logger.class));
        final MarketDataWriter writer = getInstance(MarketDataWriter.class);
        final Listing listing = getInstance(Listing.class);

        final Class<? extends DefaultInboundOrchestrator<?>> orchestratorClass =
                DefaultInboundOrchestrator.findInboundOrchestrator(listing);
        final DefaultInboundOrchestrator<?> orchestrator = createChildOrchestrator(orchestratorClass);
        final SequencedPoller poller =
                orchestrator.getSequencedRingBuffer().createPoller(new StreamRecordEventAdapter(writer));
        GnomeAgentRunner.startOnThread(
                new GnomeAgentRunner(new MarketDataCollectorAgent(List.of(poller)), getInstance(ErrorHandler.class)));
        orchestrator.startGatewayAgents();
    }
}
