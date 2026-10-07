package group.gnometrading.trading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.GatewayRunners;
import group.gnometrading.gateways.inbound.DefaultInboundOrchestrator;
import group.gnometrading.gateways.outbound.DefaultOutboundOrchestrator;
import group.gnometrading.gateways.outbound.recovery.VenueOrderQuery;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.RetryableHTTPClient;
import group.gnometrading.oms.OmsAgent;
import group.gnometrading.oms.OrderManagementSystem;
import group.gnometrading.oms.ledger.LedgerAgent;
import group.gnometrading.oms.ledger.LedgerRing;
import group.gnometrading.oms.ledger.LedgerSink;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.PriceWriterAgent;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.PositionView;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.RiskSyncAgent;
import group.gnometrading.oms.state.PooledOrderStateManager;
import group.gnometrading.resources.Properties;
import group.gnometrading.risk.RiskMaster;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.JournalWriter;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.shared.AwsModule;
import group.gnometrading.shared.RegistryEndpoint;
import group.gnometrading.shared.RiskModule;
import group.gnometrading.shared.SessionTag;
import group.gnometrading.simulation.config.ExchangeProfileConfig;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
import group.gnometrading.simulation.latency.LatencySeeds;
import group.gnometrading.sm.Listing;
import group.gnometrading.strategies.PythonStrategyAgent;
import group.gnometrading.strategies.PythonStrategyAgent.PythonStrategyCallback;
import group.gnometrading.strategies.StrategyAgent;
import group.gnometrading.strategies.StrategyFactory;
import group.gnometrading.trading.recovery.StartupRecovery;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
 * sessions insert a {@link MarketDataMultiplexer} on the inbound side and an {@link ListingRouter}
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
    private static final int LEDGER_CONNECT_TIMEOUT_MS = 1_000;
    private static final int LEDGER_RESPONSE_TIMEOUT_MS = 2_000;
    private static final long HEARTBEAT_SAMPLE_INTERVAL_MS = 1_000;
    private static final long HEARTBEAT_POST_INTERVAL_MS = 5_000;
    // Paper sessions are reproducible by default; set simulation.seed to vary the latency draws.
    private static final long PAPER_TRADING_DEFAULT_SEED = 0x9E3779B97F4A7C15L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Set once configure() has started them; read by onClose(), which may run on a shutdown hook thread.
    private volatile AgentRunners runners;
    // The live venue gateways' reader and supervisor threads; their order writers are among the runners. Filled before
    // the runners are published, so onClose sees them all.
    private final List<GatewayRunners> outboundGateways = new ArrayList<>();
    private volatile GnomeAgentRunner journalRunner;
    private volatile GnomeAgentRunner heartbeatRunner;
    private volatile Logger logger;

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
        this.logger = logger;
        SecurityMaster securityMaster = getInstance(SecurityMaster.class);
        Properties properties = getInstance(Properties.class);
        RiskEngine riskEngine = getInstance(RiskEngine.class);

        int strategyId = properties.getIntProperty("strategy.id");
        List<Listing> listings = resolveListings(properties, securityMaster);
        AgentRuntimeInstaller.install(properties, logger, listings.size());

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
        String sessionId = properties.hasProperty("session.id") ? properties.getStringProperty("session.id") : null;
        // Without a session (a local run) there is no ledger to write to or to recover from.
        LedgerRing ledgerRing = sessionId == null
                ? null
                : new LedgerRing(
                        properties.getIntProperty("ledger.ring.capacity"),
                        LedgerAgent.MAX_EVENTS_PER_BATCH,
                        TimeUnit.MILLISECONDS.toNanos(properties.getLongProperty("ledger.max.lag.ms")),
                        positionTracker);
        SharedPriceBuffer priceBuffer = new SharedPriceBuffer(listings.size());
        PriceSlotRegistry priceSlotRegistry = new PriceSlotRegistry(listings.size());
        for (Listing listing : listings) {
            priceSlotRegistry.register(listing.listingId());
        }
        OrderManagementSystem oms = new OrderManagementSystem(
                logger,
                new PooledOrderStateManager(),
                positionTracker,
                riskEngine,
                securityMaster,
                priceBuffer,
                priceSlotRegistry,
                ledgerRing != null ? ledgerRing : LedgerSink.NONE,
                getInstance(EpochNanoClock.class));
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

        ErrorHandler errorHandler = sessionErrorHandler(logger);
        Map<Integer, VenueOrderQuery> venueQueries = new HashMap<>();
        OutboundSetup outboundSetup = setupOutbound(
                listings, perListingMdBuffers, orderOutboundBuffer, omsExecReportBuffer, globalSequence, venueQueries);
        List<GnomeAgent> outboundAgents = outboundSetup.agents();
        ListingRouter routerAgent = outboundSetup.router();

        OmsAgent omsAgent = new OmsAgent(
                oms,
                intentBuffer,
                omsExecReportBuffer,
                orderOutboundBuffer,
                stratExecReportBuffer,
                getInstance(EpochNanoClock.class));
        StrategyAgent strategy = createStrategyAgent(
                strategyId, strategyMdBuffer, stratExecReportBuffer, intentBuffer, positionView, securityMaster);

        EpochClock epochClock = SystemEpochClock.INSTANCE;

        LedgerAgent ledgerAgent = ledgerRing == null
                ? null
                : recoverAndCreateLedgerAgent(
                        ledgerRing,
                        positionTracker,
                        priceBuffer,
                        priceSlotRegistry,
                        sessionId,
                        strategyId,
                        listings,
                        venueQueries,
                        epochClock);

        // Built here rather than injected so the policies that value positions get the session's mark prices.
        RiskSyncAgent riskSyncAgent = new RiskSyncAgent(
                getInstance(RiskMaster.class),
                riskEngine,
                epochClock,
                Duration.ofMillis(properties.getIntProperty("risk.refresh.interval.ms")),
                logger,
                priceBuffer,
                priceSlotRegistry);

        List<SequencedRingBuffer<?>> journaledBuffers = new ArrayList<>();
        journaledBuffers.add(strategyMdBuffer);
        journaledBuffers.add(intentBuffer);
        journaledBuffers.add(stratExecReportBuffer);
        journaledBuffers.add(orderOutboundBuffer);
        journaledBuffers.add(omsExecReportBuffer);
        this.journalRunner =
                wireJournal(strategyId, sessionId, journaledBuffers, epochClock, errorHandler, logger, properties);

        this.runners = startAgentRunners(
                inbounds,
                omsAgent,
                outboundAgents,
                muxAgent,
                routerAgent,
                strategy,
                ledgerAgent,
                priceWriterAgent,
                riskSyncAgent,
                errorHandler);
        this.heartbeatRunner =
                startHeartbeat(sessionId, ledgerAgent, ledgerRing, priceBuffer, priceSlotRegistry, errorHandler);
        // A fallback for plain Java runs; an embedding process closes first, which makes this a no-op.
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "orchestrator-shutdown"));
        reportRunning(sessionId, logger);
    }

    // Both lines are the only evidence an operator stop shut down cleanly rather than being cut off at the timeout.
    @Override
    protected final void onClose() {
        AgentRunners started = this.runners;
        if (started == null) {
            return;
        }
        logger.logf(LogMessage.DEBUG, "Shutting down: closing agents");
        final long start = System.nanoTime();
        closeQuietly(started.strategy(), logger);
        closeQuietly(started.oms(), logger);
        closeQuietly(started.router(), logger);
        for (GnomeAgentRunner outbound : started.outboundAgents()) {
            closeQuietly(outbound, logger);
        }
        for (GatewayRunners gateway : outboundGateways) {
            closeQuietly(gateway, "outbound gateway", logger);
        }
        closeQuietly(started.mux(), logger);
        for (GatewayRunners gateway : started.inboundGateways()) {
            closeQuietly(gateway, "market data gateway", logger);
        }
        // After the OMS and gateways, so its final write holds everything they recorded while closing.
        closeQuietly(started.ledger(), logger);
        closeQuietly(started.priceWriter(), logger);
        closeQuietly(started.riskSync(), logger);
        // Last, so the journal holds everything the agents wrote while closing.
        closeQuietly(journalRunner, logger);
        // After everything else, so the session keeps reporting while it shuts down.
        closeQuietly(heartbeatRunner, logger);
        logger.logf(LogMessage.DEBUG, "Agents closed in %d ms", (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Picks up what the strategy's earlier sessions left (positions, and in live, orders on the venue) before any
     * agent starts, then builds the agent that records this session's orders and fills.
     */
    private LedgerAgent recoverAndCreateLedgerAgent(
            LedgerRing ledgerRing,
            DefaultPositionTracker positionTracker,
            SharedPriceBuffer priceBuffer,
            PriceSlotRegistry priceSlotRegistry,
            String sessionId,
            int strategyId,
            List<Listing> listings,
            Map<Integer, VenueOrderQuery> venueQueries,
            EpochClock epochClock) {
        Properties properties = getInstance(Properties.class);
        try {
            new StartupRecovery(
                            getInstance(RegistryConnection.class),
                            positionTracker,
                            logger,
                            epochClock,
                            sessionId,
                            strategyId,
                            properties.getStringProperty("mode"),
                            properties.getBooleanProperty("recovery.inherit"),
                            listings,
                            venueQueries)
                    .run();
        } catch (IOException e) {
            throw new UncheckedIOException("Startup recovery couldn't reach the venue", e);
        }
        return new LedgerAgent(
                ledgerRing,
                registryWriterConnection(),
                epochClock,
                logger,
                sessionId,
                priceBuffer,
                priceSlotRegistry,
                properties.getLongProperty("ledger.flush.interval.ms"),
                properties.getLongProperty("ledger.mark.interval.ms"));
    }

    /** Only a session reports to the registry; a local run has nothing to report to. */
    private GnomeAgentRunner startHeartbeat(
            String sessionId,
            LedgerAgent ledgerAgent,
            LedgerRing ledgerRing,
            SharedPriceBuffer priceBuffer,
            PriceSlotRegistry priceSlotRegistry,
            ErrorHandler errorHandler) {
        if (sessionId == null) {
            return null;
        }
        AgentRunners started = this.runners;
        List<GatewayRunners> gateways = new ArrayList<>(started.inboundGateways());
        gateways.addAll(outboundGateways);
        List<GnomeAgentRunner> watched = new ArrayList<>();
        for (GnomeAgentRunner runner : new GnomeAgentRunner[] {
            started.strategy(),
            started.oms(),
            started.mux(),
            started.router(),
            started.ledger(),
            started.priceWriter(),
            started.riskSync()
        }) {
            if (runner != null) {
                watched.add(runner);
            }
        }
        watched.addAll(started.outboundAgents());
        for (GatewayRunners gateway : gateways) {
            watched.addAll(gateway.runners());
        }
        GnomeAgentRunner runner = new GnomeAgentRunner(
                new SessionHeartbeatAgent(
                        sessionId,
                        registryWriterConnection(),
                        SystemEpochClock.INSTANCE,
                        logger,
                        watched,
                        gateways,
                        priceBuffer,
                        priceSlotRegistry,
                        ledgerAgent,
                        ledgerRing,
                        HEARTBEAT_SAMPLE_INTERVAL_MS,
                        HEARTBEAT_POST_INTERVAL_MS),
                errorHandler);
        GnomeAgentRunner.startOnThread(runner);
        return runner;
    }

    /**
     * A background writer's own connection (the ledger's, the heartbeat's): the registry client isn't thread-safe,
     * and a write must fail fast and be retried on the agent's schedule rather than block its thread in the client's
     * own retries.
     */
    private RegistryConnection registryWriterConnection() {
        RegistryEndpoint endpoint = getInstance(RegistryEndpoint.class);
        return new RegistryConnection(
                endpoint.host(),
                endpoint.apiKey(),
                RetryableHTTPClient.builder()
                        .withMaxRetries(0)
                        .withHttpClient(HTTPClient.builder()
                                .withConnectTimeout(LEDGER_CONNECT_TIMEOUT_MS)
                                .withResponseTimeout(LEDGER_RESPONSE_TIMEOUT_MS))
                        .build());
    }

    private void reportRunning(String sessionId, Logger logger) {
        if (sessionId == null) {
            return;
        }
        RegistryEndpoint registry = getInstance(RegistryEndpoint.class);
        new SessionStatusReporter(URI.create("https://" + registry.host()), registry.apiKey(), logger)
                .reportRunning(sessionId);
    }

    private GnomeAgentRunner wireJournal(
            int strategyId,
            String sessionId,
            List<SequencedRingBuffer<?>> journaledBuffers,
            EpochClock epochClock,
            ErrorHandler errorHandler,
            Logger logger,
            Properties properties) {
        if (!properties.getBooleanProperty("journal.enabled")) {
            return null;
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
            return journalRunner;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create journal writer", e);
        }
    }

    private record OutboundSetup(List<GnomeAgent> agents, ListingRouter router) {}

    private OutboundSetup setupOutbound(
            List<Listing> listings,
            List<SequencedRingBuffer<?>> perListingMdBuffers,
            SequencedRingBuffer<Intent> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> omsExecReportBuffer,
            GlobalSequence globalSequence,
            Map<Integer, VenueOrderQuery> venueQueries) {
        List<GnomeAgent> agents = new ArrayList<>(listings.size());
        if (listings.size() == 1) {
            agents.add(createOutboundGateway(
                    listings.get(0),
                    perListingMdBuffers.get(0),
                    orderOutboundBuffer,
                    omsExecReportBuffer,
                    venueQueries));
            return new OutboundSetup(agents, null);
        }
        Map<Long, SequencedRingBuffer<?>> perListingOutBufs = new HashMap<>();
        List<SequencedRingBuffer<OrderExecutionReport>> perExchangeExecBufs = new ArrayList<>(listings.size());
        for (int i = 0; i < listings.size(); i++) {
            Listing listing = listings.get(i);
            long routingKey = ListingRouter.routingKey(
                    listing.exchange().exchangeId(), listing.security().securityId());
            SequencedRingBuffer<Intent> perExchangeOutBuf =
                    new SequencedRingBuffer<>(Intent::new, globalSequence, OUTBOUND_BUFFER_SIZE);
            SequencedRingBuffer<OrderExecutionReport> perExchangeExecBuf =
                    new SequencedRingBuffer<>(OrderExecutionReport::new, globalSequence, OUTBOUND_BUFFER_SIZE);
            perListingOutBufs.put(routingKey, perExchangeOutBuf);
            perExchangeExecBufs.add(perExchangeExecBuf);
            agents.add(createOutboundGateway(
                    listing, perListingMdBuffers.get(i), perExchangeOutBuf, perExchangeExecBuf, venueQueries));
        }
        return new OutboundSetup(
                agents,
                new ListingRouter(orderOutboundBuffer, perListingOutBufs, perExchangeExecBufs, omsExecReportBuffer));
    }

    private record AgentRunners(
            List<GatewayRunners> inboundGateways,
            GnomeAgentRunner strategy,
            GnomeAgentRunner oms,
            List<GnomeAgentRunner> outboundAgents,
            GnomeAgentRunner mux,
            GnomeAgentRunner router,
            GnomeAgentRunner ledger,
            GnomeAgentRunner priceWriter,
            GnomeAgentRunner riskSync) {}

    private static AgentRunners startAgentRunners(
            List<DefaultInboundOrchestrator<?>> inbounds,
            OmsAgent omsAgent,
            List<GnomeAgent> outboundAgents,
            MarketDataMultiplexer muxAgent,
            ListingRouter routerAgent,
            StrategyAgent strategy,
            LedgerAgent ledgerAgent,
            PriceWriterAgent priceWriterAgent,
            RiskSyncAgent riskSyncAgent,
            ErrorHandler errorHandler) {
        List<GatewayRunners> inboundGateways = new ArrayList<>(inbounds.size());
        for (DefaultInboundOrchestrator<?> inbound : inbounds) {
            inboundGateways.add(inbound.startGatewayAgents());
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
        GnomeAgentRunner ledgerRunner = null;
        if (ledgerAgent != null) {
            ledgerRunner = new GnomeAgentRunner(ledgerAgent, errorHandler);
            GnomeAgentRunner.startOnThread(ledgerRunner);
        }
        GnomeAgentRunner riskSyncRunner = new GnomeAgentRunner(riskSyncAgent, errorHandler);
        GnomeAgentRunner.startOnThread(riskSyncRunner);
        return new AgentRunners(
                inboundGateways,
                strategyRunner,
                omsRunner,
                outboundRunners,
                muxRunner,
                routerRunner,
                ledgerRunner,
                priceWriterRunner,
                riskSyncRunner);
    }

    private static void closeQuietly(GnomeAgentRunner runner, Logger logger) {
        if (runner == null) {
            return;
        }
        try {
            runner.close();
        } catch (Exception e) { // best-effort: one agent failing to close must not stop the others closing
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Failed to close %s: %s",
                    runner.getAgent().roleName(),
                    e);
        }
    }

    private static void closeQuietly(AutoCloseable closeable, String name, Logger logger) {
        try {
            closeable.close();
        } catch (Exception e) { // best-effort, as for each agent above
            logger.logf(LogMessage.UNKNOWN_ERROR, "Failed to close %s: %s", name, e);
        }
    }

    /** Every agent of a session ends it on an error, the venue gateways' reader and supervisor included. */
    private static ErrorHandler sessionErrorHandler(final Logger logger) {
        return error -> {
            logger.logf(LogMessage.FATAL_ERROR_EXITING, "Agent error: %s", error);
            System.exit(1);
        };
    }

    private GnomeAgent createOutboundGateway(
            Listing listing,
            SequencedRingBuffer<?> marketDataBuffer,
            SequencedRingBuffer<?> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            Map<Integer, VenueOrderQuery> venueQueries) {
        Properties properties = getInstance(Properties.class);
        String mode = properties.getStringProperty("mode");

        if ("paper".equals(mode)) {
            ExchangeProfileConfig profile = ExchangeProfileConfig.resolveForListing(properties, listing.listingId());
            long baseSeed = properties.hasProperty("simulation.seed")
                    ? properties.getLongProperty("simulation.seed")
                    : PAPER_TRADING_DEFAULT_SEED;
            MbpSimulatedExchange exchange = (MbpSimulatedExchange)
                    profile.toSimulatedExchange(LatencySeeds.derive(baseSeed, listing.listingId()));
            return new PaperTradingOutboundGateway(
                    exchange,
                    marketDataBuffer,
                    orderOutboundBuffer,
                    execReportBuffer,
                    getInstance(EpochNanoClock.class),
                    SessionTag.of(properties));
        }

        final Class<? extends DefaultOutboundOrchestrator> orchClass =
                DefaultOutboundOrchestrator.findOutboundOrchestrator(listing);
        // Each venue gateway is built for its own listing, as each market data gateway is.
        final DefaultOutboundOrchestrator outboundOrch =
                createChildOrchestrator(orchClass, Map.of(Listing.class, listing));
        venueQueries.put(listing.listingId(), outboundOrch.createVenueOrderQuery());
        final DefaultOutboundOrchestrator.OutboundAgents agents = outboundOrch.startGatewayAgents(
                orderOutboundBuffer, execReportBuffer, sessionErrorHandler(getInstance(Logger.class)));
        outboundGateways.add(agents.gateway());
        return agents.writer();
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

        return StrategyFactory.create(
                className, strategyId, mdBuf, erBuf, intentBuf, positionView, securityMaster, strategyArgs);
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
