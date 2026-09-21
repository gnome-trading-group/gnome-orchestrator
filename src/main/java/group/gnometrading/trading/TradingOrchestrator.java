package group.gnometrading.trading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.inbound.DefaultInboundOrchestrator;
import group.gnometrading.gateways.outbound.DefaultOutboundOrchestrator;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.oms.OmsAgent;
import group.gnometrading.oms.OrderManagementSystem;
import group.gnometrading.oms.pnl.PnlReportingAgent;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.PriceWriterAgent;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
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
import group.gnometrading.simulation.config.ExchangeProfileConfig;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>Configure via the {@code listings} property (JSON array of listing IDs, e.g. {@code [1,2,3]}).
 */
public class TradingOrchestrator extends Orchestrator {

    static {
        instanceClass = TradingOrchestrator.class;
    }

    private static final int OUTBOUND_BUFFER_SIZE = 64;
    private static final Duration DEFAULT_PNL_FLUSH_INTERVAL = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        SharedPriceBuffer priceBuffer = new SharedPriceBuffer(listings.size());
        PriceSlotRegistry priceSlotRegistry = new PriceSlotRegistry(listings.size());
        for (Listing listing : listings) {
            priceSlotRegistry.register(listing.listingId());
        }
        OrderManagementSystem oms = new OrderManagementSystem(
                logger,
                new RingBufferOrderStateManager(),
                positionTracker,
                riskEngine,
                securityMaster,
                priceBuffer,
                priceSlotRegistry);
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

        PriceWriterAgent priceWriterAgent =
                new PriceWriterAgent(priceBuffer, priceSlotRegistry, securityMaster, strategyMdBuffer);

        SequencedRingBuffer<Intent> orderOutboundBuffer =
                new SequencedRingBuffer<>(Intent::new, globalSequence, OUTBOUND_BUFFER_SIZE);
        SequencedRingBuffer<OrderExecutionReport> omsExecReportBuffer =
                new SequencedRingBuffer<>(OrderExecutionReport::new, globalSequence, OUTBOUND_BUFFER_SIZE);

        OutboundSetup outboundSetup =
                setupOutbound(listings, perListingMdBuffers, orderOutboundBuffer, omsExecReportBuffer, globalSequence);
        List<GnomeAgent> outboundAgents = outboundSetup.agents();
        ExchangeRouter routerAgent = outboundSetup.router();

        OmsAgent omsAgent = new OmsAgent(
                oms,
                intentBuffer,
                omsExecReportBuffer,
                orderOutboundBuffer,
                stratExecReportBuffer,
                getInstance(EpochNanoClock.class));
        StrategyAgent strategy = createStrategyAgent(
                strategyId, strategyMdBuffer, stratExecReportBuffer, intentBuffer, positionView, securityMaster);

        RegistryConnection registryConnection = getInstance(RegistryConnection.class);
        EpochClock epochClock = SystemEpochClock.INSTANCE;

        String sessionId = properties.hasProperty("session.id") ? properties.getStringProperty("session.id") : null;

        PnlReportingAgent pnlReportingAgent = null;
        if (sessionId != null) {
            int pnlFlushSeconds = properties.getIntProperty("pnl.flush.interval.seconds");
            pnlReportingAgent = new PnlReportingAgent(
                    positionTracker,
                    registryConnection,
                    epochClock,
                    Duration.ofSeconds(pnlFlushSeconds),
                    listings.size(),
                    sessionId,
                    priceBuffer,
                    priceSlotRegistry);
        }

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

