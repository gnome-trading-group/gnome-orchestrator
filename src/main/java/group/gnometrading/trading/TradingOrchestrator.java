package group.gnometrading.trading;

import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.DefaultInboundOrchestrator;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.oms.OmsAgent;
import group.gnometrading.oms.OrderManagementSystem;
import group.gnometrading.oms.pnl.PnlReportingAgent;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.PositionView;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.RiskSyncAgent;
import group.gnometrading.oms.state.RingBufferOrderStateManager;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.shared.RiskModule;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
import group.gnometrading.simulation.fee.StaticFeeModel;
import group.gnometrading.simulation.latency.StaticLatency;
import group.gnometrading.simulation.queues.OptimisticQueueModel;
import group.gnometrading.simulation.queues.ProbabilisticQueueModel;
import group.gnometrading.simulation.queues.QueueModel;
import group.gnometrading.simulation.queues.RiskAverseQueueModel;
import group.gnometrading.sm.Listing;
import group.gnometrading.strategies.PythonStrategyAgent;
import group.gnometrading.strategies.PythonStrategyAgent.PythonStrategyCallback;
import group.gnometrading.strategies.StrategyAgent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;

/**
 * Concrete orchestrator for all live and paper trading sessions.
 *
 * <p>Wires N inbound market data gateways, the OMS, a strategy, and N outbound gateways together
 * using ring-buffer-based inter-thread communication. The outbound gateway and strategy are
 * selected via properties:
 *
 * <ul>
 *   <li>{@code mode} — {@code paper} uses {@link MbpSimulatedExchange}; {@code live} connects to
 *       a real exchange (not yet implemented)
 *   <li>{@code strategy.type} — {@code python} bridges to a JPype callback set via
 *       {@link PythonStrategyAgent#setCallback}; {@code java} reflectively instantiates
 *       {@code strategy.class}
 *   <li>{@code strategy.id} — registry strategy ID used for PnL and risk scoping
 * </ul>
 *
 * <p>Single-listing sessions wire buffers directly — no mux/demux agents are created. Multi-listing
 * sessions insert a {@link MarketDataMultiplexer} on the inbound side and an {@link ExchangeRouter}
 * on the outbound side so the strategy and OMS always see single buffers regardless of the number
 * of exchanges.
 *
 * <p>Configure via the {@code listings} property (comma-separated listing IDs).
 */
public class TradingOrchestrator extends Orchestrator {

    static {
        instanceClass = TradingOrchestrator.class;
    }

    private static final int OUTBOUND_BUFFER_SIZE = 64;
    private static final Duration DEFAULT_PNL_FLUSH_INTERVAL = Duration.ofSeconds(30);

    @Provides
    @Singleton
    public final EpochNanoClock provideEpochNanoClock() {
        return new SystemEpochNanoClock();
    }

    @Provides
    @Singleton
    public final Logger provideLogger(EpochNanoClock clock) {
        return new ConsoleLogger(clock);
    }

