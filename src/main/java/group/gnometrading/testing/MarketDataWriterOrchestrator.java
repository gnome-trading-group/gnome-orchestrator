package group.gnometrading.testing;

import group.gnometrading.SecurityMaster;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.DefaultInboundOrchestrator;
import group.gnometrading.gateways.inbound.MarketInboundGatewayConfig;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.sockets.factory.GnomeSocketFactory;
import group.gnometrading.networking.sockets.factory.NativeSocketFactory;
import group.gnometrading.resources.Properties;
import group.gnometrading.shared.PropertiesModule;
import group.gnometrading.shared.SecurityMasterModule;
import group.gnometrading.sm.Listing;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochNanoClock;

import java.net.URI;
import java.net.URISyntaxException;

public class MarketDataWriterOrchestrator extends Orchestrator implements SecurityMasterModule, PropertiesModule {

    static {
        instanceClass = MarketDataWriterOrchestrator.class;
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
    @Named("HOST")
    public String provideHost(Properties properties) {
        return properties.getStringProperty("host");
    }

    @Provides
    @Named("PORT")
    public Integer providePort(Properties properties) {
        return properties.getIntProperty("port");
    }

    @Provides
    @Named("OUTPUT_PATH")
    public String provideFilePath(Properties properties) {
        return properties.getStringProperty("output");
    }

    @Provides
    public URI provideURI(@Named("HOST") String host, @Named("PORT") Integer port) throws URISyntaxException {
        return new URI(null, host + ":" + port, null, null, null);
    }

    @Provides
    public GnomeSocketFactory provideSocketFactory() {
        return new NativeSocketFactory();
    }

    @Provides
    public MarketInboundGatewayConfig provideMarketInboundGatewayConfig() {
        return new MarketInboundGatewayConfig.Builder()
                .withMaxReconnectAttempts(1)
                .build();
    }

    @Provides
    @Singleton
    public MarketDataWriter provideMarketDataWriter(Logger logger, @Named("OUTPUT_PATH") String outputPath) {
        return new MarketDataWriter(logger, outputPath);
    }

    @Provides
    public ErrorHandler provideErrorHandler() {
        Logger logger = getInstance(Logger.class);
        return (error) -> {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Exiting due to expected socket closure: %s", error);
            System.exit(0);
        };
    }

    @Override
    public void configure() {
        final Logger logger = getInstance(Logger.class);
        final MarketDataWriter writer = getInstance(MarketDataWriter.class);
        final Listing listing = getInstance(Listing.class);
        final SecurityMaster securityMaster = getInstance(SecurityMaster.class);

        final Class<? extends DefaultInboundOrchestrator<?>> orchestratorClass = DefaultInboundOrchestrator.findInboundOrchestrator(listing, securityMaster);
        final DefaultInboundOrchestrator<?> orchestrator = createChildOrchestrator(orchestratorClass);
        orchestrator.configureGatewayForListing(writer);
    }

}
