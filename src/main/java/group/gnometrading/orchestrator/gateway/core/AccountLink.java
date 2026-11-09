package group.gnometrading.orchestrator.gateway.core;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.AccountState;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.fee.FeeTimeline;
import java.io.IOException;

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

        default void removeSession(final long slot) {}
    }

    int accountId();

    /** The exchange the account trades on. */
    int exchangeId();

    ManyToOneRingBuffer<LaneCommand> commands();

    AccountState state();

    /** Orders working on the account; approximate from any thread but the lane's. */
    int workingOrders();

    Markets markets();

    /** The venue's order lookups on this account: the control thread's only, as they block and aren't thread-safe. */
    VenueOrderQuery orderQuery();
}