    @Override
    public final void configure() {
        install(new RiskModule());

        Logger logger = getInstance(Logger.class);
        SecurityMaster securityMaster = getInstance(SecurityMaster.class);
        Properties properties = getInstance(Properties.class);
        RiskEngine riskEngine = getInstance(RiskEngine.class);

        int strategyId = properties.getIntProperty("strategy.id");
        List<Listing> listings = resolveListings(properties, securityMaster);

        List<DefaultInboundOrchestrator<?>> inbounds = new ArrayList<>(listings.size());
        List<SequencedRingBuffer<?>> perListingMdBuffers = new ArrayList<>(listings.size());
        for (Listing listing : listings) {
            DefaultInboundOrchestrator<?> inbound = createChildOrchestrator(
                    DefaultInboundOrchestrator.findInboundOrchestrator(listing), Map.of(Listing.class, listing));
            inbounds.add(inbound);
            perListingMdBuffers.add(inbound.getSequencedRingBuffer());
        }

        GlobalSequence globalSequence = new GlobalSequence();
        SharedPositionBuffer sharedBuffer = new SharedPositionBuffer(64);
        DefaultPositionTracker positionTracker = new DefaultPositionTracker(sharedBuffer);
        OrderManagementSystem oms = new OrderManagementSystem(
                logger, new RingBufferOrderStateManager(), positionTracker, riskEngine, securityMaster);
        for (Listing listing : listings) {
            positionTracker.registerSlot(strategyId, listing.listingId());
        }
        PositionView positionView = positionTracker.createPositionView(strategyId);

        SequencedRingBuffer<Intent> intentBuffer = new SequencedRingBuffer<>(Intent::new, globalSequence);
        SequencedRingBuffer<OrderExecutionReport> stratExecReportBuffer =
                new SequencedRingBuffer<>(OrderExecutionReport::new, globalSequence, OUTBOUND_BUFFER_SIZE);

        SequencedRingBuffer<?> strategyMdBuffer;
        MarketDataMultiplexer muxAgent = null;
        if (listings.size() == 1) {
            strategyMdBuffer = perListingMdBuffers.get(0);
        } else {
            strategyMdBuffer = new SequencedRingBuffer<>(Intent::new, globalSequence);
            muxAgent = new MarketDataMultiplexer(perListingMdBuffers, strategyMdBuffer);
        }

        SequencedRingBuffer<Intent> orderOutboundBuffer =
                new SequencedRingBuffer<>(Intent::new, globalSequence, OUTBOUND_BUFFER_SIZE);
        SequencedRingBuffer<OrderExecutionReport> omsExecReportBuffer =
                new SequencedRingBuffer<>(OrderExecutionReport::new, globalSequence, OUTBOUND_BUFFER_SIZE);

        List<GnomeAgent> outboundAgents = new ArrayList<>(listings.size());
        ExchangeRouter routerAgent = null;

        if (listings.size() == 1) {
            outboundAgents.add(createOutboundGateway(
                    listings.get(0), perListingMdBuffers.get(0), orderOutboundBuffer, omsExecReportBuffer));
        } else {
            Map<Integer, SequencedRingBuffer<?>> perExchangeOutBufs = new HashMap<>();
            List<SequencedRingBuffer<OrderExecutionReport>> perExchangeExecBufs = new ArrayList<>(listings.size());

            for (int i = 0; i < listings.size(); i++) {
                Listing listing = listings.get(i);
                int exchangeId = listing.exchange().exchangeId();

                SequencedRingBuffer<Intent> perExchangeOutBuf =
                        new SequencedRingBuffer<>(Intent::new, globalSequence, OUTBOUND_BUFFER_SIZE);
                SequencedRingBuffer<OrderExecutionReport> perExchangeExecBuf =
                        new SequencedRingBuffer<>(OrderExecutionReport::new, globalSequence, OUTBOUND_BUFFER_SIZE);

                perExchangeOutBufs.put(exchangeId, perExchangeOutBuf);
                perExchangeExecBufs.add(perExchangeExecBuf);

                outboundAgents.add(createOutboundGateway(
                        listing, perListingMdBuffers.get(i), perExchangeOutBuf, perExchangeExecBuf));
            }

            routerAgent = new ExchangeRouter(
                    orderOutboundBuffer, perExchangeOutBufs, perExchangeExecBufs, omsExecReportBuffer);
        }

        OmsAgent omsAgent =
                new OmsAgent(oms, intentBuffer, omsExecReportBuffer, orderOutboundBuffer, stratExecReportBuffer);
        StrategyAgent strategy =
                createStrategyAgent(strategyMdBuffer, stratExecReportBuffer, intentBuffer, positionView);

        RegistryConnection registryConnection = getInstance(RegistryConnection.class);
        EpochClock epochClock = SystemEpochClock.INSTANCE;
        int pnlFlushSeconds = properties.getIntProperty("pnl.flush.interval.seconds");
        PnlReportingAgent pnlReportingAgent = new PnlReportingAgent(
                positionTracker, registryConnection, epochClock, Duration.ofSeconds(pnlFlushSeconds), listings.size());

        RiskSyncAgent riskSyncAgent = getInstance(RiskSyncAgent.class);

        ErrorHandler errorHandler = error -> {
            logger.logf(LogMessage.FATAL_ERROR_EXITING, "Agent error: %s", error);
            System.exit(1);
        };

        for (DefaultInboundOrchestrator<?> inbound : inbounds) {
            inbound.startGatewayAgents();
        }
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(omsAgent, errorHandler));
        for (GnomeAgent outbound : outboundAgents) {
            GnomeAgentRunner.startOnThread(new GnomeAgentRunner(outbound, errorHandler));
        }
        if (muxAgent != null) {
            GnomeAgentRunner.startOnThread(new GnomeAgentRunner(muxAgent, errorHandler));
        }
        if (routerAgent != null) {
            GnomeAgentRunner.startOnThread(new GnomeAgentRunner(routerAgent, errorHandler));
        }
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(strategy, errorHandler));
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(pnlReportingAgent, errorHandler));
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(riskSyncAgent, errorHandler));
    }

    private GnomeAgent createOutboundGateway(
            Listing listing,
            SequencedRingBuffer<?> marketDataBuffer,
            SequencedRingBuffer<?> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer) {
        Properties properties = getInstance(Properties.class);
        String mode = properties.getStringProperty("mode");

        if ("paper".equals(mode)) {
            StaticFeeModel feeModel = new StaticFeeModel(
                    Double.parseDouble(properties.getStringProperty("simulation.taker.fee")),
                    Double.parseDouble(properties.getStringProperty("simulation.maker.fee")));
            StaticLatency networkLatency =
                    new StaticLatency(Long.parseLong(properties.getStringProperty("simulation.network.latency.nanos")));
            StaticLatency orderLatency =
                    new StaticLatency(Long.parseLong(properties.getStringProperty("simulation.order.latency.nanos")));
            QueueModel queueModel = resolveQueueModel(properties);
            MbpSimulatedExchange exchange =
                    new MbpSimulatedExchange(feeModel, networkLatency, orderLatency, queueModel);
            return new PaperTradingOutboundGateway(exchange, marketDataBuffer, orderOutboundBuffer, execReportBuffer);
        }

        throw new UnsupportedOperationException("Live outbound gateway not yet implemented. mode=" + mode);
    }

    private StrategyAgent createStrategyAgent(
            SequencedRingBuffer<?> mdBuf,
            SequencedRingBuffer<OrderExecutionReport> erBuf,
            SequencedRingBuffer<Intent> intentBuf,
            PositionView positionView) {
        Properties properties = getInstance(Properties.class);
        String strategyType = properties.getStringProperty("strategy.type");

        if ("python".equals(strategyType)) {
            PythonStrategyCallback callback = PythonStrategyAgent.getCallback();
            if (callback == null) {
                throw new IllegalStateException(
                        "Python strategy callback not set. Call PythonStrategyAgent.setCallback() before Orchestrator.main().");
            }
            return PythonStrategyAgent.createWithBuffers(mdBuf, erBuf, intentBuf, positionView, callback);
        }

        String className = properties.getStringProperty("strategy.class");
        try {
            Class<?> clazz = Class.forName(className);
            return (StrategyAgent) clazz.getDeclaredConstructor(
                            SequencedRingBuffer.class,
                            SequencedRingBuffer.class,
                            SequencedRingBuffer.class,
                            PositionView.class)
                    .newInstance(mdBuf, erBuf, intentBuf, positionView);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate strategy class: " + className, e);
        }
    }

    private List<Listing> resolveListings(Properties properties, SecurityMaster securityMaster) {
        return Arrays.stream(properties.getStringProperty("listings").split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .map(securityMaster::getListing)
                .toList();
    }

    private QueueModel resolveQueueModel(final Properties properties) {
        String model = properties.getStringProperty("simulation.queue.model");
        return switch (model) {
            case "optimistic" -> new OptimisticQueueModel();
            case "probabilistic" -> new ProbabilisticQueueModel(
                    Double.parseDouble(properties.getStringProperty("simulation.queue.cancel.ahead.probability")));
            case "risk_averse" -> new RiskAverseQueueModel();
            default -> throw new IllegalArgumentException("Unknown queue model: " + model);
        };
    }
}
