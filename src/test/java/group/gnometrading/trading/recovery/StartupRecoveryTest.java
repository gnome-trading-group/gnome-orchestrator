package group.gnometrading.trading.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.RegistryConnection;
import group.gnometrading.gateways.outbound.recovery.VenueOrder;
import group.gnometrading.gateways.outbound.recovery.VenueOrderQuery;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.SharedPositionBuffer;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StartupRecoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int STRATEGY = 7;
    private static final long UNIT = 1_000_000L;
    private static final long CENT = 10_000_000L;
    private static final String BY_LISTING = "/api/ledger/orders?mode=live&listingIds=";
    private static final String BY_ID = "/api/ledger/orders?mode=live&status=ANY&exchangeOrderIds=";
    private static final Listing LISTING = new Listing(
            501,
            new Exchange(2, "KALSHI", "Kalshi", "global", SchemaType.MBP_10),
            new Security(3, "KXT", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
            "KXT-26:yes",
            "KXT");

    private final RegistryConnection registry = mock(RegistryConnection.class);
    private final Map<String, String> responses = new HashMap<>();
    private final List<JsonNode> posts = new ArrayList<>();
    private final List<String> postPaths = new ArrayList<>();
    private final FakeVenue venue = new FakeVenue();
    private DefaultPositionTracker positions;

    @BeforeEach
    void setUp() {
        positions = new DefaultPositionTracker(new SharedPositionBuffer(8));
        positions.registerSlot(STRATEGY, 501);
        when(registry.get(any())).thenAnswer(call -> {
            final String path = call.getArgument(0).toString();
            // The most specific registered path answers.
            final String body = responses.entrySet().stream()
                    .filter(entry -> path.startsWith(entry.getKey()))
                    .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                    .map(Map.Entry::getValue)
                    .orElse("[]");
            return ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8));
        });
        doAnswer(call -> {
                    final byte[] body = call.getArgument(1);
                    final int length = call.getArgument(2);
                    postPaths.add(call.getArgument(0).toString());
                    posts.add(MAPPER.readTree(new String(body, 0, length, StandardCharsets.UTF_8)));
                    return null;
                })
                .when(registry)
                .post(any(), any(byte[].class), anyInt());
        responses.put("/api/strategy-sessions?sessionId=", "[{\"status\":\"STARTING\",\"strategy_id\":7}]");
    }

    @Test
    void refusesToStartASessionTheRegistryHasStopped() {
        responses.put("/api/strategy-sessions?sessionId=", "[{\"status\":\"STOPPED\"}]");
        assertThrows(IllegalStateException.class, () -> recovery("paper", true).run());
    }

    @Test
    void inheritsTheStrategysInventoryAndVersion() throws Exception {
        responses.put(
                "/api/ledger/positions",
                "[{\"listing_id\":501,\"net_quantity\":\"-4000000\","
                        + "\"total_cost\":\"1600000000\",\"version\":\"12\",\"needs_review\":false}]");

        recovery("paper", true).run();

        final Position position = positions.getStrategyPosition(STRATEGY, 501);
        assertEquals(-4 * UNIT, position.netQuantity);
        assertEquals(1_600_000_000L, position.totalCost);
        assertEquals(12, position.version);
        assertTrue(posts.isEmpty());
    }

    @Test
    void aPositionNeedingReviewHaltsTheListing() throws Exception {
        responses.put(
                "/api/ledger/positions",
                "[{\"listing_id\":501,\"net_quantity\":\"0\",\"total_cost\":\"0\","
                        + "\"version\":\"3\",\"needs_review\":true}]");

        recovery("paper", true).run();

        assertEquals(List.of("/api/risk/halts"), postPaths);
        assertEquals(501, posts.get(0).get("listingId").asInt());
        assertEquals(STRATEGY, posts.get(0).get("strategyId").asInt());
    }

    @Test
    void startingFlatRecordsTheDiscardedInventory() throws Exception {
        responses.put(
                "/api/ledger/positions",
                "[{\"listing_id\":501,\"net_quantity\":\"4000000\","
                        + "\"total_cost\":\"1600000000\",\"version\":\"12\",\"needs_review\":false}]");

        recovery("paper", false).run();

        final JsonNode reset = posts.get(0).get("fills").get(0);
        assertEquals("RESET", reset.get("source").asText());
        assertEquals(0, reset.get("netQuantityAfter").asLong());
        assertEquals(13, reset.get("positionVersion").asLong(), "a version above the discarded position's");
        assertEquals(0, positions.getStrategyPosition(STRATEGY, 501).netQuantity);
        assertEquals(13, positions.getStrategyPosition(STRATEGY, 501).version);
    }

    @Test
    void liveNeverDiscardsRealInventory() {
        responses.put(
                "/api/ledger/positions",
                "[{\"listing_id\":501,\"net_quantity\":\"4000000\","
                        + "\"total_cost\":\"1600000000\",\"version\":\"12\",\"needs_review\":false}]");
        assertThrows(IllegalStateException.class, () -> recovery("live", false).run());
        assertTrue(posts.isEmpty());
    }

    @Test
    void cancelsAnEndedSessionsRestingOrderAndBooksWhatItFilledUnrecorded() throws Exception {
        // The old session's order: buy 10 at 40c; the ledger saw 2 fill for 80c and 1c of fees.
        final String row = ledgerOrder("old", 3, "old-3", 501, STRATEGY, false, 2, 80, 1);
        responses.put(BY_LISTING, "[" + row + "]");
        responses.put(BY_ID, "[" + row + "]");
        venue.resting.add(venueOrder("old-3", 6 * UNIT, 236 * CENT, 3 * CENT, false));
        // Once cancelled, it's terminal with the same fills.
        venue.afterCancel = venueOrder("old-3", 6 * UNIT, 236 * CENT, 3 * CENT, true);

        recovery("live", true).run();

        assertEquals(List.of("old-3"), venue.cancelled, "cancelled once, though both lookups found it");
        assertEquals(4 * UNIT, positions.getStrategyPosition(STRATEGY, 501).netQuantity, "the 4 the ledger missed");
        final JsonNode batch = posts.get(posts.size() - 1);
        final JsonNode fill = batch.get("fills").get(0);
        assertEquals("RECOVERY", fill.get("source").asText());
        assertEquals("old", fill.get("originSessionId").asText());
        assertEquals(4 * UNIT, fill.get("fillQty").asLong());
        assertEquals(39 * CENT, fill.get("fillPrice").asLong(), "($2.36 - $0.80) over 4");
        assertEquals(2 * CENT, fill.get("fee").asLong());
        assertEquals(6 * UNIT, fill.get("cumQtyAfter").asLong());
        assertEquals(1, batch.get("orderRecoveries").size());
        assertEquals(
                "old",
                batch.get("orderRecoveries").get(0).get("originSessionId").asText());
    }

    @Test
    void anOrderNobodyRecordedHaltsTheListing() throws Exception {
        venue.resting.add(venueOrder("manual-order", 0, 0, 0, false));

        recovery("live", true).run();

        assertTrue(venue.cancelled.isEmpty(), "not ours to cancel");
        assertEquals(List.of("/api/risk/halts"), postPaths);
    }

    @Test
    void leavesOtherStrategiesAndOtherListingsOnTheSameMarketAlone() throws Exception {
        responses.put(
                BY_ID,
                "[" + ledgerOrder("theirs", 1, "theirs-1", 501, 9, false, 0, 0, 0) + ","
                        + ledgerOrder("old", 8, "old-8", 502, STRATEGY, false, 0, 0, 0) + "]");
        venue.resting.add(venueOrder("theirs-1", 0, 0, 0, false));
        venue.resting.add(venueOrder("old-8", 0, 0, 0, false));

        recovery("live", true).run();

        assertTrue(venue.cancelled.isEmpty());
        assertTrue(posts.isEmpty());
    }

    @Test
    void anEndedSessionsUnacknowledgedOrderHaltsTheListing() throws Exception {
        responses.put(BY_LISTING, "[" + ledgerOrder("old", 3, null, 501, STRATEGY, false, 0, 0, 0) + "]");

        recovery("live", true).run();

        assertEquals(List.of("/api/risk/halts"), postPaths);
    }

    @Test
    void anOrderThatFinishedUnrecordedHasItsFillsBooked() throws Exception {
        responses.put(BY_LISTING, "[" + ledgerOrder("old", 3, "old-3", 501, STRATEGY, false, 0, 0, 0) + "]");
        venue.lookup.put("old-3", venueOrder("old-3", 2 * UNIT, 80 * CENT, 0, true));

        recovery("live", true).run();

        assertEquals(2 * UNIT, positions.getStrategyPosition(STRATEGY, 501).netQuantity);
    }

    @Test
    void aLedgerAheadOfTheVenueHaltsTheListing() throws Exception {
        responses.put(BY_LISTING, "[" + ledgerOrder("old", 3, "old-3", 501, STRATEGY, false, 5, 200, 0) + "]");
        venue.lookup.put("old-3", venueOrder("old-3", 2 * UNIT, 80 * CENT, 0, true));

        recovery("live", true).run();

        assertTrue(postPaths.contains("/api/risk/halts"));
        assertEquals(0, positions.getStrategyPosition(STRATEGY, 501).netQuantity);
    }

    @Test
    void paperSettlesItsEndedSessionsOpenOrdersWithoutAVenue() throws Exception {
        responses.put(
                "/api/ledger/orders?mode=paper&listingIds=",
                "["
                        + ledgerOrder("old", 3, null, 501, STRATEGY, false, 0, 0, 0) + ","
                        + ledgerOrder("running", 1, null, 501, STRATEGY, true, 0, 0, 0) + "]");

        recovery("paper", true).run();

        final JsonNode recoveries = posts.get(0).get("orderRecoveries");
        assertEquals(1, recoveries.size());
        assertEquals("old", recoveries.get(0).get("originSessionId").asText());
    }

    private StartupRecovery recovery(final String mode, final boolean inherit) {
        return new StartupRecovery(
                registry,
                positions,
                new NullLogger(),
                () -> 1_700_000_000_000L,
                "session-new",
                STRATEGY,
                mode,
                inherit,
                List.of(LISTING),
                "live".equals(mode) ? Map.of(501, venue) : Map.of());
    }

    private static String ledgerOrder(
            final String session,
            final long counter,
            final String exchangeOrderId,
            final int listing,
            final int strategy,
            final boolean active,
            final long filledUnits,
            final long notionalCents,
            final long feeCents) {
        return "{\"session_id\":\"" + session + "\",\"client_oid_counter\":\"" + counter
                + "\",\"exchange_order_id\":" + (exchangeOrderId == null ? "null" : "\"" + exchangeOrderId + "\"")
                + ",\"strategy_id\":" + strategy + ",\"listing_id\":" + listing + ",\"session_active\":" + active
                + ",\"side\":0,\"price\":\"400000000\",\"size\":\"10000000\",\"opened_at\":\"2026-10-06T10:00:00Z\""
                + ",\"ledger_filled_qty\":\"" + filledUnits * UNIT + "\",\"ledger_filled_notional\":\""
                + notionalCents * CENT + "\",\"ledger_fees\":\"" + feeCents * CENT + "\"}";
    }

    private static VenueOrder venueOrder(
            final String id, final long filled, final long notional, final long fees, final boolean terminal) {
        return new VenueOrder(id, "venue-" + id, filled, notional, fees, terminal);
    }

    private static final class FakeVenue implements VenueOrderQuery {
        final List<VenueOrder> resting = new ArrayList<>();
        final Map<String, VenueOrder> lookup = new HashMap<>();
        final List<String> cancelled = new ArrayList<>();
        VenueOrder afterCancel;

        @Override
        public List<VenueOrder> listOpenOrders(final Listing listing) {
            return resting;
        }

        @Override
        public Optional<VenueOrder> getOrder(final Listing listing, final String exchangeOrderId, final long after) {
            if (afterCancel != null && cancelled.contains(exchangeOrderId)) {
                return Optional.of(afterCancel);
            }
            return Optional.ofNullable(lookup.get(exchangeOrderId));
        }

        @Override
        public boolean cancel(final Listing listing, final VenueOrder order) {
            cancelled.add(order.exchangeOrderId());
            return true;
        }
    }
}
