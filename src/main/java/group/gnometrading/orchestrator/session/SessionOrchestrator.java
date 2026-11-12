package group.gnometrading.orchestrator.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.logging.AsyncLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.md.StreamDirectory;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPClientBuilder;
import group.gnometrading.networking.http.RetryableHTTPClient;
import group.gnometrading.oms.OmsAgent;
import group.gnometrading.oms.OrderManagementSystem;
import group.gnometrading.oms.ledger.LedgerAgent;
import group.gnometrading.oms.ledger.LedgerRing;
import group.gnometrading.oms.ledger.LedgerSink;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.PriceWriter;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.PositionView;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.ListingDirectory;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.RiskSyncAgent;
import group.gnometrading.oms.state.PooledOrderStateManager;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aws.AwsModule;
import group.gnometrading.orchestrator.aws.RegistryEndpoint;
import group.gnometrading.orchestrator.aws.RiskModule;
import group.gnometrading.orchestrator.di.Orchestrator;
import group.gnometrading.orchestrator.di.Provides;
import group.gnometrading.orchestrator.di.Singleton;
import group.gnometrading.orchestrator.marketdata.ShardMarketData;
import group.gnometrading.orchestrator.runtime.AgentRuntimeInstaller;
import group.gnometrading.orchestrator.session.journal.SessionJournal;
import group.gnometrading.orchestrator.session.lifecycle.SessionExit;
import group.gnometrading.orchestrator.session.lifecycle.SessionFatalHandler;
import group.gnometrading.orchestrator.session.lifecycle.SessionReports;
import group.gnometrading.orchestrator.session.lifecycle.SessionStatusReporter;
import group.gnometrading.orchestrator.session.marketdata.SessionMarketData;
import group.gnometrading.orchestrator.session.orders.SessionOrders;
import group.gnometrading.orchestrator.session.recovery.SessionNotRunnableException;
import group.gnometrading.orchestrator.session.recovery.StartupRecovery;
import group.gnometrading.resources.Properties;
import group.gnometrading.sequencer.metrics.JournaledMetricRecorder;
import group.gnometrading.sm.Listing;
import group.gnometrading.strategies.StrategyAgent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;

/**
 * A live or paper trading session: its market data from shards, the OMS, the strategy and each listing's order entry,
 * wired over sequenced ring buffers on one session sequence that the journal records.
 *
 * <ul>
 *   <li>{@code mode}: {@code paper} trades through each venue's paper gateway, which simulates it; {@code live}
 *       through the venue's order gateway on the account the registry bound to the session.
 *   <li>{@code strategy.type} and {@code strategy.class}: see {@link SessionStrategy}.
 *   <li>{@code strategy.id}: the registry strategy, which scopes PnL and risk.
 *   <li>{@code listings}: the traded listing ids (JSON array, e.g. {@code [1,2,3]}).
 * </ul>
 *
 * <p>Market data arrives in the one buffer the strategy reads ({@link SessionMarketData}); order entry is
 * {@link SessionOrders}, with a router when there are several listings; the agents' start and close order is
 * {@link SessionAgents}.
 */
public class SessionOrchestrator extends Orchestrator {

    private static final int LEDGER_CONNECT_TIMEOUT_MS = 1_000;
    private static final int LEDGER_RESPONSE_TIMEOUT_MS = 2_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Set once configure() has started them; read by onClose(), which may run on a shutdown hook thread.
    private volatile SessionAgents agents;
    private volatile SessionOrders orders;
    private volatile AeronRuntime aeron;
    private volatile SessionJournal journal = SessionJournal.OFF;
    private volatile GnomeAgentRunner heartbeatRunner;
    // Order entry, started ahead of the other agents; closed on its own if the session fails before they start.
    private volatile GnomeAgentRunner orderEntry;
    private volatile Logger logger;
    // A session's way out on an error; null for a local run, which throws instead.
    private volatile SessionFatalHandler fatalHandler;
    // Null outside a registry session (a local run), which then exits without reporting anything.
    private SessionReports sessionReports;

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
        EpochNanoClock nanoClock = getInstance(EpochNanoClock.class);
        int strategyId = properties.getIntProperty("strategy.id");
        List<Listing> listings = resolveListings(properties, securityMaster);
        int[] dataStreams = SessionMarketData.dataStreams(properties);
        StreamDirectory streams = SessionMarketData.streams(securityMaster, listings, dataStreams);
        ListingDirectory listingDirectory = RiskModule.directory(listings);
        RiskEngine riskEngine = RiskModule.riskEngine(properties, listingDirectory);
        AgentRuntimeInstaller.install(properties, logger);

