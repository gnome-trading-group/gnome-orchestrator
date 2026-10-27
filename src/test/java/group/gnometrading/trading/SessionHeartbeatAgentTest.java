package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.RegistryConnection;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.GatewayHealth;
import group.gnometrading.gateways.GatewayRunners;
import group.gnometrading.gateways.ReaderPauseControl;
import group.gnometrading.gateways.ratelimit.VenueRateLimiters;
import group.gnometrading.gateways.venues.kalshi.KalshiVenue;
import group.gnometrading.host.HostReadings;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.oms.ledger.LedgerAgent;
import group.gnometrading.oms.ledger.LedgerRing;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.ListingDirectory;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.sequencer.JournalWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionHeartbeatAgentTest {

    private static final long SAMPLE_MS = 1_000;
    private static final long POST_MS = 5_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final long[] now = {10_000};
    private final List<String> posted = new ArrayList<>();
    private final Deque<Object> responses = new ArrayDeque<>();
    private final List<GnomeAgentRunner> started = new ArrayList<>();
    private RegistryConnection registry;
    private SharedPriceBuffer prices;
    private PriceSlotRegistry slots;
    private LedgerRing ledgerRing;
    private LedgerAgent ledger;
    private RiskEngine riskEngine;
    private VenueRateLimiters rateLimiters;
    private HostReadings host;

    @TempDir
    Path tempDir;

    private static final class Named implements GnomeAgent {
        private final String name;

        Named(final String name) {
            this.name = name;
        }

        @Override
        public int doWork() {
            return 0;
        }

        @Override
        public String roleName() {
            return name;
        }
    }

    @BeforeEach
    void setUp() {
        registry = mock(RegistryConnection.class);
        when(registry.tryPost(any(), any(byte[].class), anyInt())).thenAnswer(call -> {
            final byte[] body = call.getArgument(1);
            posted.add(new String(body, 0, (int) call.getArgument(2), StandardCharsets.UTF_8));
            final Object response = responses.isEmpty() ? 200 : responses.poll();
            if (response instanceof RuntimeException error) {
                throw error;
            }
            return response;
        });
        prices = new SharedPriceBuffer(1);
        slots = new PriceSlotRegistry(1);
        slots.register(100);
        ledgerRing = new LedgerRing(16, 4, Long.MAX_VALUE, new DefaultPositionTracker(new SharedPositionBuffer(8)));
        ledger = new LedgerAgent(
                ledgerRing,
                mock(RegistryConnection.class),
                () -> now[0],
                new NullLogger(),
                "s1",
                prices,
                slots,
                250,
                1_000);
        riskEngine = RiskEngine.syncedFromRegistry(() -> now[0], Duration.ofSeconds(30), ListingDirectory.EMPTY);
        rateLimiters = new VenueRateLimiters(new NullLogger(), System::nanoTime, () -> now[0]);
        rateLimiters.forAccount(KalshiVenue.rateLimits(), "key-1");
        host = new HostReadings(tempDir.resolve("proc"), tempDir.toFile());
    }

    @AfterEach
    void tearDown() throws Exception {
        host.close();
        for (GnomeAgentRunner runner : started) {
            runner.close();
        }
    }

    private GnomeAgentRunner running(final String name) {
        final GnomeAgentRunner runner = new GnomeAgentRunner(new Named(name), Throwable::printStackTrace);
        GnomeAgentRunner.startOnThread(runner);
        started.add(runner);
        return runner;
    }

    private SessionHeartbeatAgent heartbeat(final List<GnomeAgentRunner> agents, final List<GatewayHealth> gateways) {
        return new SessionHeartbeatAgent(
                "s1",
                registry,
                () -> now[0],
                new NullLogger(),
                agents,
                gateways,
                prices,
                slots,
                ledger,
                ledgerRing,
                riskEngine,
                rateLimiters.all(),
                host,
                null,
                SAMPLE_MS,
                POST_MS);
    }

    private void step(final SessionHeartbeatAgent agent, final long millis) throws InterruptedException {
        now[0] += millis;
        // Real time too, so running agents' loops move between samples.
        Thread.sleep(20);
        agent.doWork();
    }

    @Test
    void reportsEachPartsProgressAndTheQuietOfEachListing() throws Exception {
        final GnomeAgentRunner stuck = new GnomeAgentRunner(new Named("StuckAgent"), Throwable::printStackTrace);
        final GnomeAgentRunner live = running("LiveAgent");
        final GatewayRunners gateway = GatewayRunners.venue(
                100,
                new ReaderPauseControl(),
                new GnomeAgentRunner(new Named("KalshiReader"), Throwable::printStackTrace),
                new GnomeAgentRunner(new Named("KalshiGateway"), Throwable::printStackTrace));
        final SessionHeartbeatAgent agent = heartbeat(List.of(stuck, live), List.of(gateway));

        agent.onStart();
        agent.doWork();
        assertEquals(1, posted.size(), "reports at once");
        step(agent, 2_000);
        prices.writeQuote(0, 30, 32);
        step(agent, 1_000);
        step(agent, 2_000);

        assertEquals(2, posted.size());
        final JsonNode beat = mapper.readTree(posted.get(1));
        assertEquals("s1", beat.get("sessionId").asText());
        assertEquals(5_000, beat.get("uptimeMs").asLong());
        final JsonNode agents = beat.get("agents");
        assertEquals("StuckAgent", agents.get(0).get("name").asText());
        assertEquals(5_000, agents.get(0).get("stalledMs").asLong(), "never advanced");
        assertEquals(0, agents.get(1).get("stalledMs").asLong(), "advanced since the last sample");
        assertFalse(agents.get(1).get("exited").asBoolean());
        final JsonNode listing = beat.get("listings").get(0);
        assertEquals(100, listing.get("listingId").asInt());
        assertEquals(2_000, listing.get("priceUnchangedMs").asLong(), "moved at the sample 2s before");
        final JsonNode ledgerHealth = beat.get("ledger");
        assertTrue(ledgerHealth.get("lastAcceptedAgoMs").isNull(), "nothing written yet");
        assertEquals(0, ledgerHealth.get("consecutiveFailures").asInt());
        assertFalse(ledgerHealth.get("fenced").asBoolean());
        final JsonNode gatewayHealth = beat.get("gateways").get(0);
        assertEquals("KalshiReader 100", gatewayHealth.get("name").asText());
        assertTrue(gatewayHealth.get("reconnecting").asBoolean(), "a reader starts held until its socket connects");
        assertEquals(0, gatewayHealth.get("resyncsLast5m").asLong(), "a venue gateway has no book to resync");
        final JsonNode risk = beat.get("risk");
        assertTrue(risk.get("stale").asBoolean(), "never refreshed from the registry");
        assertFalse(risk.get("unguarded").asBoolean());
        assertEquals(0, risk.get("unbuildablePolicies").asInt());
        assertEquals(0, risk.get("droppedLatches").asLong());
        final JsonNode limiter = beat.get("rateLimit").get(0);
        assertEquals("Kalshi/key-1", limiter.get("account").asText());
        assertFalse(limiter.get("throttling").asBoolean());
    }

    @Test
    void fitsASessionWithHundredsOfListings() throws Exception {
        final int listings = 500;
        prices = new SharedPriceBuffer(listings);
        slots = new PriceSlotRegistry(listings);
        final List<GatewayHealth> gateways = new ArrayList<>();
        final List<GnomeAgentRunner> agents = new ArrayList<>();
        for (int i = 0; i < listings; i++) {
            final int listingId = 1_000_000_000 + i;
            slots.register(listingId);
            gateways.add(new Health("market data " + listingId));
            agents.add(new GnomeAgentRunner(new Named("PolymarketUsAgent" + i), Throwable::printStackTrace));
        }
        final SessionHeartbeatAgent agent = heartbeat(agents, gateways);
        agent.onStart();
        // The widest stall and quiet times a field can hold.
        now[0] = Long.MAX_VALUE;
        agent.encode(now[0]);

        final JsonNode body = mapper.readTree(agent.lastBody());
        assertEquals(listings, body.get("listings").size());
        assertEquals(listings, body.get("gateways").size());
        assertEquals(listings, body.get("agents").size());
        assertTrue(agent.lastBody().length()
                <= SessionHeartbeatAgent.bodyCapacity("s1", agents, gateways, slots, rateLimiters.all()));
    }

    /** A listing's market data as the heartbeat sees it. */
    private static final class Health implements GatewayHealth {
        private final String name;
        volatile boolean reconnecting;
        volatile long resyncs;

        Health(final String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isReconnecting() {
            return reconnecting;
        }

        @Override
        public long bookResyncs() {
            return resyncs;
        }
    }

    @Test
    void reportsAMarketsResyncsOverTheLastFiveMinutes() throws Exception {
        final Health market = new Health("market data 100");
        final SessionHeartbeatAgent agent = heartbeat(List.of(), List.of(market));
        agent.onStart();
        agent.doWork();
        market.resyncs = 3;
        market.reconnecting = true;
        step(agent, POST_MS);

        JsonNode gateway =
                mapper.readTree(posted.get(posted.size() - 1)).get("gateways").get(0);
        assertEquals("market data 100", gateway.get("name").asText());
        assertTrue(gateway.get("reconnecting").asBoolean());
        assertEquals(3, gateway.get("resyncsLast5m").asLong());

        for (long elapsed = 0; elapsed < 5 * 60_000; elapsed += POST_MS) {
            step(agent, POST_MS);
        }
        gateway = mapper.readTree(posted.get(posted.size() - 1)).get("gateways").get(0);
        assertEquals(0, gateway.get("resyncsLast5m").asLong(), "none in the last five minutes");
    }

    @Test
    void stopsSendingOnceTheSessionHasEnded() throws Exception {
        final SessionHeartbeatAgent agent = heartbeat(List.of(), List.of());
        responses.add(409);
        agent.onStart();
        agent.doWork();
        step(agent, POST_MS);
        step(agent, POST_MS);
        assertEquals(1, posted.size());
    }

    @Test
    void keepsTryingThroughFailuresAndNeverThrows() throws Exception {
        final SessionHeartbeatAgent agent = heartbeat(List.of(), List.of());
        responses.add(503);
        responses.add(new IllegalStateException("socket closed"));
        agent.onStart();
        agent.doWork();
        step(agent, POST_MS);
        step(agent, POST_MS);
        assertEquals(3, posted.size());
    }

    @Test
    void samplingAllocatesNothing() throws Exception {
        final SessionHeartbeatAgent agent = new SessionHeartbeatAgent(
                "s1",
                registry,
                () -> now[0],
                new NullLogger(),
                List.of(running("LiveAgent")),
                List.of(),
                prices,
                slots,
                ledger,
                ledgerRing,
                riskEngine,
                rateLimiters.all(),
                host,
                null,
                SAMPLE_MS,
                Long.MAX_VALUE / 2);
        agent.onStart();
        agent.doWork();
        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long threadId = Thread.currentThread().getId();
        // Warms the price writes too: interpreted, the buffer's accessors allocate where compiled code doesn't.
        for (int i = 0; i < 50_000; i++) {
            now[0] += SAMPLE_MS;
            prices.writeTrade(0, i);
            agent.doWork();
        }
        final long before = threads.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < 1_000; i++) {
            now[0] += SAMPLE_MS;
            prices.writeTrade(0, i);
            agent.doWork();
        }
        assertEquals(0, threads.getThreadAllocatedBytes(threadId) - before);
    }

    private HostReadings fixtureHost() throws Exception {
        final Path proc = tempDir.resolve("fixture-proc");
        Files.createDirectories(proc.resolve("self"));
        Files.writeString(proc.resolve("stat"), "cpu  1000 20 300 8000 50 0 30 0 0 0\n");
        Files.writeString(proc.resolve("meminfo"), "MemTotal: 2000000 kB\nMemAvailable: 500000 kB\n");
        Files.writeString(proc.resolve("self").resolve("status"), "VmRSS:\t400000 kB\n");
        return new HostReadings(proc, tempDir.toFile());
    }

    /** A sealer that has sealed {@code count} one-record segments, all still waiting to be shipped. */
    private JournalSegmentSealer sealed(final int count) throws Exception {
        final JournalSegmentSealer sealer = new JournalSegmentSealer(1 << 20, () -> now[0], new NullLogger());
        final JournalWriter writer = new JournalWriter(tempDir.resolve("journal"), 1 << 20, sealer::submit);
        for (int sequence = 1; sequence <= count; sequence++) {
            writer.append(0, sequence, 1, new UnsafeBuffer(new byte[0]), 0);
            writer.roll();
        }
        writer.close();
        assertTrue(sealer.close(Duration.ofSeconds(5)));
        return sealer;
    }

    private SessionHeartbeatAgent heartbeat(final HostReadings readings, final JournalSegmentSealer journal) {
        return new SessionHeartbeatAgent(
                "s1",
                registry,
                () -> now[0],
                new NullLogger(),
                List.of(),
                List.of(),
                prices,
                slots,
                ledger,
                ledgerRing,
                riskEngine,
                rateLimiters.all(),
                readings,
                journal,
                SAMPLE_MS,
                POST_MS);
    }

    @Test
    void reportsTheHostAndTheJournalsBacklog() throws Exception {
        final JournalSegmentSealer journal = sealed(2);
        now[0] += 90_000;
        try (HostReadings readings = fixtureHost()) {
            final SessionHeartbeatAgent agent = heartbeat(readings, journal);
            agent.onStart();
            agent.encode(now[0]);
            final JsonNode body = mapper.readTree(agent.lastBody());

            final JsonNode hostNode = body.get("host");
            assertEquals(-1, hostNode.get("cpuBusyPermille").asLong(), "no CPU share before a second sample");
            assertEquals(500_000L * 1024, hostNode.get("memAvailableBytes").asLong());
            assertEquals(2_000_000L * 1024, hostNode.get("memTotalBytes").asLong());
            assertEquals(400_000L * 1024, hostNode.get("processRssBytes").asLong());
            assertTrue(hostNode.get("heapUsedBytes").asLong() > 0);
            assertTrue(hostNode.get("diskFreeBytes").asLong() > 0);

            final JsonNode journalNode = body.get("journal");
            assertEquals(2, journalNode.get("pendingSegments").asInt());
            assertEquals(90_000, journalNode.get("oldestPendingMs").asLong());
            assertEquals(1 << 20, journalNode.get("segmentBytes").asLong());
        }
    }

    @Test
    void reportsNoJournalWhenItIsOff() throws Exception {
        final SessionHeartbeatAgent agent = heartbeat(List.of(), List.of());
        agent.onStart();
        agent.encode(now[0]);

        final JsonNode body = mapper.readTree(agent.lastBody());
        assertTrue(body.get("journal").isNull());
        assertEquals(-1, body.get("host").get("memTotalBytes").asLong(), "this test's host has no /proc");
    }

    @Test
    void writingTheHeartbeatAllocatesNothing() throws Exception {
        final JournalSegmentSealer journal = sealed(3);
        try (HostReadings readings = fixtureHost()) {
            final SessionHeartbeatAgent agent = heartbeat(readings, journal);
            agent.onStart();
            final com.sun.management.ThreadMXBean threads =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            final long threadId = Thread.currentThread().getId();
            for (int i = 0; i < 20_000; i++) {
                now[0] += POST_MS;
                agent.encode(now[0]);
            }
            final long before = threads.getThreadAllocatedBytes(threadId);
            for (int i = 0; i < 1_000; i++) {
                now[0] += POST_MS;
                agent.encode(now[0]);
            }
            final long allocated = threads.getThreadAllocatedBytes(threadId) - before;

            assertTrue(agent.lastBody().contains("\"pendingSegments\":3"), "the readings were written");
            assertEquals(0, allocated);
        }
    }
}
