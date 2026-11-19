package group.gnometrading.orchestrator.gateway.core;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.AccountState;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.PriceGrid;
import group.gnometrading.sm.fee.FeeTimeline;
import java.io.IOException;
import java.util.List;
import java.util.function.IntFunction;

/**
 * What the gateway's threads need of an account: where sessions' commands go, whether it takes new orders, how many
 * orders it has working, and (for the control thread) how to make a listing tradeable on it.
 */
public interface AccountLink {

    /** What only the venue knows how to do, on the gateway's control thread: may block and allocate. */
    interface Markets {
        /**
         * Makes a listing tradeable on the account: the lane can send its orders and the reader read their events.
         *
         * @return where the listing's orders can meet others, for the self-trade check
         */
        CrossKey load(Listing listing, FeeTimeline fees) throws IOException;

        /** A session the account now serves, under its slot; on venues whose order ids carry the session. */
        default void addSession(final long slot, final String sessionId) {}

        /**
         * A paper session the account now serves: the listings its hello names here, and its simulation as its launch
         * pinned it, so an account simulating its venue can build the session's own books before its first order.
         */
        default void addPaperSession(
                final long slot, final String sessionId, final List<Listing> listings, final String simulationJson) {
            addSession(slot, sessionId);
        }

        default void removeSession(final long slot) {}

        /**
         * Puts each listing loaded on the account on its current price grid, which {@code grids} gives by listing id
         * (null for one with no spec, which keeps what it has), where order entry prices on the listing's own grid.
         * Called from a side thread, not the control thread.
         */
        default void priceGrids(final IntFunction<PriceGrid> grids) {}

        /**
         * Whether a paper session new to the account fits beside those it serves, judged before its books are built: a
         * simulated venue refuses one that would take it past its caps. True where nothing limits it.
         */
        default boolean roomFor(final List<Listing> listings) {
            return true;
        }

        /** What it holds against its caps, for a refusal's log line; empty where nothing limits it. */
        default String describeLoad() {
            return "";
        }
    }

    int accountId();

    /** The exchange the account trades on. */
    int exchangeId();

    ManyToOneRingBuffer<LaneCommand> commands();

    /** After committing a command to {@link #commands()}: wakes the lane if it sleeps when idle and is waiting. */
    default void wakeLane() {}

    AccountState state();

    /** Orders working on the account; approximate from any thread but the lane's. */
    int workingOrders();

    /**
     * Nothing queued for the venue and no request awaiting its answer, from any thread: every request's outcome is
     * known, so a gateway stopping without cancels leaves nothing half-sent.
     */
    default boolean quiet() {
        return true;
    }

    Markets markets();

    /** The venue's order lookups on this account: the control thread's only, as they block and aren't thread-safe. */
    VenueOrderQuery orderQuery();
}
