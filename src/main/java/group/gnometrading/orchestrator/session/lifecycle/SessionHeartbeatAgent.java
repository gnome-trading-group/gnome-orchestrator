package group.gnometrading.orchestrator.session.lifecycle;

import group.gnometrading.RegistryConnection;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.host.HostReadings;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.oms.ledger.LedgerAgent;
import group.gnometrading.oms.ledger.LedgerRing;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.orchestrator.runtime.ClockReadings;
import group.gnometrading.orchestrator.runtime.GatewayHealth;
import group.gnometrading.orchestrator.session.journal.JournalSegmentSealer;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.ViewString;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongPredicate;
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
 *   <li>a live session's link to each venue's order gateway: whether it is linked, how long it hasn't been, and how
 *       many of its orders work on that venue meanwhile;
 *   <li>whether risk can be trusted: stale (the registry unreachable), unguarded (a required policy missing), policies
 *       that couldn't be built, and halts the OMS couldn't record;
 *   <li>the host's CPU, memory and disk, and this process's memory;
 *   <li>how many journal segments are waiting for the shipper to upload them;
 * </ul>
 *
 * <p>It also carries the session's samples ({@link SampleBuffer}): readings over time that the registry stores apart
 * from the health, each sent until a post carrying it is accepted. The heartbeat thread takes them: each sampler when
 * it is due (the runtime, the agents' counters), and each recording thread's latency ({@link LatencyReport}) once a
 * minute closes. Samples go last in the body, in whatever room the registry's limit leaves.
 *
 * <p>Reads only what the agents already publish for other threads, so it costs their threads nothing. Nothing here
 * may stop the process: a failed post is retried at the next interval, and once the registry says the session has
 * ended it stops sending.
 */
public final class SessionHeartbeatAgent implements GnomeAgent {

    private static final GnomeString PATH = new ViewString("/api/strategy-sessions/heartbeat");
    // Long.MIN_VALUE, the longest number a field can hold.
    private static final int MAX_NUMBER_BYTES = 20;
    // The sections that don't grow with the session (ledger, risk, host, journal and the top-level keys) are well
    // under this; the rest is counted per entry below.
    private static final int FIXED_SECTIONS_BYTES = 2 * 1024;
    // The registry refuses a heartbeat past 64 KB; this leaves room for the request's own framing.
    static final int MAX_BODY_BYTES = 60 * 1024;
    // Room kept after the samples for the closing bracket and brace.
    private static final int CLOSING_BYTES = 2;
    private static final int MAX_SAMPLES_PER_POST = 300;
    private static final int AGENT_ENTRY_BYTES =
            "{\"name\":\"\",\"hotPath\":false,\"stalledMs\":,\"exited\":false},".length() + MAX_NUMBER_BYTES;
    private static final int LISTING_ENTRY_BYTES =
            "{\"listingId\":,\"priceUnchangedMs\":,\"marketDataBadMs\":},".length() + 3 * MAX_NUMBER_BYTES;
    private static final int GATEWAY_ENTRY_BYTES =
            "{\"name\":\"\",\"reconnecting\":false,\"resyncsLast5m\":},".length() + MAX_NUMBER_BYTES;
    private static final int ORDER_GATEWAY_ENTRY_BYTES =
            "{\"key\":\"\",\"linked\":false,\"unlinkedMs\":,\"workingOrders\":},".length() + 2 * MAX_NUMBER_BYTES;
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

    private final GatewayHealth[] gateways;
    private final String[] gatewayNames;
    // Each gateway's resync count at each of the last five minutes' posts, oldest at resyncSlot.
    private final long[][] resyncHistory;
    private int resyncSlot;

    private final OrderGatewayLinkStatus[] orderGateways;
    // When each link was last seen unlinked after being linked (or since start); 0 while linked.
    private final long[] unlinkedSinceMs;

    private final RiskEngine riskEngine;

    private final LedgerAgent ledger;
    private final LedgerRing ledgerRing;

    private final HostReadings host;
    private final ClockReadings hostClock;
    private final JournalSegmentSealer journal;
    private final LatencyReport latency;
    // Each takes its sample when due at the time given; true if it did.
    private final LongPredicate[] samplers;
    private final SampleBuffer samples;
    // Samples the last body carried, acknowledged when the registry accepts it.
    private int samplesInBody;

    private final ByteBuffer body;
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
            final List<GatewayHealth> gateways,
            final List<? extends OrderGatewayLinkStatus> orderGateways,
            final SharedPriceBuffer prices,
            final PriceSlotRegistry priceSlots,
            final LedgerAgent ledger,
            final LedgerRing ledgerRing,
            final RiskEngine riskEngine,
            final HostReadings host,
            final ClockReadings hostClock,
            final JournalSegmentSealer journal,
            final SampleBuffer samples,
            final List<LongPredicate> samplers,
            final LatencyReport latency,
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
        this.gateways = gateways.toArray(new GatewayHealth[0]);
        this.gatewayNames = new String[this.gateways.length];
        for (int i = 0; i < this.gateways.length; i++) {
            this.gatewayNames[i] = this.gateways[i].name();
        }
        this.orderGateways = orderGateways.toArray(new OrderGatewayLinkStatus[0]);
        this.unlinkedSinceMs = new long[this.orderGateways.length];
        this.resyncHistory = new long[this.gateways.length][(int) Math.max(1, RESYNC_WINDOW_MS / postIntervalMs)];
        this.ledger = ledger;
        this.ledgerRing = ledgerRing;
        this.riskEngine = riskEngine;
        this.host = host;
        this.hostClock = hostClock;
        this.journal = journal;
        this.latency = latency;
        this.samplers = samplers.toArray(new LongPredicate[0]);
        this.samples = samples;
        this.body = ByteBuffer.allocate(bodyCapacity(sessionId, agents, gateways, orderGateways, priceSlots));
    }

    /**
     * The most bytes a heartbeat for these parts can take, so the body (and the connection that sends it) can be sized
     * once up front: every listing, gateway and agent adds an entry, so a fixed size overflows on a large session.
     */
    public static int bodyCapacity(
            final String sessionId,
            final List<GnomeAgentRunner> agents,
            final List<GatewayHealth> gateways,
            final List<? extends OrderGatewayLinkStatus> orderGateways,
            final PriceSlotRegistry priceSlots) {
        // Samples fill what the registry's limit leaves after the health.
        int capacity = FIXED_SECTIONS_BYTES + escapedBytes(sessionId) + MAX_BODY_BYTES;
        for (OrderGatewayLinkStatus link : orderGateways) {
            capacity += ORDER_GATEWAY_ENTRY_BYTES + escapedBytes(link.gatewayKey());
        }
        for (GnomeAgentRunner agent : agents) {
            capacity += AGENT_ENTRY_BYTES + escapedBytes(agent.getAgent().roleName());
        }
        capacity += priceSlots.count() * LISTING_ENTRY_BYTES;
        for (GatewayHealth gateway : gateways) {
            capacity += GATEWAY_ENTRY_BYTES + escapedBytes(gateway.name());
        }
        return capacity;
    }

    // The encoder writes one byte per char, and a quote or backslash takes two.
    private static int escapedBytes(final String value) {
        return 2 * value.length();
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
        for (int i = 0; i < orderGateways.length; i++) {
            unlinkedSinceMs[i] = orderGateways[i].linked() ? 0 : now;
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
        for (int i = 0; i < orderGateways.length; i++) {
            if (orderGateways[i].linked()) {
                unlinkedSinceMs[i] = 0;
            } else if (unlinkedSinceMs[i] == 0) {
                unlinkedSinceMs[i] = now;
            }
        }
        if (latency != null) {
            latency.sample();
        }
        for (final LongPredicate sampler : samplers) {
            sampler.test(now);
        }
    }

    private void post(final long now) {
        encode(now);
        final int status = registry.tryPost(PATH, body.array(), body.position());
        if (status == HTTP_OK) {
            samples.acknowledge(samplesInBody);
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

    /** Writes the heartbeat into the body; package-private so a test can check it allocates nothing. */
    void encode(final long now) {
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
        writeOrderGateways(now);
        json.writeComma();
        writeRisk(now);
        json.writeComma();
        writeHost();
        json.writeComma();
        writeClock();
        json.writeComma();
        writeJournal(now);
        json.writeComma();
        writeSamples();
        json.writeObjectEnd();
    }

    private void writeHost() {
        host.sample();
        json.writeString("host")
                .writeColon()
                .writeObjectStart()
                .writeObjectEntry("cpuBusyPermille", host.cpuBusyPermille())
                .writeComma()
                .writeObjectEntry("cpuStealPermille", host.cpuStealPermille())
                .writeComma()
                .writeObjectEntry("memAvailableBytes", host.memAvailableBytes())
                .writeComma()
                .writeObjectEntry("memTotalBytes", host.memTotalBytes())
                .writeComma()
                .writeObjectEntry("processRssBytes", host.processRssBytes())
                .writeComma()
                .writeObjectEntry("heapUsedBytes", host.heapUsedBytes())
                .writeComma()
                .writeObjectEntry("heapMaxBytes", host.heapMaxBytes())
                .writeComma()
                .writeObjectEntry("diskFreeBytes", host.diskFreeBytes())
                .writeObjectEnd();
    }

    private void writeJournal(final long now) {
        json.writeString("journal").writeColon();
        if (journal == null) {
            json.writeNull();
            return;
        }
        journal.measureBacklog(now);
        json.writeObjectStart()
                .writeObjectEntry("pendingSegments", journal.pendingSegments())
                .writeComma()
                .writeObjectEntry("oldestPendingMs", journal.oldestPendingMs())
                .writeComma()
                .writeObjectEntry("segmentBytes", journal.segmentBytes())
                .writeObjectEnd();
    }

    // What the buffer holds and has lost goes in the health; the samples themselves, which the registry stores apart,
    // last, as many as fit under its limit.
    private void writeSamples() {
        json.writeString("sampleBuffer")
                .writeColon()
                .writeObjectStart()
                .writeObjectEntry("pending", samples.pending())
                .writeComma()
                .writeObjectEntry("dropped", samples.dropped())
                .writeObjectEnd()
                .writeComma()
                .writeString("samples")
                .writeColon()
                .writeArrayStart();
        final int limit = body.limit();
        body.limit(Math.max(body.position(), Math.min(limit, MAX_BODY_BYTES - CLOSING_BYTES)));
        // A sample larger than the room this health leaves would sit at the head and hold up every one behind it.
        samples.dropLargerThan(body.remaining());
        samplesInBody = samples.writeTo(body, MAX_SAMPLES_PER_POST);
        body.limit(limit);
        json.writeArrayEnd();
    }

    @Override
    public void onClose() {
        host.close();
    }

    /** The last heartbeat written, for tests. */
    String lastBody() {
        return new String(body.array(), 0, body.position(), StandardCharsets.UTF_8);
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

    private void writeOrderGateways(final long now) {
        json.writeString("orderGateways").writeColon().writeArrayStart();
        for (int i = 0; i < orderGateways.length; i++) {
            if (i > 0) {
                json.writeComma();
            }
            final boolean linked = orderGateways[i].linked();
            json.writeObjectStart()
                    .writeObjectEntry("key", orderGateways[i].gatewayKey())
                    .writeComma()
                    .writeObjectEntry("linked", linked)
                    .writeComma()
                    .writeObjectEntry("unlinkedMs", linked || unlinkedSinceMs[i] == 0 ? 0 : now - unlinkedSinceMs[i])
                    .writeComma()
                    .writeObjectEntry("workingOrders", orderGateways[i].workingOrders())
                    .writeObjectEnd();
        }
        json.writeArrayEnd();
    }

    /** How far this host's clock can be from UTC: what every timestamp it stamps, compared with another host's, carries. */
    private void writeClock() {
        hostClock.sample();
        json.writeString("clock")
                .writeColon()
                .writeObjectStart()
                .writeObjectEntry("source", hostClock.source().name())
                .writeComma();
        writeOptional("errorBoundNanos", hostClock.errorBoundNanos());
        json.writeComma()
                .writeObjectEntry("offsetNanos", hostClock.offsetNanos())
                .writeComma();
        writeOptional("ageMs", hostClock.ageMs());
        json.writeObjectEnd();
    }

    private void writeOptional(final String name, final long value) {
        json.writeString(name).writeColon();
        if (value == ClockReadings.UNAVAILABLE) {
            json.writeNull();
        } else {
            json.writeNumber(value);
        }
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
                    .writeComma()
                    .writeObjectEntry("marketDataBadMs", marketDataBadMs(slot, now))
                    .writeObjectEnd();
        }
        json.writeArrayEnd();
    }

    /** How long the listing's book has been flagged bad data, the OMS refusing new orders on it; 0 while trusted. */
    private long marketDataBadMs(final int slot, final long now) {
        final long badSinceNanos = prices.badSince(slot);
        return badSinceNanos == 0 ? 0 : Math.max(1, now - badSinceNanos / 1_000_000);
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

    private static void park(final long millis) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}
