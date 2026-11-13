package group.gnometrading.orchestrator.session.lifecycle;

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
import group.gnometrading.gateways.ReaderPauseControl;
import group.gnometrading.host.HostReadings;
import group.gnometrading.latency.LatencyKey;
import group.gnometrading.latency.MinuteHistograms;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.oms.ledger.LedgerAgent;
import group.gnometrading.oms.ledger.LedgerRing;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.oms.risk.ListingDirectory;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.orchestrator.runtime.ClockReadings;
import group.gnometrading.orchestrator.runtime.GatewayHealth;
import group.gnometrading.orchestrator.runtime.GatewayRunners;
import group.gnometrading.orchestrator.session.journal.JournalSegmentSealer;
import group.gnometrading.sequencer.JournalWriter;
import group.gnometrading.sequencer.metrics.ColumnDef;
import group.gnometrading.sequencer.metrics.ColumnarMetricRecorder;
import group.gnometrading.sequencer.metrics.RecordBuffer;
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
    private HostReadings host;
    private ClockReadings hostClock;

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

    private MinuteHistograms histograms;
    private int omsKey;
    private ColumnarMetricRecorder latencyRows;
    private SampleBuffer samples;
    private LatencyReport latency;

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
        host = new HostReadings(tempDir.resolve("proc"), tempDir.toFile());
        hostClock = new ClockReadings(tempDir.resolve("clock.status"), () -> now[0]);
        histograms = new MinuteHistograms(2);
        omsKey = histograms.key(new LatencyKey(3, "oms.process", 4, "KALSHI", 0, "NEW", 1, "MARKET_DATA"));
        histograms.key(new LatencyKey(3, "oms.process", 4, "KALSHI", 1, "CANCEL", 1, "MARKET_DATA"));
        latencyRows = new ColumnarMetricRecorder(16);
        samples = new SampleBuffer(256 * 1024, 1_024, 48 * 1024);
        latency = new LatencyReport(List.of(histograms), List.of("latency.oms"), latencyRows, samples);
    }

    @AfterEach
    void tearDown() throws Exception {
        host.close();
        hostClock.close();
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

    /** An order gateway link as the heartbeat sees it. */
    private static final class Link implements OrderGatewayLinkStatus {
        volatile boolean linked;
        volatile int working;

        @Override
        public String gatewayKey() {
            return "live:kalshi:main";
        }

        @Override
        public boolean linked() {
            return this.linked;
        }

        @Override
        public int workingOrders() {
            return this.working;
        }
    }

    private final Link link = new Link();
    private final List<OrderGatewayLinkStatus> orderGateways = new ArrayList<>(List.of(this.link));

    @Test
    void reportsEachOrderGatewayLinkAndHowLongItHasBeenDown() throws Exception {
        this.link.linked = true;
        this.link.working = 2;
        final SessionHeartbeatAgent agent = heartbeat(List.of(), List.of());
        agent.onStart();
        agent.doWork();
        JsonNode reported = mapper.readTree(posted.get(posted.size() - 1))
                .get("orderGateways")
                .get(0);
        assertEquals("live:kalshi:main", reported.get("key").asText());
        assertTrue(reported.get("linked").asBoolean());
        assertEquals(0, reported.get("unlinkedMs").asLong());
        assertEquals(2, reported.get("workingOrders").asInt());

        this.link.linked = false;
        step(agent, SAMPLE_MS);
        step(agent, POST_MS);
        reported = mapper.readTree(posted.get(posted.size() - 1))
                .get("orderGateways")
                .get(0);
        assertFalse(reported.get("linked").asBoolean());
        assertEquals(POST_MS, reported.get("unlinkedMs").asLong(), "down since the sample that first saw it");
        assertEquals(2, reported.get("workingOrders").asInt());

        this.link.linked = true;
        step(agent, POST_MS);
        reported = mapper.readTree(posted.get(posted.size() - 1))
                .get("orderGateways")
                .get(0);
        assertEquals(0, reported.get("unlinkedMs").asLong());
    }

    @Test
    void aListingWhoseBookIsFlaggedBadReportsForHowLong() throws Exception {
        final SessionHeartbeatAgent agent = heartbeat(List.of(), List.of());
        agent.onStart();
        agent.doWork();
        prices.markBad(0, (now[0] - 3_000) * 1_000_000);
        step(agent, POST_MS);

        final JsonNode listing =
                mapper.readTree(posted.get(posted.size() - 1)).get("listings").get(0);
        assertEquals(3_000 + POST_MS, listing.get("marketDataBadMs").asLong());
    }

    private SessionHeartbeatAgent heartbeat(final List<GnomeAgentRunner> agents, final List<GatewayHealth> gateways) {
        return new SessionHeartbeatAgent(
                "s1",
                registry,
                () -> now[0],
                new NullLogger(),
                agents,
                gateways,
                orderGateways,
                prices,
                slots,
                ledger,
                ledgerRing,
                riskEngine,
                host,
                hostClock,
                null,
                samples,
                List.of(),
                latency,
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
        assertEquals(0, listing.get("marketDataBadMs").asLong(), "its book is trusted");
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
                <= SessionHeartbeatAgent.bodyCapacity("s1", agents, gateways, orderGateways, slots));
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
    void aClosedMinutesLatencyIsSampledAndJournaledOnce() throws Exception {
        final long minute = 1_700_000_040L * 1_000_000_000L;
        histograms.record(omsKey, 1_500, minute + 5);
        histograms.record(omsKey, 2_500, minute + 6);
        histograms.roll(minute + MinuteHistograms.MINUTE_NANOS);
        final SessionHeartbeatAgent agent = heartbeat(List.of(), List.of());
        agent.onStart();

        agent.doWork();
        step(agent, POST_MS);

        final JsonNode body = mapper.readTree(posted.get(posted.size() - 1));
        assertFalse(body.has("latency"), "latency travels as a sample, not in the health");
        final JsonNode sample = only(body.get("samples"), "latency.oms");
        assertEquals(minute / 1_000_000, sample.get("tsMs").asLong());
        final JsonNode keys = sample.get("data").get("keys");
        assertEquals(1, keys.size(), "the empty CANCEL key is left out");
        assertEquals("oms.process", keys.get(0).get("m").asText());
        assertEquals("KALSHI", keys.get(0).get("ex").asText());
        assertEquals(4, keys.get(0).get("exchangeId").asLong());
        assertEquals("NEW", keys.get(0).get("act").asText());
        assertEquals("MARKET_DATA", keys.get(0).get("kind").asText());
        assertEquals(2, keys.get(0).get("n").asLong());
        assertEquals(2_500, keys.get(0).get("max").asLong());

        final RecordBuffer rows = latencyRows.getBuffers().get(0);
        assertEquals(LatencyReport.STREAM, rows.getName());
        assertEquals(1, rows.getCount(), "one row per key with data, written once however many samples see it");
        assertEquals(minute, rows.getLongColumn(column(rows, "minute_start"))[0]);
        assertEquals(3, rows.getIntColumn(column(rows, "metric"))[0]);
        assertEquals(4, rows.getIntColumn(column(rows, "exchange_id"))[0]);
        assertEquals(0, rows.getIntColumn(column(rows, "action"))[0]);
        assertEquals(1, rows.getIntColumn(column(rows, "kind"))[0]);
        assertEquals(2, rows.getLongColumn(column(rows, "count"))[0]);
    }

    @Test
    void samplesAreSentUntilAPostCarryingThemIsAccepted() throws Exception {
        samples.begin("runtime", 1).writeObjectEntry("v", 1);
        samples.commit();
        final SessionHeartbeatAgent agent = heartbeat(List.of(), List.of());
        agent.onStart();
        responses.add(503);

        agent.doWork();
        assertEquals(1, mapper.readTree(posted.get(0)).get("samples").size());
        assertEquals(1, samples.pending(), "a refused post keeps its samples");

        step(agent, POST_MS);
        final JsonNode accepted = mapper.readTree(posted.get(1));
        assertEquals(1, accepted.get("samples").size());
        assertEquals(1, accepted.get("sampleBuffer").get("pending").asLong());
        assertEquals(0, samples.pending(), "an accepted post's samples are gone");

        step(agent, POST_MS);
        assertEquals(0, mapper.readTree(posted.get(2)).get("samples").size());
    }

    @Test
    void samplesFillOnlyWhatTheRegistrysLimitLeaves() throws Exception {
        final String filler = "x".repeat(4_000);
        for (int i = 0; i < 40; i++) {
            samples.begin("runtime", i).writeObjectEntry("filler", filler);
            samples.commit();
        }
        final SessionHeartbeatAgent agent = heartbeat(List.of(), List.of());
        agent.onStart();

        agent.doWork();

        final String body = posted.get(0);
        assertTrue(body.length() <= SessionHeartbeatAgent.MAX_BODY_BYTES, body.length() + " bytes");
        final int carried = mapper.readTree(body).get("samples").size();
        assertTrue(carried > 0 && carried < 40, carried + " samples");
        assertEquals(40 - carried, samples.pending(), "the rest wait for the next post");
    }

    private static JsonNode only(final JsonNode samples, final String kind) {
        JsonNode found = null;
        for (JsonNode sample : samples) {
            if (sample.get("kind").asText().equals(kind)) {
                assertTrue(found == null, "two " + kind + " samples");
                found = sample;
            }
        }
        assertTrue(found != null, "no " + kind + " sample");
        return found;
    }

    private static int column(final RecordBuffer rows, final String name) {
        for (ColumnDef column : rows.getColumns()) {
            if (column.name().equals(name)) {
                return column.columnIndex();
            }
        }
        throw new AssertionError("no column " + name);
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
                orderGateways,
                prices,
                slots,
                ledger,
                ledgerRing,
                riskEngine,
                host,
                hostClock,
                null,
                samples,
                List.of(),
                latency,
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

    @Test
    void reportsTheHostsClockAndHowOldItsStatusIs() throws Exception {
        final SessionHeartbeatAgent agent = heartbeat(List.of(), List.of());
        agent.onStart();
        agent.encode(now[0]);
        final JsonNode unknown = mapper.readTree(agent.lastBody()).get("clock");
        assertEquals("UNKNOWN", unknown.get("source").asText());
        assertTrue(unknown.get("errorBoundNanos").isNull());
        assertTrue(unknown.get("ageMs").isNull());

        writeClockStatus(now[0] - 7_000);
        agent.encode(now[0]);
        final JsonNode clock = mapper.readTree(agent.lastBody()).get("clock");
        assertEquals("PHC", clock.get("source").asText());
        assertEquals(23_617, clock.get("errorBoundNanos").asLong());
        assertEquals(-5, clock.get("offsetNanos").asLong());
        assertEquals(7_000, clock.get("ageMs").asLong());
    }

    private void writeClockStatus(final long writtenAtMs) throws Exception {
        Files.writeString(
                tempDir.resolve("clock.status"),
                "written_ms " + writtenAtMs + "\nsource PHC\nstratum 1\noffset_ns -5\nerror_bound_ns 23617\n"
                        + "phc_error_bound_ns 18372\n");
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
            writer.append(0, sequence, 0, 1, new UnsafeBuffer(new byte[0]), 0);
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
                orderGateways,
                prices,
                slots,
                ledger,
                ledgerRing,
                riskEngine,
                readings,
                hostClock,
                journal,
                samples,
                List.of(),
                latency,
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
        writeClockStatus(now[0]);
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
            assertTrue(agent.lastBody().contains("\"source\":\"PHC\""), "the clock was read");
            assertEquals(0, allocated);
        }
    }
}