        SessionBuffers buffers = SessionBuffers.create();
        ShardMarketData marketData = startAeron(logger, properties, listings, streams, dataStreams, buffers);

        DefaultPositionTracker positionTracker = new DefaultPositionTracker(new SharedPositionBuffer(64));
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
                nanoClock);
        for (Listing listing : listings) {
            positionTracker.registerSlot(strategyId, listing.listingId());
        }
        PositionView positionView = positionTracker.createPositionView(strategyId);

        PriceWriter priceWriter = new PriceWriter(priceBuffer, priceSlotRegistry, streams, buffers.marketData());

        EpochClock epochClock = SystemEpochClock.INSTANCE;
        ErrorHandler errorHandler = fatalHandler != null ? fatalHandler.errorHandler() : sessionErrorHandler(logger);
        this.journal = SessionJournal.start(
                sessionId, buffers.sequence(), buffers.journalSources(), errorHandler, logger, properties);

        Map<Integer, VenueOrderQuery> venueQueries = new HashMap<>();
        this.orders = new SessionOrders(
                logger, properties, nanoClock, () -> getInstance(RegistryConnection.class), this.aeron);
        GnomeAgent client = orders.wire(listings, buffers.orders(), buffers.omsExecReports(), venueQueries);
        // Before recovery: a live session asks the venue through its gateways, which must have welcomed it first.
        GnomeAgentRunner orderEntry = SessionAgents.startOrderEntry(client, errorHandler);
        this.orderEntry = orderEntry;
        awaitGateways(properties);

        OmsAgent omsAgent = new OmsAgent(
                oms,
                buffers.intents(),
                buffers.omsExecReports(),
                buffers.orders(),
                buffers.strategyExecReports(),
                priceWriter,
                nanoClock);
        StrategyAgent strategy = SessionStrategy.create(
                properties,
                strategyId,
                buffers.marketData(),
                buffers.strategyExecReports(),
                buffers.intents(),
                positionView,
                securityMaster);
        strategy.configureStreams(streams);
        strategy.configureExit(listings, sessionExit(logger, properties, strategyId, listings));
        // The journal is already draining this buffer, so declaring streams here can't wait on a full ring.
        strategy.configureMetrics(new JournaledMetricRecorder(buffers.metrics()));

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

        SessionAgents started = SessionAgents.start(
                marketData,
                omsAgent,
                orderEntry,
                strategy,
                ledgerAgent,
                riskSyncAgent,
                riskEngine,
                logger,
                errorHandler);
        this.agents = started;
        // Only a session reports to the registry; a local run has nothing to report to.
        if (sessionId != null) {
            this.heartbeatRunner = started.startHeartbeat(
                    sessionId,
                    journal,
                    ledgerAgent,
                    ledgerRing,
                    priceBuffer,
                    priceSlotRegistry,
                    riskEngine,
                    this.orders == null ? List.of() : this.orders.gatewayLinks(),
                    bodyBytes -> registryWriterConnection(registryWriterClient().withWriteBufferSize(bodyBytes)),
                    logger,
                    errorHandler);
        }
        // A fallback for plain Java runs; an embedding process closes first, which makes this a no-op.
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "orchestrator-shutdown"));
        reportRunning(sessionId, logger);
    }

    // Both lines are the only evidence an operator stop shut down cleanly rather than being cut off at the timeout.
    /** The session's Aeron runtime, shared by its market data and its order-gateway link, and its market data on it. */
    private ShardMarketData startAeron(
            Logger logger,
            Properties properties,
            List<Listing> listings,
            StreamDirectory streams,
            int[] dataStreams,
            SessionBuffers buffers) {
        this.aeron = SessionMarketData.aeron(logger, properties);
        return SessionMarketData.create(
                logger, properties, this.aeron, listings, streams, dataStreams, buffers.marketData());
    }

    /** Until every order gateway the session trades through has welcomed it; none without gateways. */
    private void awaitGateways(Properties properties) {
        try {
            orders.awaitGateways(
                    TimeUnit.SECONDS.toNanos(properties.getLongProperty("order.gateway.start.timeout.seconds")));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the order gateways", e);
        }
    }

    @Override
    protected final void onClose() {
        SessionFatalHandler fatal = this.fatalHandler;
        if (fatal != null) {
            fatal.markShuttingDown();
        }
        SessionAgents started = this.agents;
        if (started == null) {
            closeOrderEntry();
            return;
        }
        logger.logf(LogMessage.DEBUG, "Shutting down: closing agents");
        final long start = System.nanoTime();
        SessionAgents.close(started, journal.runner(), heartbeatRunner, logger);
        try {
            orders.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Last: the gateway link's cancels on shutdown go over it after market data has closed.
        this.aeron.close();
        logger.logf(LogMessage.DEBUG, "Agents closed in %d ms", (System.nanoTime() - start) / 1_000_000);
    }

    /** A session that failed before its agents started: order entry may already be running, and its links open. */
    private void closeOrderEntry() {
        GnomeAgentRunner runner = this.orderEntry;
        if (runner != null) {
            try {
                runner.close();
            } catch (Exception e) {
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Closing %s: %s",
                        runner.getAgent().roleName(),
                        e);
            }
        }
        if (orders != null) {
            try {
                orders.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
                registryWriterConnection(registryWriterClient()),
                epochClock,
                logger,
                sessionId,
                priceBuffer,
                priceSlotRegistry,
                properties.getLongProperty("ledger.flush.interval.ms"),
                properties.getLongProperty("ledger.mark.interval.ms"));
    }

    /**
     * A background writer's own connection (the ledger's, the heartbeat's): the registry client isn't thread-safe,
     * and a write must fail fast and be retried on the agent's schedule rather than block its thread in the client's
     * own retries.
     */
    private RegistryConnection registryWriterConnection(HTTPClientBuilder httpClient) {
        RegistryEndpoint endpoint = getInstance(RegistryEndpoint.class);
        return new RegistryConnection(
                endpoint.host(),
                endpoint.apiKey(),
                RetryableHTTPClient.builder()
                        .withMaxRetries(0)
                        .withHttpClient(httpClient)
                        .build());
    }

    private static HTTPClientBuilder registryWriterClient() {
        return HTTPClient.builder()
                .withConnectTimeout(LEDGER_CONNECT_TIMEOUT_MS)
                .withResponseTimeout(LEDGER_RESPONSE_TIMEOUT_MS);
    }

    private void reportRunning(String sessionId, Logger logger) {
        if (sessionId == null) {
            return;
        }
        RegistryEndpoint registry = getInstance(RegistryEndpoint.class);
        new SessionStatusReporter(URI.create("https://" + registry.host()), registry.apiKey(), logger)
                .reportRunning(sessionId);
    }

    /**
     * The session's own agents end it on an error. The venue gateways' reader and supervisor have their own handler,
     * which reconnects first: a dropped socket is routine there.
     */
    private static ErrorHandler sessionErrorHandler(final Logger logger) {
        final AtomicBoolean exiting = new AtomicBoolean();
        return error -> {
            logger.logf(LogMessage.FATAL_ERROR_EXITING, "Agent error: %s", error);
            // Off the agent's thread, which the shutdown hook joins: it would never be joined if it waited in exit.
            if (exiting.compareAndSet(false, true)) {
                new Thread(() -> System.exit(1), "session-exit-on-error").start();
            }
        };
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
