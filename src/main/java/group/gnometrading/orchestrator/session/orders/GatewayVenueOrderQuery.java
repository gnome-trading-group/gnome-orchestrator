package group.gnometrading.orchestrator.session.orders;

import group.gnometrading.gateways.orders.recovery.AccountOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.schemas.gateway.QueryStatus;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Startup recovery's questions about the account at the venue, asked through the session's gateway client, so the
 * session holds no trading key: the gateway answers with the account's own lookups. Blocks the calling thread until
 * the answer, once the client's link is welcomed.
 *
 * <p>A cancel can only be of an order an ended session of this strategy left: the gateway cancels what those sessions
 * hold on the listing and refuses if the order still rests, as it was then no order this session may cancel.
 */
final class GatewayVenueOrderQuery implements VenueOrderQuery {

    private static final AtomicLong QUERY_IDS = new AtomicLong();

    private final GatewayClient client;
    private final long timeoutNanos;

    GatewayVenueOrderQuery(final GatewayClient client, final long timeoutNanos) {
        this.client = client;
        this.timeoutNanos = timeoutNanos;
    }

    @Override
    public List<VenueOrder> listOpenOrders(final Listing listing) throws IOException {
        return ask(GatewayQuery.openOrders(nextId(), exchangeId(listing), securityId(listing)), "open orders");
    }

    @Override
    public Optional<VenueOrder> getOrder(final Listing listing, final String exchangeOrderId, final long createdAfterMs)
            throws IOException {
        final List<VenueOrder> found = ask(
                GatewayQuery.byExchangeOrderId(nextId(), exchangeId(listing), securityId(listing), exchangeOrderId),
                "order " + exchangeOrderId);
        return found.stream().findFirst();
    }

    /** A session only ever asks about its own listings; the account as a whole is the gateway's. */
    @Override
    public List<AccountOrder> listAllOpenOrders() {
        throw new UnsupportedOperationException("a session lists orders only on its own listings");
    }

    @Override
    public boolean cancel(final Listing listing, final VenueOrder order) throws IOException {
        ask(
                GatewayQuery.cancelEnded(nextId(), exchangeId(listing), securityId(listing), order.exchangeOrderId()),
                "a cancel of " + order.exchangeOrderId());
        return true;
    }

    @Override
    public boolean cancel(final AccountOrder order) {
        throw new UnsupportedOperationException("a session cancels orders only on its own listings");
    }

    private List<VenueOrder> ask(final GatewayQuery query, final String what) throws IOException {
        if (!this.client.ask(query)) {
            throw new IOException("another venue query is still waiting for the gateway");
        }
        final QueryStatus status;
        try {
            status = query.await(this.timeoutNanos);
        } catch (InterruptedException e) {
            this.client.forget(query);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted waiting for the gateway's answer about " + what);
        }
        if (status == null) {
            this.client.forget(query);
            throw new IOException("no answer from the gateway about " + what);
        }
        if (status == QueryStatus.REFUSED) {
            throw new IOException(
                    "the gateway refused " + what + ": it isn't an ended session's order of this strategy");
        }
        if (status != QueryStatus.OK) {
            throw new IOException("the gateway couldn't ask the venue about " + what);
        }
        return query.orders;
    }

    private static long nextId() {
        return QUERY_IDS.incrementAndGet();
    }

    private static int exchangeId(final Listing listing) {
        return listing.exchange().exchangeId();
    }

    private static long securityId(final Listing listing) {
        return listing.security().securityId();
    }
}
