package group.gnometrading.trading;

import group.gnometrading.RegistryConnection;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.gateways.GatewayRunners;
import group.gnometrading.gateways.ratelimit.VenueRateLimiter;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.oms.ledger.LedgerAgent;
import group.gnometrading.oms.ledger.LedgerRing;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.ViewString;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.agrona.concurrent.EpochClock;

/**
 * Tells the registry, every few seconds, that this session is alive and how each of its parts is doing, so the
 * controller can tell a quiet session from a dead one: the ledger only writes when something trades or a price
 * moves, which in a quiet market can be minutes apart.
 *
 * <p>It samples once a second and reports raw readings, leaving it to the reader to judge them:
 * <ul>
 *   <li>each agent's time since its work loop last advanced (a stuck agent stops advancing; an idle one doesn't);
 *   <li>each listing's time since its price last changed (a quiet market, or a dead feed);
 *   <li>the ledger's last stored write, failures since, and whether it was fenced;
 *   <li>each gateway's reader, whether it is held off its socket while reconnecting, and how often its book was found
 *       wrong and resynced in the last five minutes;
 *   <li>whether risk can be trusted: stale (the registry unreachable), unguarded (a required policy missing), policies
 *       that couldn't be built, and halts the OMS couldn't record;
 *   <li>each venue account's rate limiter, and whether it is refusing requests.
 * </ul>
 *
 * <p>Reads only what the agents already publish for other threads, so it costs their threads nothing. Nothing here
 * may stop the process: a failed post is retried at the next interval, and once the registry says the session has
 * ended it stops sending.
 */
public final class SessionHeartbeatAgent implements GnomeAgent {

    private static final GnomeString PATH = new ViewString("/api/strategy-sessions/heartbeat");
    private static final int BODY_CAPACITY = 16 * 1024;
    private static final long MAX_PARK_MS = 100;
    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_CONFLICT = 409;
    private static final long RESYNC_WINDOW_MS = 5 * 60_000;

    private final String sessionId;
    private final RegistryConnection registry;
    private final EpochClock clock;
    private final Logger logger;
    private final long sampleIntervalMs;
    private final long postIntervalMs;

    private final GnomeAgentRunner[] agents;
    private final String[] agentNames;
    private final boolean[] hotPath;
    private final long[] lastCycles;
    private final long[] advancedAtMs;

    private final SharedPriceBuffer prices;
    private final PriceSlotRegistry priceSlots;
    private final long[] lastPriceVersions;
    private final long[] priceChangedAtMs;

    private final GatewayRunners[] gateways;
    private final String[] gatewayNames;
    // Each gateway's resync count at each of the last five minutes' posts, oldest at resyncSlot.
    private final long[][] resyncHistory;
    private int resyncSlot;

    private final RiskEngine riskEngine;
    private final String[] limiterNames;
    private final VenueRateLimiter[] limiters;

    private final LedgerAgent ledger;
    private final LedgerRing ledgerRing;

    private final ByteBuffer body = ByteBuffer.allocate(BODY_CAPACITY);
    private final JsonEncoder json = new JsonEncoder();

    private long startedAtMs;
    private long nextSampleMs;
    private long nextPostMs;
    private boolean failing;
    private boolean ended;

