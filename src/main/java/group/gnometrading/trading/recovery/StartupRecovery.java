package group.gnometrading.trading.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import group.gnometrading.RegistryConnection;
import group.gnometrading.gateways.outbound.recovery.VenueOrder;
import group.gnometrading.gateways.outbound.recovery.VenueOrderQuery;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.oms.position.DefaultPositionTracker;
import group.gnometrading.oms.position.Position;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.Listing;
import group.gnometrading.strings.ViewString;
import group.gnometrading.utils.ScaledMath;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.agrona.concurrent.EpochClock;

/**
 * Picks up where the strategy's earlier sessions left off, before any trading thread starts.
 *
 * <ol>
 *   <li>Refuses to start a session the registry no longer lets run, e.g. one stopped while it booted.
 *   <li>Seeds each listing with the inventory the ledger holds for the strategy, or, when the session chose not to
 *       inherit, records that it starts flat. Live never silently disowns real inventory.
 *   <li>Live only: settles what the strategy's ended sessions left on the venue. Their resting orders are
 *       cancelled; fills the ledger never recorded are booked as RECOVERY fills; an order nobody can account for
 *       halts the strategy on that listing until an operator looks.
 * </ol>
 *
 * <p>Any failure to reach the registry or the venue aborts startup: trading on a position or an order book this
 * session can't vouch for is worse than not starting.
 */
public final class StartupRecovery {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RUNNABLE_STATUSES = Set.of("SUBMITTED", "STARTING", "RUNNING");
    private static final long TERMINAL_POLL_MS = 500;
    private static final long TERMINAL_TIMEOUT_MS = 10_000;
    private static final int BUY = 0;
    // Venue ids per ledger lookup, keeping the request's URL short.
    private static final int LOOKUP_BATCH = 25;

    private final RegistryConnection registry;
    private final DefaultPositionTracker positions;
    private final Logger logger;
    private final EpochClock clock;
    private final String sessionId;
    private final int strategyId;
    private final String mode;
    private final boolean inherit;
    private final List<Listing> listings;
    // Live only; paper sessions have no venue.
    private final Map<Integer, VenueOrderQuery> venues;

    private final ArrayNode recoveryFills = MAPPER.createArrayNode();
    private final ArrayNode orderRecoveries = MAPPER.createArrayNode();

    public StartupRecovery(
            final RegistryConnection registry,
            final DefaultPositionTracker positions,
            final Logger logger,
            final EpochClock clock,
            final String sessionId,
            final int strategyId,
            final String mode,
            final boolean inherit,
            final List<Listing> listings,
            final Map<Integer, VenueOrderQuery> venues) {
        this.registry = registry;
        this.positions = positions;
        this.logger = logger;
        this.clock = clock;
        this.sessionId = sessionId;
        this.strategyId = strategyId;
        this.mode = mode;
        this.inherit = inherit;
        this.listings = listings;
        this.venues = venues;
    }

    public void run() throws IOException {
        requireRunnable();
        restorePositions();
        for (final Listing listing : listings) {
            final VenueOrderQuery venue = venues.get(listing.listingId());
            if (venue != null) {
                reconcileWithVenue(listing, venue);
            } else {
                settleStaleOrders(listing);
            }
        }
        if (!recoveryFills.isEmpty() || !orderRecoveries.isEmpty()) {
            postBatch(recoveryFills, orderRecoveries);
        }
    }

    private void requireRunnable() {
        final JsonNode sessions = get("/api/strategy-sessions?sessionId=" + sessionId);
        final String status =
                sessions.isEmpty() ? "" : sessions.get(0).path("status").asText();
        if (!RUNNABLE_STATUSES.contains(status)) {
            throw new IllegalStateException("Session " + sessionId + " is " + status + "; refusing to start");
        }
    }

    // --- positions ---

    private void restorePositions() {
        final JsonNode rows = get("/api/ledger/positions?strategyId=" + strategyId + "&mode=" + mode + "&listingIds="
                + listings.stream().map(l -> String.valueOf(l.listingId())).collect(Collectors.joining(",")));
        if (inherit) {
            for (final JsonNode row : rows) {
                final int listingId = row.path("listing_id").asInt();
                if (row.path("needs_review").asBoolean()) {
                    halt(
                            listingId,
                            "position needs review before it can be traded",
                            LogMessage.POSITION_NEEDS_REVIEW_HALTED);
                }
                positions.seedStrategyPosition(
                        strategyId,
                        listingId,
                        row.path("net_quantity").asLong(),
                        row.path("total_cost").asLong(),
                        row.path("version").asLong());
            }
            return;
        }
        resetPositions(rows);
    }

