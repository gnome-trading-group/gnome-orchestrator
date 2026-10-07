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
import group.gnometrading.gateways.GatewayRunners;
import group.gnometrading.gateways.ReaderPauseControl;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.oms.ledger.LedgerAgent;
import group.gnometrading.oms.ledger.LedgerRing;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    }

    @AfterEach
    void tearDown() throws Exception {
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

    private SessionHeartbeatAgent heartbeat(final List<GnomeAgentRunner> agents, final List<GatewayRunners> gateways) {
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
        assertEquals("KalshiReader", gatewayHealth.get("name").asText());
        assertTrue(gatewayHealth.get("reconnecting").asBoolean(), "a reader starts held until its socket connects");
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
}