    public SessionHeartbeatAgent(
            final String sessionId,
            final RegistryConnection registry,
            final EpochClock clock,
            final Logger logger,
            final List<GnomeAgentRunner> agents,
            final List<GatewayRunners> gateways,
            final SharedPriceBuffer prices,
            final PriceSlotRegistry priceSlots,
            final LedgerAgent ledger,
            final LedgerRing ledgerRing,
            final RiskEngine riskEngine,
            final Map<String, VenueRateLimiter> rateLimiters,
            final long sampleIntervalMs,
            final long postIntervalMs) {
        this.sessionId = sessionId;
        this.registry = registry;
        this.clock = clock;
        this.logger = logger;
        this.sampleIntervalMs = sampleIntervalMs;
        this.postIntervalMs = postIntervalMs;
        this.agents = agents.toArray(new GnomeAgentRunner[0]);
        this.agentNames = new String[this.agents.length];
        this.hotPath = new boolean[this.agents.length];
        for (int i = 0; i < this.agents.length; i++) {
            this.agentNames[i] = this.agents[i].getAgent().roleName();
            this.hotPath[i] = this.agents[i].getAgent().threadProfile() == ThreadProfile.HOT_PATH;
        }
        this.lastCycles = new long[this.agents.length];
        this.advancedAtMs = new long[this.agents.length];
        this.prices = prices;
        this.priceSlots = priceSlots;
        this.lastPriceVersions = new long[priceSlots.count()];
        this.priceChangedAtMs = new long[priceSlots.count()];
        this.gateways = gateways.toArray(new GatewayRunners[0]);
        this.gatewayNames = new String[this.gateways.length];
        for (int i = 0; i < this.gateways.length; i++) {
            this.gatewayNames[i] = this.gateways[i].name();
        }
        this.resyncHistory = new long[this.gateways.length][(int) Math.max(1, RESYNC_WINDOW_MS / postIntervalMs)];
        this.ledger = ledger;
        this.ledgerRing = ledgerRing;
        this.riskEngine = riskEngine;
        this.limiterNames = rateLimiters.keySet().toArray(new String[0]);
        this.limiters = rateLimiters.values().toArray(new VenueRateLimiter[0]);
    }

    @Override
    public void onStart() {
        final long now = clock.time();
        startedAtMs = now;
        for (int i = 0; i < agents.length; i++) {
            lastCycles[i] = agents[i].cycles();
            advancedAtMs[i] = now;
        }
        for (int slot = 0; slot < lastPriceVersions.length; slot++) {
            lastPriceVersions[slot] = prices.slotVersion(slot);
            priceChangedAtMs[slot] = now;
        }
        nextSampleMs = now + sampleIntervalMs;
        nextPostMs = now;
    }