    /**
     * Starts flat. The ledger keeps the discarded inventory in a RESET row, so it is never silently forgotten, and
     * the RESET's higher version lets this session's own fills move the position. Live refuses: inventory that
     * still exists on the venue must be flattened, or set to 0 by an operator, first.
     */
    private void resetPositions(final JsonNode rows) {
        final ArrayNode resets = MAPPER.createArrayNode();
        for (final JsonNode row : rows) {
            final long net = row.path("net_quantity").asLong();
            if ("live".equals(mode) && net != 0) {
                throw new IllegalStateException("recovery.inherit=false, but the ledger holds " + net
                        + " on listing " + row.path("listing_id").asInt()
                        + " for this strategy; flatten it or adjust it to 0 first");
            }
            final long version = row.path("version").asLong() + 1;
            positions.seedStrategyPosition(strategyId, row.path("listing_id").asInt(), 0, 0, version);
            resets.add(fill("RESET", row.path("listing_id").asInt(), null, 0, 0)
                    .put("positionVersion", version)
                    .put(
                            "reason",
                            "session started flat; discarded " + net + " at cost "
                                    + row.path("total_cost").asLong()));
        }
        if (!resets.isEmpty()) {
            postBatch(resets, MAPPER.createArrayNode());
        }
    }

    // --- orders ---

    /**
     * Matches the listing's resting venue orders to the ledger by the id the venue knows them by. This strategy's
     * leftovers from ended sessions are cancelled and settled; another strategy's or a running session's are left
     * alone, as are orders recorded under another listing on the same venue market (Kalshi's YES and NO share one).
     * An order the ledger has no record of halts the listing until an operator looks.
     */
    private void reconcileWithVenue(final Listing listing, final VenueOrderQuery venue) throws IOException {
        final List<VenueOrder> resting = venue.listOpenOrders(listing);
        final Map<String, JsonNode> recorded = ledgerOrdersById(resting);
        final Set<String> settled = new HashSet<>();
        for (final VenueOrder order : resting) {
            final JsonNode row = recorded.get(order.exchangeOrderId());
            if (row == null) {
                halt(
                        listing.listingId(),
                        "an order on the venue no session recorded: " + order.exchangeOrderId(),
                        LogMessage.VENUE_ORDER_UNATTRIBUTABLE_HALTED);
            } else if (row.path("listing_id").asInt() == listing.listingId() && isOursAndEnded(row)) {
                settled.add(order.exchangeOrderId());
                venue.cancel(listing, order);
                settle(listing, row, awaitTerminal(listing, venue, order));
            }
        }
        for (final JsonNode row : openLedgerOrders(listing)) {
            if (isOursAndEnded(row)
                    && !settled.contains(row.path("exchange_order_id").asText(""))) {
                settleClosedOrder(listing, venue, row);
            }
        }
    }

    /**
     * An ended session's order that is no longer resting: books whatever it filled that the ledger missed. One the
     * venue never acknowledged can't be looked up, so its listing waits for an operator.
     */
    private void settleClosedOrder(final Listing listing, final VenueOrderQuery venue, final JsonNode row)
            throws IOException {
        final String id = row.path("exchange_order_id").asText(null);
        if (id == null) {
            halt(
                    listing.listingId(),
                    "an ended session's order was never acknowledged by the venue",
                    LogMessage.VENUE_ORDER_UNATTRIBUTABLE_HALTED);
            return;
        }
        final long openedMs = Instant.parse(row.path("opened_at").asText(Instant.EPOCH.toString()))
                .toEpochMilli();
        final Optional<VenueOrder> order = venue.getOrder(listing, id, openedMs);
        if (order.isEmpty()) {
            recordSettled(row);
            return;
        }
        VenueOrder found = order.get();
        if (!found.terminal()) {
            venue.cancel(listing, found);
            found = awaitTerminal(listing, venue, found);
        }
        settle(listing, row, found);
    }

    private void settleStaleOrders(final Listing listing) {
        for (final JsonNode row : openLedgerOrders(listing)) {
            if (isOursAndEnded(row)) {
                recordSettled(row);
            }
        }
    }

    /**
     * Books what the venue filled beyond what the ledger holds, valued at the venue's average over that part. If
     * this session started flat, the fill is recorded for the record without moving its position.
     */
    private void settle(final Listing listing, final JsonNode row, final VenueOrder order) {
        final long ledgerFilled = row.path("ledger_filled_qty").asLong();
        final long delta = order.filledQty() - ledgerFilled;
        if (delta < 0) {
            halt(
                    listing.listingId(),
                    "the ledger holds more fills than the venue for " + order.exchangeOrderId(),
                    LogMessage.VENUE_ORDER_UNATTRIBUTABLE_HALTED);
            return;
        }
        if (delta > 0) {
            final long notional =
                    order.filledNotional() - row.path("ledger_filled_notional").asLong();
            final long price = ScaledMath.multiplyDivide(notional, Statics.SIZE_SCALING_FACTOR, delta);
            final long fee = Math.max(0, order.fees() - row.path("ledger_fees").asLong());
            final Side side = row.path("side").asInt() == BUY ? Side.Bid : Side.Ask;
            if (inherit) {
                positions.applyStrategyFill(strategyId, listing.listingId(), side, delta, price, fee);
            }
            final Position after = positions.getStrategyPosition(strategyId, listing.listingId());
            recoveryFills.add(fill(
                            "RECOVERY",
                            listing.listingId(),
                            after,
                            row.path("client_oid_counter").asLong(),
                            order.filledQty())
                    .put("originSessionId", row.path("session_id").asText())
                    .put("side", side == Side.Bid ? BUY : 1)
                    .put("fillQty", delta)
                    .put("fillPrice", price)
                    .put("fee", fee)
                    .put("reason", inherit ? "filled while no session was running" : "not inherited; for the record"));
        }
        recordSettled(row);
    }

