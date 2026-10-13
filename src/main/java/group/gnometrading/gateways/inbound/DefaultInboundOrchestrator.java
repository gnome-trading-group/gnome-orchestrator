package group.gnometrading.gateways.inbound;

import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.GatewayErrorHandler;
import group.gnometrading.gateways.GatewayRunners;
import group.gnometrading.logging.Logger;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.shared.RiskModule;
import group.gnometrading.sm.Listing;
import group.gnometrading.trading.AgentRuntimeInstaller;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;

public abstract class DefaultInboundOrchestrator<T extends Schema> extends Orchestrator {

    @Override
    public final void configure() {
        install(new RiskModule());
    }

    public static Class<? extends DefaultInboundOrchestrator<?>> findInboundOrchestrator(final Listing listing) {
        switch (listing.exchange().exchangeCode()) {
            case "HYPERLIQUID" -> {
                return HyperliquidInboundOrchestrator.class;
            }
            case "LIGHTER" -> {
                return LighterInboundOrchestrator.class;
            }
            case "BINANCE" -> {
                return BinanceInboundOrchestrator.class;
            }
            case "POLYMARKET_INTL" -> {
                return PolymarketIntlInboundOrchestrator.class;
            }
            case "POLYMARKET_US" -> {
                return PolymarketUsInboundOrchestrator.class;
            }
            case "KALSHI" -> {
                return KalshiInboundOrchestrator.class;
            }
            default -> throw new IllegalArgumentException(
                    "Unmapped exchange code: " + listing.exchange().exchangeCode() + " ("
                            + listing.exchange().exchangeName() + ")");
        }
    }

    @Provides
    public final EpochClock provideEpochClock() {
        return SystemEpochClock.INSTANCE;
    }

    @Provides
    public final EpochNanoClock provideEpochNanoClock() {
        return new SystemEpochNanoClock();
    }

    @Provides
    @Singleton
    public final GlobalSequence provideGlobalSequence() {
        return new GlobalSequence();
    }

    @Provides
    @Singleton
    public abstract SequencedRingBuffer<T> provideSequencedRingBuffer();

    @Provides
    @Singleton
    public abstract InboundSocketReader<T> provideSocketReader();

    @Provides
    public abstract GatewayConfig provideGatewayConfig();

    @Provides
    @Singleton
    public abstract InboundSocketWriter provideSocketWriter();

    @Provides
    @Singleton
    public final InboundGateway provideInboundGateway() {
        // A reader on its own isolated core spins rather than sleeping in a blocking read: waking cost ~130us per
        // message on c7i against single-digit microseconds spinning. Collectors and standard sessions keep blocking.
        GatewayConfig config = getInstance(GatewayConfig.class)
                .withSpinReads(AgentRuntimeInstaller.pinsHotAgents(getInstance(Properties.class)));
        return new InboundGateway(
                getInstance(Logger.class),
                config,
                getInstance(InboundSocketReader.class),
                getInstance(EpochClock.class));
    }

    @Provides
    @Singleton
    public final ErrorHandler provideInboundErrorHandler() {
        final InboundGateway gateway = getInstance(InboundGateway.class);
        return new GatewayErrorHandler(
                getInstance(Logger.class), "market inbound gateway", gateway::forceReconnect, System::exit);
    }

    @SuppressWarnings("unchecked")
    public final SequencedRingBuffer<T> getSequencedRingBuffer() {
        return getInstance(SequencedRingBuffer.class);
    }

    @SuppressWarnings("unchecked")
    public final void setRawDataSink(RawDataSink sink) {
        getInstance(InboundSocketReader.class).setRawDataSink(sink);
    }

    @SuppressWarnings("unchecked")
    public final GatewayRunners startGatewayAgents() {
        ErrorHandler errorHandler = getInstance(ErrorHandler.class);
        InboundSocketReader<?> reader = getInstance(InboundSocketReader.class);
        return GatewayRunners.marketData(
                        reader.pauseControl,
                        new GnomeAgentRunner(reader, errorHandler),
                        new GnomeAgentRunner(getInstance(InboundSocketWriter.class), errorHandler),
                        new GnomeAgentRunner(getInstance(InboundGateway.class), errorHandler))
                .start();
    }
}
