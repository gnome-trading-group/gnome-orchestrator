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
import group.gnometrading.sequencer.JournalWriter;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.shared.AwsModule;
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
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.services.s3.S3Client;

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

    private static final Map<Class<?>, Function<String, Object>> CONVERTERS = Map.ofEntries(
            Map.entry(String.class, (Function<String, Object>) v -> v),
            Map.entry(int.class, Integer::parseInt),
            Map.entry(Integer.class, Integer::parseInt),
            Map.entry(long.class, Long::parseLong),
            Map.entry(Long.class, Long::parseLong),
            Map.entry(double.class, Double::parseDouble),
            Map.entry(Double.class, Double::parseDouble),
            Map.entry(float.class, Float::parseFloat),
            Map.entry(Float.class, Float::parseFloat),
            Map.entry(boolean.class, Boolean::parseBoolean),
            Map.entry(Boolean.class, Boolean::parseBoolean));

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
        install(new AwsModule());

        Logger logger = getInstance(Logger.class);
        SecurityMaster securityMaster = getInstance(SecurityMaster.class);
        Properties properties = getInstance(Properties.class);
        RiskEngine riskEngine = getInstance(RiskEngine.class);

        int strategyId = properties.getIntProperty("strategy.id");
        List<Listing> listings = resolveListings(properties, securityMaster);

        GlobalSequence globalSequence = new GlobalSequence();

        List<DefaultInboundOrchestrator<?>> inbounds = new ArrayList<>(listings.size());
        List<SequencedRingBuffer<?>> perListingMdBuffers = new ArrayList<>(listings.size());
        for (Listing listing : listings) {
            Map<Class<?>, Object> inboundOverrides = new HashMap<>();
            inboundOverrides.put(Listing.class, listing);
            if (listings.size() == 1) {
                inboundOverrides.put(GlobalSequence.class, globalSequence);
            }
            DefaultInboundOrchestrator<?> inbound = createChildOrchestrator(
                    DefaultInboundOrchestrator.findInboundOrchestrator(listing), inboundOverrides);
            inbounds.add(inbound);
            perListingMdBuffers.add(inbound.getSequencedRingBuffer());
        }
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
        StrategyAgent strategy = createStrategyAgent(
                strategyMdBuffer, stratExecReportBuffer, intentBuffer, positionView, securityMaster);

        RegistryConnection registryConnection = getInstance(RegistryConnection.class);
        EpochClock epochClock = SystemEpochClock.INSTANCE;

        String sessionId = properties.getStringProperty("session.id");

        int pnlFlushSeconds = properties.getIntProperty("pnl.flush.interval.seconds");
        PnlReportingAgent pnlReportingAgent = new PnlReportingAgent(
                positionTracker, registryConnection, epochClock, Duration.ofSeconds(pnlFlushSeconds), listings.size(), sessionId);

        RiskSyncAgent riskSyncAgent = getInstance(RiskSyncAgent.class);

        ErrorHandler errorHandler = error -> {
            logger.logf(LogMessage.FATAL_ERROR_EXITING, "Agent error: %s", error);
            System.exit(1);
        };
        List<SequencedRingBuffer<?>> journaledBuffers = new ArrayList<>();
        journaledBuffers.add(strategyMdBuffer);
        journaledBuffers.add(intentBuffer);
        journaledBuffers.add(stratExecReportBuffer);
        journaledBuffers.add(orderOutboundBuffer);
        journaledBuffers.add(omsExecReportBuffer);
        wireJournal(strategyId, sessionId, journaledBuffers, epochClock, errorHandler, logger, properties);

        startAgentRunners(
                inbounds,
                omsAgent,
                outboundAgents,
                muxAgent,
                routerAgent,
                strategy,
                pnlReportingAgent,
                riskSyncAgent,
                errorHandler);
    }

    private void wireJournal(
            int strategyId,
            String sessionId,
            List<SequencedRingBuffer<?>> journaledBuffers,
            EpochClock epochClock,
            ErrorHandler errorHandler,
            Logger logger,
            Properties properties) {
        if (!properties.getBooleanProperty("journal.enabled")) {
            return;
        }
        Path journalPath = Path.of("/tmp/journal-" + sessionId + ".bin");
        long fileSizeBytes = (long) properties.getIntProperty("journal.file.size.mb") * 1024L * 1024L;
        try {
            JournalWriter journalWriter = new JournalWriter(journalPath, fileSizeBytes);
            for (SequencedRingBuffer<?> buf : journaledBuffers) {
                buf.addHandler(journalWriter);
                buf.start();
            }
            S3Client s3Client = getInstance(S3Client.class);
            String journalBucket = properties.getStringProperty("journal.bucket");
            String s3Key = strategyId + "/" + sessionId + "/journal.zst";
            int flushIntervalSeconds = properties.getIntProperty("journal.flush.interval.seconds");
            JournalManagerAgent journalManagerAgent = new JournalManagerAgent(
                    journalWriter,
                    journalPath,
                    s3Client,
                    journalBucket,
                    s3Key,
                    epochClock,
                    Duration.ofSeconds(flushIntervalSeconds),
                    logger);
            GnomeAgentRunner journalRunner = new GnomeAgentRunner(journalManagerAgent, errorHandler);
            GnomeAgentRunner.startOnThread(journalRunner);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    journalRunner.close();
                } catch (Exception e) {
                    /* best effort */
                }
            }));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create journal writer", e);
        }
    }

    private static void startAgentRunners(
            List<DefaultInboundOrchestrator<?>> inbounds,
            OmsAgent omsAgent,
            List<GnomeAgent> outboundAgents,
            MarketDataMultiplexer muxAgent,
            ExchangeRouter routerAgent,
            StrategyAgent strategy,
            PnlReportingAgent pnlReportingAgent,
            RiskSyncAgent riskSyncAgent,
            ErrorHandler errorHandler) {
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
            PositionView positionView,
            SecurityMaster securityMaster) {
        Properties properties = getInstance(Properties.class);
        String strategyType = properties.getStringProperty("strategy.type");

        if ("python".equals(strategyType)) {
            PythonStrategyCallback callback = PythonStrategyAgent.getCallback();
            if (callback == null) {
                throw new IllegalStateException(
                        "Python strategy callback not set. Call PythonStrategyAgent.setCallback() before Orchestrator.main().");
            }
            return PythonStrategyAgent.createWithBuffers(
                    mdBuf, erBuf, intentBuf, positionView, securityMaster, callback);
        }

        String className = properties.getStringProperty("strategy.class");
        Map<String, String> strategyArgs = properties.getPropertiesByPrefix("strategy.args.");

        try {
            Class<?> clazz = Class.forName(className);
            for (Constructor<?> ctor : clazz.getConstructors()) {
                StrategyAgent result = tryInstantiateConstructor(
                        ctor, mdBuf, erBuf, intentBuf, positionView, securityMaster, strategyArgs);
                if (result != null) {
                    return result;
                }
            }
            throw new IllegalArgumentException("No constructor found for " + className + " matching strategy.args: "
                    + strategyArgs.keySet() + ". Ensure the class is compiled with -parameters.");
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate strategy class: " + className, e);
        }
    }

    private static StrategyAgent tryInstantiateConstructor(
            Constructor<?> ctor,
            SequencedRingBuffer<?> mdBuf,
            SequencedRingBuffer<OrderExecutionReport> erBuf,
            SequencedRingBuffer<Intent> intentBuf,
            PositionView positionView,
            SecurityMaster securityMaster,
            Map<String, String> strategyArgs)
            throws ReflectiveOperationException {
        Parameter[] params = ctor.getParameters();
        if (params.length < 5 || !isInfrastructureParams(params)) {
            return null;
        }
        if (params.length == 5 && strategyArgs.isEmpty()) {
            return (StrategyAgent) ctor.newInstance(mdBuf, erBuf, intentBuf, positionView, securityMaster);
        }
        if (params.length - 5 != strategyArgs.size()) {
            return null;
        }
        Set<String> userParamNames = new HashSet<>();
        for (int i = 5; i < params.length; i++) {
            userParamNames.add(params[i].getName());
        }
        if (!userParamNames.equals(strategyArgs.keySet())) {
            return null;
        }
        Object[] args = new Object[params.length];
        args[0] = mdBuf;
        args[1] = erBuf;
        args[2] = intentBuf;
        args[3] = positionView;
        args[4] = securityMaster;
        for (int i = 5; i < params.length; i++) {
            args[i] = convertStrategyArg(strategyArgs.get(params[i].getName()), params[i].getType());
        }
        return (StrategyAgent) ctor.newInstance(args);
    }

    private static boolean isInfrastructureParams(Parameter[] params) {
        return SequencedRingBuffer.class.isAssignableFrom(params[0].getType())
                && SequencedRingBuffer.class.isAssignableFrom(params[1].getType())
                && SequencedRingBuffer.class.isAssignableFrom(params[2].getType())
                && PositionView.class.isAssignableFrom(params[3].getType())
                && SecurityMaster.class.isAssignableFrom(params[4].getType());
    }

    private static Object convertStrategyArg(String value, Class<?> type) {
        Function<String, Object> converter = CONVERTERS.get(type);
        if (converter == null) {
            throw new IllegalArgumentException("Unsupported strategy arg type: " + type.getName());
        }
        return converter.apply(value);
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
