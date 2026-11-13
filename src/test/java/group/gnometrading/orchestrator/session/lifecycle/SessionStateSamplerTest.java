package group.gnometrading.orchestrator.session.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.ThreadMXBean;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.oms.ledger.LedgerRing;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.orchestrator.runtime.GatewayHealth;
import group.gnometrading.schemas.Side;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionStateSamplerTest {

    private static final int STRATEGY = 7;
    private static final int LISTING = 100;

    private final SampleBuffer samples = new SampleBuffer(256 * 1024, 64, 32 * 1024);
    private final DefaultPositionTracker positions = new DefaultPositionTracker(new SharedPositionBuffer(4));
    private final SharedPriceBuffer prices = new SharedPriceBuffer(1);
    private final PriceSlotRegistry slots = new PriceSlotRegistry(1);
    private final LedgerRing ledgerRing = new LedgerRing(16, 4, Long.MAX_VALUE, positions);
    private long resyncs;

    private final OrderGatewayLinkStatus link = new OrderGatewayLinkStatus() {
        @Override
        public String gatewayKey() {
            return "paper:kalshi:main";
        }

        @Override
        public boolean linked() {
            return true;
        }

        @Override
        public int workingOrders() {
            return 3;
        }
    };

    private final GatewayHealth feed = new GatewayHealth() {
        @Override
        public String name() {
            return "shard use1-1b7a9c";
        }

        @Override
        public boolean isReconnecting() {
            return false;
        }

        @Override
        public long bookResyncs() {
            return resyncs;
        }
    };

    private SessionStateSampler sampler() {
        positions.registerSlot(STRATEGY, LISTING);
        slots.register(LISTING);
        prices.register();
        return new SessionStateSampler(
                samples,
                positions.createPositionView(STRATEGY),
                prices,
                slots,
                ledgerRing,
                null,
                List.of(link),
                List.of(feed),
                null,
                60_000);
    }

    private Map<String, JsonNode> sent() throws Exception {
        ByteBuffer out = ByteBuffer.allocate(256 * 1024);
        out.put((byte) '[');
        samples.acknowledge(samples.writeTo(out, 64));
        out.put((byte) ']');
        Map<String, JsonNode> byKind = new HashMap<>();
        for (JsonNode sample :
                new ObjectMapper().readTree(new String(out.array(), 0, out.position(), StandardCharsets.UTF_8))) {
            byKind.put(sample.get("kind").asText(), sample.get("data"));
        }
        return byKind;
    }

    @Test
    void eachMinuteSamplesPositionsLedgerLinksAndFeeds() throws Exception {
        SessionStateSampler sampler = sampler();
        positions.applyStrategyFill(STRATEGY, LISTING, Side.Bid, 4_000_000, 500_000_000L, 1_000);
        prices.writeQuote(0, 490_000_000L, 510_000_000L);
        sampler.sampleIfDue(0);
        Map<String, JsonNode> first = sent();
        resyncs = 2;
        sampler.sampleIfDue(60_000);
        Map<String, JsonNode> second = sent();

        JsonNode listing = first.get("session.positions").get("listings").get(0);
        assertEquals(LISTING, listing.get("listingId").asInt());
        assertEquals(4_000_000, listing.get("netQuantity").asLong());
        assertEquals(490_000_000L, listing.get("bid").asLong());
        assertEquals(510_000_000L, listing.get("ask").asLong());
        assertEquals(false, listing.get("bad").asBoolean());
        assertEquals(0, first.get("session.ledger").get("lag").asLong());
        JsonNode gateway = first.get("session.gateways").get("links").get(0);
        assertEquals("paper:kalshi:main", gateway.get("key").asText());
        assertEquals(3, gateway.get("workingOrders").asInt());
        assertTrue(!first.get("session.marketdata").get("feeds").get(0).has("resyncs"), "no change to report yet");
        assertEquals(
                2,
                second.get("session.marketdata")
                        .get("feeds")
                        .get(0)
                        .get("resyncs")
                        .asLong());
        assertTrue(!first.containsKey("session.journal"), "the journal is off");
    }

    @Test
    void aLargeSessionsPositionsGoOutInPartsThatEachFit() throws Exception {
        DefaultPositionTracker many = new DefaultPositionTracker(new SharedPositionBuffer(100));
        SharedPriceBuffer manyPrices = new SharedPriceBuffer(100);
        PriceSlotRegistry manySlots = new PriceSlotRegistry(100);
        for (int i = 0; i < 100; i++) {
            many.registerSlot(STRATEGY, 1_000 + i);
            manySlots.register(1_000 + i);
            manyPrices.register();
        }
        SessionStateSampler sampler = new SessionStateSampler(
                samples,
                many.createPositionView(STRATEGY),
                manyPrices,
                manySlots,
                null,
                null,
                List.of(),
                List.of(),
                null,
                60_000);

        sampler.sampleIfDue(0);

        ByteBuffer out = ByteBuffer.allocate(256 * 1024);
        out.put((byte) '[');
        samples.writeTo(out, 64);
        out.put((byte) ']');
        JsonNode all = new ObjectMapper().readTree(new String(out.array(), 0, out.position(), StandardCharsets.UTF_8));
        int listings = 0;
        int parts = 0;
        for (JsonNode sample : all) {
            if (sample.get("kind").asText().equals("session.positions")) {
                assertEquals(parts, sample.get("tsMs").asLong(), "each part a millisecond on");
                assertEquals(3, sample.get("data").get("parts").asInt());
                assertTrue(
                        sample.toString().length() < 12 * 1024,
                        sample.toString().length() + " bytes");
                listings += sample.get("data").get("listings").size();
                parts++;
            }
        }
        assertEquals(3, parts);
        assertEquals(100, listings);
    }

    @Test
    void aSampleThatCantBeWrittenDoesNotStopTheOthersOrTheBuffer() {
        SampleBuffer tiny = new SampleBuffer(64 * 1024, 64, 220);
        positions.registerSlot(STRATEGY, LISTING);
        slots.register(LISTING);
        prices.register();
        SessionStateSampler sampler = new SessionStateSampler(
                tiny,
                positions.createPositionView(STRATEGY),
                prices,
                slots,
                ledgerRing,
                null,
                List.of(link),
                List.of(feed),
                null,
                60_000);

        assertThrows(java.nio.BufferOverflowException.class, () -> sampler.sampleIfDue(0), "positions outgrow 220 B");

        assertTrue(tiny.pending() >= 2, "the ledger and gateway samples were still written");
        tiny.begin("runtime", 1).writeObjectEntry("v", 1);
        tiny.commit();
    }

    @Test
    void samplingAllocatesNothing() {
        SessionStateSampler sampler = sampler();
        ByteBuffer out = ByteBuffer.allocate(256 * 1024);
        long now = 0;
        for (int i = 0; i < 50_000; i++) {
            now += 60_000;
            sampler.sampleIfDue(now);
            out.clear();
            samples.acknowledge(samples.writeTo(out, 64));
        }
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 5_000; i++) {
            now += 60_000;
            sampler.sampleIfDue(now);
            out.clear();
            samples.acknowledge(samples.writeTo(out, 64));
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(allocated < 4_096, "allocated " + allocated + " bytes");
    }
}