        AgentRunners runners = startAgentRunners(
                inbounds,
                omsAgent,
                outboundAgents,
                muxAgent,
                routerAgent,
                strategy,
                pnlReportingAgent,
                priceWriterAgent,
                riskSyncAgent,
                errorHandler);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            closeQuietly(runners.strategy());
            closeQuietly(runners.oms());
            closeQuietly(runners.router());
            for (GnomeAgentRunner outbound : runners.outboundAgents()) {
                closeQuietly(outbound);
            }
            closeQuietly(runners.mux());
            closeQuietly(runners.pnl());
            closeQuietly(runners.priceWriter());
            closeQuietly(runners.riskSync());
        }));
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

    private record OutboundSetup(List<GnomeAgent> agents, ExchangeRouter router) {}

    private OutboundSetup setupOutbound(
            List<Listing> listings,
            List<SequencedRingBuffer<?>> perListingMdBuffers,
            SequencedRingBuffer<Intent> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> omsExecReportBuffer,
            GlobalSequence globalSequence) {
        List<GnomeAgent> agents = new ArrayList<>(listings.size());
        if (listings.size() == 1) {
            agents.add(createOutboundGateway(
                    listings.get(0), perListingMdBuffers.get(0), orderOutboundBuffer, omsExecReportBuffer));
            return new OutboundSetup(agents, null);
        }
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
            agents.add(
                    createOutboundGateway(listing, perListingMdBuffers.get(i), perExchangeOutBuf, perExchangeExecBuf));
        }
        return new OutboundSetup(
                agents,
                new ExchangeRouter(orderOutboundBuffer, perExchangeOutBufs, perExchangeExecBufs, omsExecReportBuffer));
    }

    private record AgentRunners(
            GnomeAgentRunner strategy,
            GnomeAgentRunner oms,
            List<GnomeAgentRunner> outboundAgents,
            GnomeAgentRunner mux,
            GnomeAgentRunner router,
            GnomeAgentRunner pnl,
            GnomeAgentRunner priceWriter,
            GnomeAgentRunner riskSync) {}

    private static AgentRunners startAgentRunners(
            List<DefaultInboundOrchestrator<?>> inbounds,
            OmsAgent omsAgent,
            List<GnomeAgent> outboundAgents,
            MarketDataMultiplexer muxAgent,
            ExchangeRouter routerAgent,
            StrategyAgent strategy,
            PnlReportingAgent pnlReportingAgent,
            PriceWriterAgent priceWriterAgent,
            RiskSyncAgent riskSyncAgent,
            ErrorHandler errorHandler) {
        for (DefaultInboundOrchestrator<?> inbound : inbounds) {
            inbound.startGatewayAgents();
        }
        GnomeAgentRunner omsRunner = new GnomeAgentRunner(omsAgent, errorHandler);
        GnomeAgentRunner.startOnThread(omsRunner);
        GnomeAgentRunner priceWriterRunner = new GnomeAgentRunner(priceWriterAgent, errorHandler);
        GnomeAgentRunner.startOnThread(priceWriterRunner);
        List<GnomeAgentRunner> outboundRunners = new ArrayList<>(outboundAgents.size());
        for (GnomeAgent outbound : outboundAgents) {
            GnomeAgentRunner runner = new GnomeAgentRunner(outbound, errorHandler);
            GnomeAgentRunner.startOnThread(runner);
            outboundRunners.add(runner);
        }
        GnomeAgentRunner muxRunner = null;
        if (muxAgent != null) {
            muxRunner = new GnomeAgentRunner(muxAgent, errorHandler);
            GnomeAgentRunner.startOnThread(muxRunner);
        }
        GnomeAgentRunner routerRunner = null;
        if (routerAgent != null) {
            routerRunner = new GnomeAgentRunner(routerAgent, errorHandler);
            GnomeAgentRunner.startOnThread(routerRunner);
        }
        GnomeAgentRunner strategyRunner = new GnomeAgentRunner(strategy, errorHandler);
        GnomeAgentRunner.startOnThread(strategyRunner);
        GnomeAgentRunner pnlRunner = null;
        if (pnlReportingAgent != null) {
            pnlRunner = new GnomeAgentRunner(pnlReportingAgent, errorHandler);
            GnomeAgentRunner.startOnThread(pnlRunner);
        }
        GnomeAgentRunner riskSyncRunner = new GnomeAgentRunner(riskSyncAgent, errorHandler);
        GnomeAgentRunner.startOnThread(riskSyncRunner);
        return new AgentRunners(
                strategyRunner,
                omsRunner,
                outboundRunners,
                muxRunner,
                routerRunner,
                pnlRunner,
                priceWriterRunner,
                riskSyncRunner);
    }

    private static void closeQuietly(GnomeAgentRunner runner) {
        if (runner == null) {
            return;
        }
        try {
            runner.close();
        } catch (Exception ignored) { // best-effort close on shutdown
        }
    }

    private GnomeAgent createOutboundGateway(
            Listing listing,
            SequencedRingBuffer<?> marketDataBuffer,
            SequencedRingBuffer<?> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer) {
        Properties properties = getInstance(Properties.class);
        String mode = properties.getStringProperty("mode");

        if ("paper".equals(mode)) {
            ExchangeProfileConfig profile = ExchangeProfileConfig.resolveForListing(properties, listing.listingId());
            MbpSimulatedExchange exchange = (MbpSimulatedExchange) profile.toSimulatedExchange();
            return new PaperTradingOutboundGateway(
                    exchange,
                    marketDataBuffer,
                    orderOutboundBuffer,
                    execReportBuffer,
                    getInstance(EpochNanoClock.class));
        }

        final Class<? extends DefaultOutboundOrchestrator> orchClass =
                DefaultOutboundOrchestrator.findOutboundOrchestrator(listing);
        final DefaultOutboundOrchestrator outboundOrch = createChildOrchestrator(orchClass);
        final ErrorHandler errorHandler = getInstance(ErrorHandler.class);
        return outboundOrch.startGatewayAgents(orderOutboundBuffer, execReportBuffer, errorHandler);
    }

    private StrategyAgent createStrategyAgent(
            int strategyId,
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
                    strategyId, mdBuf, erBuf, intentBuf, positionView, securityMaster, callback);
        }

        String className = properties.getStringProperty("strategy.class");
        Map<String, Object> strategyArgs;
        String argsJson = System.getenv("STRATEGY_ARGS_JSON");
        if (argsJson != null && !argsJson.isEmpty()) {
            try {
                strategyArgs = MAPPER.readValue(argsJson, new TypeReference<>() {});
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse STRATEGY_ARGS_JSON", e);
            }
        } else {
            strategyArgs = new HashMap<>(properties.getPropertiesByPrefix("strategy.args."));
        }

        try {
            Class<?> clazz = Class.forName(className);
            for (Constructor<?> ctor : clazz.getConstructors()) {
                StrategyAgent result = tryInstantiateConstructor(
                        ctor, strategyId, mdBuf, erBuf, intentBuf, positionView, securityMaster, strategyArgs);
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
            int strategyId,
            SequencedRingBuffer<?> mdBuf,
            SequencedRingBuffer<OrderExecutionReport> erBuf,
            SequencedRingBuffer<Intent> intentBuf,
            PositionView positionView,
            SecurityMaster securityMaster,
            Map<String, Object> strategyArgs)
            throws ReflectiveOperationException {
        Parameter[] params = ctor.getParameters();
        if (params.length < 6 || !isInfrastructureParams(params)) {
            return null;
        }
        if (params.length == 6 && strategyArgs.isEmpty()) {
            return (StrategyAgent) ctor.newInstance(strategyId, mdBuf, erBuf, intentBuf, positionView, securityMaster);
        }
        if (params.length - 6 != strategyArgs.size()) {
            return null;
        }
        Set<String> userParamNames = new HashSet<>();
        for (int i = 6; i < params.length; i++) {
            userParamNames.add(params[i].getName());
        }
        if (!userParamNames.equals(strategyArgs.keySet())) {
            return null;
        }
        Object[] args = new Object[params.length];
        args[0] = strategyId;
        args[1] = mdBuf;
        args[2] = erBuf;
        args[3] = intentBuf;
        args[4] = positionView;
        args[5] = securityMaster;
        for (int i = 6; i < params.length; i++) {
            args[i] = convertStrategyArg(strategyArgs.get(params[i].getName()), params[i]);
        }
        return (StrategyAgent) ctor.newInstance(args);
    }

    private static boolean isInfrastructureParams(Parameter[] params) {
        return int.class == params[0].getType()
                && SequencedRingBuffer.class.isAssignableFrom(params[1].getType())
                && SequencedRingBuffer.class.isAssignableFrom(params[2].getType())
                && SequencedRingBuffer.class.isAssignableFrom(params[3].getType())
                && PositionView.class.isAssignableFrom(params[4].getType())
                && SecurityMaster.class.isAssignableFrom(params[5].getType());
    }

    private static Object convertStrategyArg(Object value, Parameter param) {
        Class<?> type = param.getType();
        if (type.isInstance(value)) {
            return value;
        }
        if (value instanceof Number num) {
            return coerceNumber(num, type);
        }
        if (type == String.class) {
            return String.valueOf(value);
        }
        JavaType javaType = MAPPER.getTypeFactory().constructType(param.getParameterizedType());
        return MAPPER.convertValue(value, javaType);
    }

    private static Object coerceNumber(Number num, Class<?> type) {
        if (type == int.class || type == Integer.class) {
            return num.intValue();
        }
        if (type == long.class || type == Long.class) {
            return num.longValue();
        }
        if (type == double.class || type == Double.class) {
            return num.doubleValue();
        }
        if (type == float.class || type == Float.class) {
            return num.floatValue();
        }
        throw new IllegalArgumentException("Cannot coerce Number to " + type.getName());
    }

    private List<Listing> resolveListings(Properties properties, SecurityMaster securityMaster) {
        try {
            int[] ids = MAPPER.readValue(properties.getStringProperty("listings"), int[].class);
            List<Listing> result = new ArrayList<>(ids.length);
            for (int id : ids) {
                result.add(securityMaster.getListing(id));
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse listings property", e);
        }
    }
}
