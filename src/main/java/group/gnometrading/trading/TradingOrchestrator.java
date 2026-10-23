package group.gnometrading.trading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.account.SessionAccounts;
import group.gnometrading.account.VenueAccount;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.GatewayRunners;
import group.gnometrading.gateways.ProcessExit;
import group.gnometrading.gateways.inbound.DefaultInboundOrchestrator;
import group.gnometrading.gateways.outbound.DefaultOutboundOrchestrator;
import group.gnometrading.gateways.outbound.recovery.VenueOrderQuery;
import group.gnometrading.gateways.ratelimit.VenueRateLimiters;
import group.gnometrading.host.HostReadings;
import group.gnometrading.logging.AsyncLogger;
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
import group.gnometrading.oms.risk.ListingDirectory;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.RiskSyncAgent;
import group.gnometrading.oms.state.PooledOrderStateManager;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.MetricRow;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.JournalWriter;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sequencer.metrics.JournaledMetricRecorder;
import group.gnometrading.shared.AwsModule;
import group.gnometrading.shared.RegistryEndpoint;
import group.gnometrading.shared.RiskModule;
import group.gnometrading.shared.SessionTag;
import group.gnometrading.simulation.config.ExchangeSimulationConfig;
import group.gnometrading.simulation.config.SessionSimulationConfig;
import group.gnometrading.simulation.config.SimulationResolver;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
import group.gnometrading.simulation.latency.LatencySeeds;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.fee.FeeTimeline;
import group.gnometrading.strategies.PythonStrategyAgent;
import group.gnometrading.strategies.PythonStrategyAgent.PythonStrategyCallback;
import group.gnometrading.strategies.StrategyAgent;
import group.gnometrading.strategies.StrategyFactory;
import group.gnometrading.trading.recovery.SessionNotRunnableException;
import group.gnometrading.trading.recovery.StartupRecovery;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.SystemNanoClock;

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
    // Sealing is a local unmap, trim and rename; well inside the time systemd gives the process to stop.
    private static final Duration JOURNAL_SEAL_TIMEOUT = Duration.ofSeconds(5);
    // Read by the journal shipper (gnome-orchestrator ami/files/journal-shipper.py) to learn where segments go.
    private static final String JOURNAL_MANIFEST = "journal.json";
    // Paper sessions are reproducible by default; set simulation.seed to vary the latency draws.
    private static final long PAPER_TRADING_DEFAULT_SEED = 0x9E3779B97F4A7C15L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Set once configure() has started them; read by onClose(), which may run on a shutdown hook thread.
    private volatile AgentRunners runners;
    // The live venue gateways' reader and supervisor threads; their order writers are among the runners. Filled before
    // the runners are published, so onClose sees them all.
    private final List<GatewayRunners> outboundGateways = new ArrayList<>();
    private SessionAccounts sessionAccounts;
    private volatile GnomeAgentRunner journalRunner;
    // Set with the journal, before the heartbeat that reports on it starts; null with the journal off.
    private JournalSegmentSealer journalSealer;
    private Path journalDirectory;
    private volatile GnomeAgentRunner heartbeatRunner;
    private volatile Logger logger;
    // A session's way out on an error; null for a local run, which throws instead.
    private volatile SessionFatalHandler fatalHandler;
    // Null outside a registry session (a local run), which then exits without reporting anything.
    private SessionReports sessionReports;
    // One per session, so every listing trading on the same venue account draws on one request budget.
    private VenueRateLimiters rateLimiters;

    @Provides
    @Singleton
    public final EpochNanoClock provideEpochNanoClock() {
        return new SystemEpochNanoClock();
    }

    @Provides
    @Singleton
    public final Logger provideLogger(EpochNanoClock clock) {
        return new AsyncLogger(clock);
    }

    /**
     * A session that can't start exits rather than throwing: by then its venue gateways may already be connected, on
     * threads that would keep the process alive with nothing trading and nothing reporting. It says why first
     * ({@link SessionFatalHandler}). A session already stopped or ended exits quietly: it was never meant to run.
     */
    @Override
    public final void configure() {
        install(new RiskModule());
        install(new AwsModule());

        Logger logger = getInstance(Logger.class);
        this.logger = logger;
        Properties properties = getInstance(Properties.class);
        if (!properties.hasProperty("session.id")) {
            configureSession(logger, properties);
            return;
        }
        SessionFatalHandler fatal = createFatalHandler(logger, properties);
        this.fatalHandler = fatal;
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> fatal.exit(1, error));
        try {
            configureSession(logger, properties);
        } catch (SessionNotRunnableException e) {
            fatal.exitWithoutReport(0, e.getMessage());
        } catch (RuntimeException | Error e) {
            fatal.exit(1, e);
        }
    }

    private SessionFatalHandler createFatalHandler(Logger logger, Properties properties) {
        RegistryEndpoint registry = getInstance(RegistryEndpoint.class);
        String alertsUrl =
                properties.hasProperty("alerts.api.url") ? properties.getStringProperty("alerts.api.url") : null;
        this.sessionReports = new SessionReports(
                properties.getStringProperty("session.id"),
                alertsUrl == null ? null : URI.create(alertsUrl.endsWith("/") ? alertsUrl : alertsUrl + "/"),
                URI.create("https://" + registry.host()),
                registry.apiKey());
        return new SessionFatalHandler(
                logger,
                sessionReports,
                properties.getIntProperty("strategy.id"),
                properties.getStringProperty("mode"),
                System::exit);
    }

    private void configureSession(Logger logger, Properties properties) {
        SecurityMaster securityMaster = getInstance(SecurityMaster.class);
        int strategyId = properties.getIntProperty("strategy.id");
        List<Listing> listings = resolveListings(properties, securityMaster);
        ListingDirectory listingDirectory = RiskModule.directory(listings);
        RiskEngine riskEngine = RiskModule.riskEngine(properties, listingDirectory);
        AgentRuntimeInstaller.install(properties, logger, listings.size());

        // One total order over everything the strategy saw and did, so a journal replays the session exactly. Every
        // buffer on it is journaled. With several listings, each listing's own market data buffer stays on a sequence
        // of its own: the multiplexer copies it into the strategy's buffer, and that copy is the one journaled.
        GlobalSequence sessionSequence = new GlobalSequence();
        Map<SequencedRingBuffer<?>, JournalSource> journalSources = new IdentityHashMap<>();

        List<DefaultInboundOrchestrator<?>> inbounds = createInbounds(listings, sessionSequence);
        List<SequencedRingBuffer<?>> perListingMdBuffers = new ArrayList<>(listings.size());
        for (DefaultInboundOrchestrator<?> inbound : inbounds) {
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

        SequencedRingBuffer<Intent> intentBuffer = new SequencedRingBuffer<>(Intent::new, sessionSequence);
        SequencedRingBuffer<OrderExecutionReport> stratExecReportBuffer =
                new SequencedRingBuffer<>(OrderExecutionReport::new, sessionSequence, OUTBOUND_BUFFER_SIZE);
        journalSources.put(intentBuffer, JournalSource.STRATEGY_INTENT);
        journalSources.put(stratExecReportBuffer, JournalSource.STRATEGY_EXEC_REPORT);
        // The strategy's metric rows, on the session sequence so each is ordered against what it explains.
        SequencedRingBuffer<MetricRow> strategyMetricBuffer =
                new SequencedRingBuffer<>(MetricRow::new, sessionSequence);
        journalSources.put(strategyMetricBuffer, JournalSource.STRATEGY_METRIC);

        SequencedRingBuffer<?> strategyMdBuffer;
        MarketDataMultiplexer muxAgent = null;
        if (listings.size() == 1) {
            strategyMdBuffer = perListingMdBuffers.get(0);
        } else {
            strategyMdBuffer = new SequencedRingBuffer<>(Intent::new, sessionSequence);
            muxAgent = new MarketDataMultiplexer(perListingMdBuffers, strategyMdBuffer);
        }
        journalSources.put(strategyMdBuffer, JournalSource.STRATEGY_MARKET_DATA);

        PriceWriterAgent priceWriterAgent =
                new PriceWriterAgent(priceBuffer, priceSlotRegistry, securityMaster, strategyMdBuffer);

        SequencedRingBuffer<Intent> orderOutboundBuffer =
                new SequencedRingBuffer<>(Intent::new, sessionSequence, OUTBOUND_BUFFER_SIZE);
        SequencedRingBuffer<OrderExecutionReport> omsExecReportBuffer =
                new SequencedRingBuffer<>(OrderExecutionReport::new, sessionSequence, OUTBOUND_BUFFER_SIZE);
        journalSources.put(orderOutboundBuffer, JournalSource.OMS_ORDER);
        journalSources.put(omsExecReportBuffer, JournalSource.OMS_EXEC_REPORT);
        RoutedBuffers routedBuffers = createRoutedBuffers(listings.size(), sessionSequence, journalSources);

        EpochClock epochClock = SystemEpochClock.INSTANCE;
        ErrorHandler errorHandler = fatalHandler != null ? fatalHandler.errorHandler() : sessionErrorHandler(logger);
        // Before the outbound gateways start, which can publish execution reports as soon as they connect: a journal
        // poller only sees what is published after it exists, and a missed number stalls the journal.
        this.journalRunner = wireJournal(sessionId, sessionSequence, journalSources, errorHandler, logger, properties);

        Map<Integer, VenueOrderQuery> venueQueries = new HashMap<>();
        OutboundSetup outboundSetup = setupOutbound(
                listings, perListingMdBuffers, orderOutboundBuffer, omsExecReportBuffer, routedBuffers, venueQueries);
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
        strategy.configureExit(listings, sessionExit(logger, properties, strategyId, listings));
        // The journal is already draining this buffer, so declaring streams here can't wait on a full ring.
        strategy.configureMetrics(new JournaledMetricRecorder(strategyMetricBuffer));

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
                RiskModule.riskMaster(getInstance(RegistryConnection.class), properties, listingDirectory),
                riskEngine,
                epochClock,
                RiskModule.refreshInterval(properties),
                logger,
                priceBuffer,
                priceSlotRegistry,
                strategyId,
                "live".equals(properties.getStringProperty("mode")));

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
                riskEngine,
                logger,
                errorHandler);
        this.heartbeatRunner = startHeartbeat(
                sessionId, ledgerAgent, ledgerRing, priceBuffer, priceSlotRegistry, riskEngine, errorHandler);
        // A fallback for plain Java runs; an embedding process closes first, which makes this a no-op.
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "orchestrator-shutdown"));
        reportRunning(sessionId, logger);
    }

    // Both lines are the only evidence an operator stop shut down cleanly rather than being cut off at the timeout.
    @Override
    protected final void onClose() {
        SessionFatalHandler fatal = this.fatalHandler;
        if (fatal != null) {
            fatal.markShuttingDown();
        }
        AgentRunners started = this.runners;
        if (started == null) {
            return;
        }
        logger.logf(LogMessage.DEBUG, "Shutting down: closing agents");
        final long start = System.nanoTime();
        closeAgents(started, outboundGateways, journalRunner, heartbeatRunner, logger);
        logger.logf(LogMessage.DEBUG, "Agents closed in %d ms", (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Closes the session's agents in an order that lets each finish. A producer waits while the buffer it writes to is
     * full, and a closed consumer never makes room again, so nothing may still be producing into a buffer whose reader
     * has gone: the producer, and every close after it, would wait forever.
     */
    static void closeAgents(
            final AgentRunners started,
            final List<GatewayRunners> outboundGateways,
            final GnomeAgentRunner journalRunner,
            final GnomeAgentRunner heartbeatRunner,
            final Logger logger) {
        // Market data first, its gateways before the multiplexer that copies them, so none of it is still arriving
        // once the strategy stops reading. Closed after the strategy, a busy feed filled the strategy's buffer while
        // the
        // OMS and the gateways closed, and the multiplexer then waited on it for good.
        for (GatewayRunners gateway : started.inboundGateways()) {
            closeQuietly(gateway, "market data gateway", logger);
        }
        closeQuietly(started.mux(), logger);
        closeQuietly(started.strategy(), logger);
        closeQuietly(started.oms(), logger);
        closeQuietly(started.router(), logger);
        for (GnomeAgentRunner outbound : started.outboundAgents()) {
            closeQuietly(outbound, logger);
        }
        for (GatewayRunners gateway : outboundGateways) {
            closeQuietly(gateway, "outbound gateway", logger);
        }
        // After the OMS and gateways, so its final write holds everything they recorded while closing.
        closeQuietly(started.ledger(), logger);
        closeQuietly(started.priceWriter(), logger);
        closeQuietly(started.riskSync(), logger);
        // Last, so the journal holds everything the agents wrote while closing.
        closeQuietly(journalRunner, logger);
        // After everything else, so the session keeps reporting while it shuts down.
        closeQuietly(heartbeatRunner, logger);
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
            RiskEngine riskEngine,
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
            started.riskSync(),
            // A stuck journal holds up market data and every trading buffer once their rings fill.
            journalRunner
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
                        riskEngine,
                        rateLimiters.all(),
                        HostReadings.ofThisProcess(
                                journalDirectory != null ? journalDirectory.toFile() : new File("/")),
                        journalSealer,
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
            String sessionId,
            GlobalSequence sessionSequence,
            Map<SequencedRingBuffer<?>, JournalSource> journalSources,
            ErrorHandler errorHandler,
            Logger logger,
            Properties properties) {
        // Checked with the journal off too, so a buffer added without a source fails in every run, not only when
        // someone turns the journal on.
        verifyJournalCoverage(sessionSequence, journalSources);
        if (!properties.getBooleanProperty("journal.enabled")) {
            return null;
        }
        Path journalDirectory = Path.of(properties.getStringProperty("journal.directory"), sessionId);
        long segmentSizeBytes = (long) properties.getIntProperty("journal.segment.size.mb") * 1024L * 1024L;
        try {
            JournalSegmentSealer sealer = new JournalSegmentSealer(segmentSizeBytes, SystemEpochClock.INSTANCE, logger);
            JournalWriter journalWriter = new JournalWriter(journalDirectory, segmentSizeBytes, sealer::submit);
            this.journalSealer = sealer;
            this.journalDirectory = journalDirectory;
            MAPPER.writeValue(
                    journalDirectory.resolve(JOURNAL_MANIFEST).toFile(),
                    Map.of(
                            "bucket", properties.getStringProperty("journal.bucket"),
                            "keyPrefix", sessionId,
                            "region", properties.getStringProperty("journal.bucket.region")));
            List<SequencedPoller> pollers = new ArrayList<>(journalSources.size());
            for (SequencedRingBuffer<?> buffer : sessionSequence.buffers()) {
                pollers.add(buffer.createPoller(
                        journalWriter.source(journalSources.get(buffer).id())));
            }
            JournalManagerAgent journalManagerAgent = new JournalManagerAgent(
                    journalWriter,
                    pollers,
                    sealer,
                    Duration.ofSeconds(properties.getIntProperty("journal.segment.interval.seconds")),
                    JOURNAL_SEAL_TIMEOUT,
                    SystemEpochClock.INSTANCE,
                    logger);
            GnomeAgentRunner journalRunner = new GnomeAgentRunner(journalManagerAgent, errorHandler);
            GnomeAgentRunner.startOnThread(journalRunner);
            return journalRunner;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create journal writer", e);
        }
    }

    /** A lone listing's market data is what the strategy reads, so it goes straight onto the session sequence. */
    private List<DefaultInboundOrchestrator<?>> createInbounds(List<Listing> listings, GlobalSequence sessionSequence) {
        List<DefaultInboundOrchestrator<?>> inbounds = new ArrayList<>(listings.size());
        for (Listing listing : listings) {
            Map<Class<?>, Object> inboundOverrides = new HashMap<>();
            inboundOverrides.put(Listing.class, listing);
            if (fatalHandler != null) {
                inboundOverrides.put(ProcessExit.class, fatalHandler);
            }
            if (listings.size() == 1) {
                inboundOverrides.put(GlobalSequence.class, sessionSequence);
            }
            inbounds.add(createChildOrchestrator(
                    DefaultInboundOrchestrator.findInboundOrchestrator(listing), inboundOverrides));
        }
        return inbounds;
    }

    /**
     * Fails when a buffer takes numbers from the session sequence without a journal source. The journal writes in
     * strict sequence order, so the first number it never sees would stop it for the rest of the session.
     */
    static void verifyJournalCoverage(
            GlobalSequence sessionSequence, Map<SequencedRingBuffer<?>, JournalSource> journalSources) {
        for (SequencedRingBuffer<?> buffer : sessionSequence.buffers()) {
            if (!journalSources.containsKey(buffer)) {
                throw new IllegalStateException(
                        "A buffer on the session sequence has no journal source; every buffer on it is journaled");
            }
        }
    }

    /** The router's copy of each order and execution report, one pair of buffers per listing. */
    private record RoutedBuffers(
            List<SequencedRingBuffer<Intent>> orders, List<SequencedRingBuffer<OrderExecutionReport>> execReports) {}

    private static RoutedBuffers createRoutedBuffers(
            int listingCount,
            GlobalSequence sessionSequence,
            Map<SequencedRingBuffer<?>, JournalSource> journalSources) {
        if (listingCount == 1) {
            return null;
        }
        List<SequencedRingBuffer<Intent>> orders = new ArrayList<>(listingCount);
        List<SequencedRingBuffer<OrderExecutionReport>> execReports = new ArrayList<>(listingCount);
        for (int i = 0; i < listingCount; i++) {
            SequencedRingBuffer<Intent> order =
                    new SequencedRingBuffer<>(Intent::new, sessionSequence, OUTBOUND_BUFFER_SIZE);
            SequencedRingBuffer<OrderExecutionReport> execReport =
                    new SequencedRingBuffer<>(OrderExecutionReport::new, sessionSequence, OUTBOUND_BUFFER_SIZE);
            journalSources.put(order, JournalSource.ROUTED_ORDER);
            journalSources.put(execReport, JournalSource.ROUTED_EXEC_REPORT);
            orders.add(order);
            execReports.add(execReport);
        }
        return new RoutedBuffers(orders, execReports);
    }

    private record OutboundSetup(List<GnomeAgent> agents, ListingRouter router) {}

    private OutboundSetup setupOutbound(
            List<Listing> listings,
            List<SequencedRingBuffer<?>> perListingMdBuffers,
            SequencedRingBuffer<Intent> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> omsExecReportBuffer,
            RoutedBuffers routedBuffers,
            Map<Integer, VenueOrderQuery> venueQueries) {
        this.rateLimiters = new VenueRateLimiters(logger, SystemNanoClock.INSTANCE, SystemEpochClock.INSTANCE);
        List<GnomeAgent> agents = new ArrayList<>(listings.size());
        if (routedBuffers == null) {
            agents.add(createOutboundGateway(
                    listings.get(0),
                    perListingMdBuffers.get(0),
                    orderOutboundBuffer,
                    omsExecReportBuffer,
                    venueQueries,
                    rateLimiters));
            return new OutboundSetup(agents, null);
        }
        Map<Long, SequencedRingBuffer<?>> perListingOutBufs = new HashMap<>();
        List<SequencedRingBuffer<OrderExecutionReport>> perExchangeExecBufs = routedBuffers.execReports();
        for (int i = 0; i < listings.size(); i++) {
            Listing listing = listings.get(i);
            long routingKey = ListingRouter.routingKey(
                    listing.exchange().exchangeId(), listing.security().securityId());
            SequencedRingBuffer<Intent> perExchangeOutBuf =
                    routedBuffers.orders().get(i);
            SequencedRingBuffer<OrderExecutionReport> perExchangeExecBuf = perExchangeExecBufs.get(i);
            perListingOutBufs.put(routingKey, perExchangeOutBuf);
            agents.add(createOutboundGateway(
                    listing,
                    perListingMdBuffers.get(i),
                    perExchangeOutBuf,
                    perExchangeExecBuf,
                    venueQueries,
                    rateLimiters));
        }
        return new OutboundSetup(
                agents,
                new ListingRouter(orderOutboundBuffer, perListingOutBufs, perExchangeExecBufs, omsExecReportBuffer));
    }

    record AgentRunners(
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
            RiskEngine riskEngine,
            Logger logger,
            ErrorHandler errorHandler) {
        // Before anything that can send an order or report health: until the first refresh lands, the OMS refuses
        // every order as stale and the heartbeat reports risk stale, so a strategy started alongside it lost its first
        // order and a monitoring tick could catch the gap. A registry that never answers still lets the session start,
        // failing closed like any other stale spell.
        GnomeAgentRunner riskSyncRunner = new GnomeAgentRunner(riskSyncAgent, errorHandler);
        GnomeAgentRunner.startOnThread(riskSyncRunner);
        if (!riskEngine.awaitFirstRefresh()) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Starting with stale risk: the first policy refresh didn't succeed");
        }
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

    /**
     * The session's own agents end it on an error. The venue gateways' reader and supervisor have their own handler,
     * which reconnects first: a dropped socket is routine there.
     */
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
            Map<Integer, VenueOrderQuery> venueQueries,
            VenueRateLimiters rateLimiters) {
        Properties properties = getInstance(Properties.class);
        String mode = properties.getStringProperty("mode");

        if ("paper".equals(mode)) {
            // The session's config carries its simulation as one JSON value, in the shape a backtest config takes.
            SessionSimulationConfig simulation =
                    SessionSimulationConfig.fromJson(properties.getStringProperty("simulation.json"));
            SimulationResolver resolver = new SimulationResolver(getInstance(SecurityMaster.class), simulation.asOf);
            ExchangeSimulationConfig config = resolver.resolve(listing, false, simulation.layersFor(listing));
            logger.logf(
                    LogMessage.DEBUG,
                    "Listing %d simulates as of %s with %s",
                    listing.listingId(),
                    simulation.asOf,
                    config.toJson());
            long baseSeed = simulation.seed != null ? simulation.seed : PAPER_TRADING_DEFAULT_SEED;
            MbpSimulatedExchange exchange = (MbpSimulatedExchange) config.toSimulatedExchange(
                    listing.listingId(), LatencySeeds.derive(baseSeed, listing.listingId()), resolver.registryFees());
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
        final DefaultOutboundOrchestrator outboundOrch = createChildOrchestrator(
                orchClass,
                Map.of(
                        Listing.class,
                        listing,
                        VenueRateLimiters.class,
                        rateLimiters,
                        // The fees the venue charges on this listing, for fills it doesn't report a fee for.
                        FeeTimeline.class,
                        getInstance(SecurityMaster.class).getFeeTimeline(listing.listingId()),
                        VenueAccount.class,
                        sessionAccounts(properties)
                                .forExchange(listing.exchange().exchangeId()),
                        // Live trading needs a session, so it always has a fatal handler.
                        ProcessExit.class,
                        fatalHandler));
        venueQueries.put(listing.listingId(), outboundOrch.createVenueOrderQuery());
        final DefaultOutboundOrchestrator.OutboundAgents agents =
                outboundOrch.startGatewayAgents(orderOutboundBuffer, execReportBuffer);
        outboundGateways.add(agents.gateway());
        return agents.writer();
    }

    /**
     * The venue accounts the registry bound to this session when it was created, read once. A live session trades
     * only through them, so one without a session (a local run) can't trade live.
     */
    private SessionAccounts sessionAccounts(Properties properties) {
        if (this.sessionAccounts == null) {
            if (!properties.hasProperty("session.id")) {
                throw new IllegalStateException("Live trading needs a registry session to bind its venue accounts");
            }
            this.sessionAccounts = SessionAccounts.fetch(
                    getInstance(RegistryConnection.class), properties.getStringProperty("session.id"));
        }
        return this.sessionAccounts;
    }

    /** What ends the session when its strategy asks to exit. */
    private SessionExit sessionExit(Logger logger, Properties properties, int strategyId, List<Listing> listings) {
        return new SessionExit(
                logger,
                sessionReports,
                strategyId,
                properties.getStringProperty("mode"),
                listings,
                this::close,
                System::exit);
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
                // Otherwise the OMS fetches each spec history on its first order for the listing: a blocking registry
                // call on the OMS thread, through a client the strategy's thread also uses.
                securityMaster.getListingSpec(id);
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse listings property", e);
        }
    }
}
