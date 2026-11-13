package group.gnometrading.orchestrator.session;

import group.gnometrading.RegistryConnection;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.host.HostReadings;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.oms.OmsAgent;
import group.gnometrading.oms.ledger.LedgerAgent;
import group.gnometrading.oms.ledger.LedgerRing;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.RiskSyncAgent;
import group.gnometrading.orchestrator.marketdata.MarketData;
import group.gnometrading.orchestrator.runtime.ClockReadings;
import group.gnometrading.orchestrator.runtime.GatewayHealth;
import group.gnometrading.orchestrator.session.journal.SessionJournal;
import group.gnometrading.orchestrator.session.lifecycle.OrderGatewayLinkStatus;
import group.gnometrading.orchestrator.session.lifecycle.SessionHeartbeatAgent;
import group.gnometrading.orchestrator.session.rewards.RewardsAgent;
import group.gnometrading.strategies.StrategyAgent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.SystemEpochClock;

/**
 * The session's agents on their threads: the order they start in, the order they close in, and the heartbeat that
 * watches them.
 *
 * @param marketData the session's market data, started before the agents that read it
 * @param orderEntry the gateway client, which carries every listing's orders to its venue's order gateway
 * @param ledger null without a registry session
 * @param rewards null unless a paper session estimates venue rewards
 */
record SessionAgents(
        MarketData marketData,
        GnomeAgentRunner strategy,
        GnomeAgentRunner oms,
        GnomeAgentRunner orderEntry,
        GnomeAgentRunner ledger,
        GnomeAgentRunner riskSync,
        GnomeAgentRunner rewards) {

    private static final long HEARTBEAT_SAMPLE_INTERVAL_MS = 1_000;
    private static final long HEARTBEAT_POST_INTERVAL_MS = 5_000;
    // The request line and headers sent ahead of the heartbeat's body (host, API key, content type and length).
    private static final int HEARTBEAT_REQUEST_HEADER_BYTES = 4 * 1024;

    /**
     * Starts order entry ahead of everything else: a live session's gateway client must be welcomed before startup
     * recovery, which asks the venue through it. Nothing reaches it until the OMS starts.
     */
    static GnomeAgentRunner startOrderEntry(GnomeAgent client, ErrorHandler errorHandler) {
        return startOn(client, errorHandler);
    }

    /** @param orderEntry already started by {@link #startOrderEntry} */
    static SessionAgents start(
            MarketData marketData,
            OmsAgent omsAgent,
            GnomeAgentRunner orderEntry,
            StrategyAgent strategy,
            LedgerAgent ledgerAgent,
            RiskSyncAgent riskSyncAgent,
            RewardsAgent rewardsAgent,
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
        try {
            marketData.start(errorHandler);
        } catch (Exception e) {
            throw new IllegalStateException("Market data didn't start", e);
        }
        GnomeAgentRunner omsRunner = startOn(omsAgent, errorHandler);
        GnomeAgentRunner strategyRunner = startOn(strategy, errorHandler);
        GnomeAgentRunner ledgerRunner = ledgerAgent == null ? null : startOn(ledgerAgent, errorHandler);
        GnomeAgentRunner rewardsRunner = rewardsAgent == null ? null : startOn(rewardsAgent, errorHandler);
        return new SessionAgents(
                marketData, strategyRunner, omsRunner, orderEntry, ledgerRunner, riskSyncRunner, rewardsRunner);
    }

    private static GnomeAgentRunner startOn(GnomeAgent agent, ErrorHandler errorHandler) {
        GnomeAgentRunner runner = new GnomeAgentRunner(agent, errorHandler);
        GnomeAgentRunner.startOnThread(runner);
        return runner;
    }

    /**
     * Closes the session's agents in an order that lets each finish. A producer waits while the buffer it writes to is
     * full, and a closed consumer never makes room again, so nothing may still be producing into a buffer whose reader
     * has gone: the producer, and every close after it, would wait forever.
     */
    static void close(
            final SessionAgents started,
            final GnomeAgentRunner journalRunner,
            final GnomeAgentRunner heartbeatRunner,
            final Logger logger) {
        // Market data first, so none of it is still arriving once the strategy stops reading: closed after the
        // strategy, a busy stream filled the strategy's buffer while the OMS and the gateways closed, and its writer
        // then waited on it for good.
        closeQuietly(started.marketData(), "market data", logger);
        closeQuietly(started.strategy(), logger);
        closeQuietly(started.oms(), logger);
        closeQuietly(started.orderEntry(), logger);
        // After the OMS and gateways, so its final write holds everything they recorded while closing.
        closeQuietly(started.ledger(), logger);
        // It only reads the buffers, so it can close any time before the journal; its last post holds what the
        // session's orders earned until they closed.
        closeQuietly(started.rewards(), logger);
        closeQuietly(started.riskSync(), logger);
        // Last, so the journal holds everything the agents wrote while closing.
        closeQuietly(journalRunner, logger);
        // After everything else, so the session keeps reporting while it shuts down.
        closeQuietly(heartbeatRunner, logger);
    }

    /**
     * Starts the heartbeat over every agent and gateway the session runs.
     *
     * @param registry a registry writer connection whose client can hold a body of the given size
     */
    GnomeAgentRunner startHeartbeat(
            String sessionId,
            SessionJournal journal,
            LedgerAgent ledgerAgent,
            LedgerRing ledgerRing,
            SharedPriceBuffer priceBuffer,
            PriceSlotRegistry priceSlotRegistry,
            RiskEngine riskEngine,
            List<? extends OrderGatewayLinkStatus> orderGateways,
            IntFunction<RegistryConnection> registry,
            Logger logger,
            ErrorHandler errorHandler) {
        List<GatewayHealth> gateways = new ArrayList<>(marketData.health());
        List<GnomeAgentRunner> watched = new ArrayList<>();
        // A stuck journal holds up market data and every trading buffer once their rings fill.
        for (GnomeAgentRunner runner :
                new GnomeAgentRunner[] {strategy, oms, orderEntry, ledger, riskSync, rewards, journal.runner()}) {
            if (runner != null) {
                watched.add(runner);
            }
        }
        int bodyCapacity =
                SessionHeartbeatAgent.bodyCapacity(sessionId, watched, gateways, orderGateways, priceSlotRegistry);
        GnomeAgentRunner runner = new GnomeAgentRunner(
                new SessionHeartbeatAgent(
                        sessionId,
                        registry.apply(bodyCapacity + HEARTBEAT_REQUEST_HEADER_BYTES),
                        SystemEpochClock.INSTANCE,
                        logger,
                        watched,
                        gateways,
                        orderGateways,
                        priceBuffer,
                        priceSlotRegistry,
                        ledgerAgent,
                        ledgerRing,
                        riskEngine,
                        HostReadings.ofThisProcess(
                                journal.directory() != null
                                        ? journal.directory().toFile()
                                        : new File("/")),
                        ClockReadings.ofThisHost(SystemEpochClock.INSTANCE),
                        journal.sealer(),
                        HEARTBEAT_SAMPLE_INTERVAL_MS,
                        HEARTBEAT_POST_INTERVAL_MS),
                errorHandler);
        GnomeAgentRunner.startOnThread(runner);
        return runner;
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
}