    private VenueOrder awaitTerminal(final Listing listing, final VenueOrderQuery venue, final VenueOrder order)
            throws IOException {
        final long deadline = clock.time() + TERMINAL_TIMEOUT_MS;
        VenueOrder current = order;
        while (!current.terminal()) {
            if (clock.time() > deadline) {
                throw new IllegalStateException("order " + order.exchangeOrderId() + " is still working after cancel");
            }
            sleep(TERMINAL_POLL_MS);
            current = venue.getOrder(listing, order.exchangeOrderId(), 0).orElse(current);
        }
        return current;
    }

    /** The ledger's record of each resting order, by venue id, whichever listing or strategy it belongs to. */
    private Map<String, JsonNode> ledgerOrdersById(final List<VenueOrder> resting) {
        final Map<String, JsonNode> byId = new HashMap<>();
        for (int from = 0; from < resting.size(); from += LOOKUP_BATCH) {
            final String ids = resting.subList(from, Math.min(from + LOOKUP_BATCH, resting.size())).stream()
                    .map(order -> URLEncoder.encode(order.exchangeOrderId(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining(","));
            get("/api/ledger/orders?mode=" + mode + "&status=ANY&exchangeOrderIds=" + ids)
                    .forEach(row -> byId.put(row.path("exchange_order_id").asText(), row));
        }
        return byId;
    }

    private boolean isOursAndEnded(final JsonNode row) {
        return row.path("strategy_id").asInt() == strategyId
                && !row.path("session_active").asBoolean();
    }

    private List<JsonNode> openLedgerOrders(final Listing listing) {
        final List<JsonNode> rows = new ArrayList<>();
        get("/api/ledger/orders?mode=" + mode + "&listingIds=" + listing.listingId() + "&status=OPEN")
                .forEach(rows::add);
        return rows;
    }

    private void recordSettled(final JsonNode row) {
        orderRecoveries.add(MAPPER.createObjectNode()
                .put("originSessionId", row.path("session_id").asText())
                .put("clientOidCounter", row.path("client_oid_counter").asLong())
                .put("listingId", row.path("listing_id").asInt()));
    }

    // --- registry ---

    /** Halts the strategy on the listing through the registry, which the risk sync applies before trading starts. */
    private void halt(final int listingId, final String reason, final LogMessage alert) {
        logger.log(alert, strategyId, listingId);
        post(
                "/api/risk/halts",
                MAPPER.createObjectNode()
                        .put("strategyId", strategyId)
                        .put("listingId", listingId)
                        .put("reason", reason)
                        .put("actor", "session " + sessionId + " startup"));
    }

    private ObjectNode fill(
            final String source, final int listingId, final Position after, final long counter, final long cumQty) {
        final ObjectNode fill = MAPPER.createObjectNode()
                .put("source", source)
                .put("listingId", listingId)
                .put("eventTimeNs", clock.time() * 1_000_000L)
                .put("netQuantityAfter", after == null ? 0 : after.netQuantity)
                .put("totalCostAfter", after == null ? 0 : after.totalCost)
                .put("realizedPnlAfter", after == null ? 0 : after.realizedPnl)
                .put("feesAfter", after == null ? 0 : after.totalFees)
                .put("positionVersion", after == null ? 0 : after.version);
        if (counter > 0) {
            fill.put("clientOidCounter", counter).put("cumQtyAfter", cumQty);
        }
        return fill;
    }

    private void postBatch(final ArrayNode fills, final ArrayNode recoveries) {
        final ObjectNode batch = MAPPER.createObjectNode().put("sessionId", sessionId);
        batch.set("fills", fills);
        batch.set("orderRecoveries", recoveries);
        post("/api/ledger/batch", batch);
    }

    private JsonNode get(final String path) {
        final ByteBuffer body = registry.get(new ViewString(path));
        final byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        try {
            return MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable registry response for " + path, e);
        }
    }

    private void post(final String path, final JsonNode body) {
        final byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        registry.post(new ViewString(path), bytes, bytes.length);
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while recovering", e);
        }
    }
}