    @Override
    public int doWork() {
        try {
            if (ended) {
                park(MAX_PARK_MS);
                return 0;
            }
            final long now = clock.time();
            int work = 0;
            if (now >= nextSampleMs) {
                nextSampleMs = now + sampleIntervalMs;
                sample(now);
                work++;
            }
            if (now >= nextPostMs) {
                nextPostMs = now + postIntervalMs;
                post(now);
                work++;
            }
            if (work == 0) {
                park(Math.min(MAX_PARK_MS, Math.max(1, Math.min(nextSampleMs, nextPostMs) - now)));
            }
            return work;
        } catch (Throwable error) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Session heartbeat error: %s", error);
            return 0;
        }
    }

    private void sample(final long now) {
        for (int i = 0; i < agents.length; i++) {
            final long cycles = agents[i].cycles();
            if (cycles != lastCycles[i]) {
                lastCycles[i] = cycles;
                advancedAtMs[i] = now;
            }
        }
        for (int slot = 0; slot < lastPriceVersions.length; slot++) {
            final long version = prices.slotVersion(slot);
            if (version != lastPriceVersions[slot]) {
                lastPriceVersions[slot] = version;
                priceChangedAtMs[slot] = now;
            }
        }
    }

    private void post(final long now) {
        encode(now);
        final int status = registry.tryPost(PATH, body.array(), body.position());
        if (status == HTTP_OK) {
            if (failing) {
                logger.logf(LogMessage.DEBUG, "Session heartbeat restored");
            }
            failing = false;
        } else if (status == HTTP_CONFLICT || status == HTTP_NOT_FOUND) {
            // The session has ended (or never existed): a heartbeat could only make it look alive.
            logger.logf(LogMessage.DEBUG, "Session heartbeat stopped: the registry answered %d", status);
            ended = true;
        } else if (!failing) {
            // Once per outage: the controller shows the silence on its own.
            logger.logf(LogMessage.UNKNOWN_ERROR, "Session heartbeat failed with status %d", status);
            failing = true;
        }
    }

    private void encode(final long now) {
        body.clear();
        json.wrap(body);
        json.writeObjectStart()
                .writeObjectEntry("sessionId", sessionId)
                .writeComma()
                .writeObjectEntry("uptimeMs", now - startedAtMs)
                .writeComma();
        writeAgents(now);
        json.writeComma();
        writeListings(now);
        json.writeComma();
        writeLedger(now);
        json.writeComma();
        writeGateways();
        json.writeComma();
        writeRisk(now);
        json.writeComma();
        writeRateLimits();
        json.writeObjectEnd();
    }

    private void writeAgents(final long now) {
        json.writeString("agents").writeColon().writeArrayStart();
        for (int i = 0; i < agents.length; i++) {
            if (i > 0) {
                json.writeComma();
            }
            json.writeObjectStart()
                    .writeObjectEntry("name", agentNames[i])
                    .writeComma()
                    .writeObjectEntry("hotPath", hotPath[i])
                    .writeComma()
                    .writeObjectEntry("stalledMs", now - advancedAtMs[i])
                    .writeComma()
                    .writeObjectEntry("exited", agents[i].isClosed())
                    .writeObjectEnd();
        }
        json.writeArrayEnd();
    }

    private void writeListings(final long now) {
        json.writeString("listings").writeColon().writeArrayStart();
        for (int slot = 0; slot < lastPriceVersions.length; slot++) {
            if (slot > 0) {
                json.writeComma();
            }
            json.writeObjectStart()
                    .writeObjectEntry("listingId", priceSlots.listingId(slot))
                    .writeComma()
                    .writeObjectEntry("priceUnchangedMs", now - priceChangedAtMs[slot])
                    .writeObjectEnd();
        }
        json.writeArrayEnd();
    }

    private void writeLedger(final long now) {
        json.writeString("ledger").writeColon();
        if (ledger == null) {
            json.writeNull();
            return;
        }
        final long lastAccepted = ledger.lastAcceptedMs();
        json.writeObjectStart().writeString("lastAcceptedAgoMs").writeColon();
        if (lastAccepted == 0) {
            json.writeNull();
        } else {
            json.writeNumber(now - lastAccepted);
        }
        json.writeComma()
                .writeObjectEntry("consecutiveFailures", ledger.consecutiveFailures())
                .writeComma()
                .writeObjectEntry("fenced", ledgerRing.isFenced())
                .writeObjectEnd();
    }

    private void writeGateways() {
        json.writeString("gateways").writeColon().writeArrayStart();
        for (int i = 0; i < gateways.length; i++) {
            if (i > 0) {
                json.writeComma();
            }
            json.writeObjectStart()
                    .writeObjectEntry("name", gatewayNames[i])
                    .writeComma()
                    .writeObjectEntry("reconnecting", gateways[i].isReconnecting())
                    .writeComma()
                    .writeObjectEntry("resyncsLast5m", resyncsInWindow(i))
                    .writeObjectEnd();
        }
        json.writeArrayEnd();
        resyncSlot = (resyncSlot + 1) % (gateways.length == 0 ? 1 : resyncHistory[0].length);
    }

    // Resyncs since the post five minutes ago; this post's count then takes that oldest slot.
    private long resyncsInWindow(final int gateway) {
        final long current = gateways[gateway].bookResyncs();
        final long oldest = resyncHistory[gateway][resyncSlot];
        resyncHistory[gateway][resyncSlot] = current;
        return current - oldest;
    }

    private void writeRisk(final long now) {
        json.writeString("risk")
                .writeColon()
                .writeObjectStart()
                .writeObjectEntry("stale", riskEngine.isStale(now))
                .writeComma()
                .writeObjectEntry("unguarded", riskEngine.isUnguarded())
                .writeComma()
                .writeObjectEntry("unbuildablePolicies", riskEngine.unbuildablePolicies())
                .writeComma()
                .writeObjectEntry("droppedLatches", riskEngine.droppedLatches())
                .writeObjectEnd();
    }

    private void writeRateLimits() {
        json.writeString("rateLimit").writeColon().writeArrayStart();
        for (int i = 0; i < limiters.length; i++) {
            if (i > 0) {
                json.writeComma();
            }
            json.writeObjectStart()
                    .writeObjectEntry("account", limiterNames[i])
                    .writeComma()
                    .writeObjectEntry("throttling", limiters[i].isThrottling())
                    .writeObjectEnd();
        }
        json.writeArrayEnd();
    }

    private static void park(final long millis) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}
