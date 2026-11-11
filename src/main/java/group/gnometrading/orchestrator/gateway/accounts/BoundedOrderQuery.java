package group.gnometrading.orchestrator.gateway.accounts;

import group.gnometrading.gateways.orders.recovery.AccountOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.orchestrator.runtime.BoundedCalls;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * An account's venue order queries with a deadline on each, for the control thread, which asks them for starting sessions
 * and must keep passing: a venue that doesn't answer in time is an IOException like any failed query.
 */
public final class BoundedOrderQuery implements VenueOrderQuery {

    static final long TIMEOUT_MILLIS = 10_000;

    private final VenueOrderQuery venue;
    private final BoundedCalls calls;
    private final long timeoutMillis;

    public BoundedOrderQuery(final VenueOrderQuery venue, final String accountName) {
        this(venue, new BoundedCalls(accountName + "-venue-query"), TIMEOUT_MILLIS);
    }

    BoundedOrderQuery(final VenueOrderQuery venue, final BoundedCalls calls, final long timeoutMillis) {
        this.venue = venue;
        this.calls = calls;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public List<VenueOrder> listOpenOrders(final Listing listing) throws IOException {
        return bounded(() -> this.venue.listOpenOrders(listing));
    }

    @Override
    public Optional<VenueOrder> getOrder(final Listing listing, final String exchangeOrderId, final long createdAfterMs)
            throws IOException {
        return bounded(() -> this.venue.getOrder(listing, exchangeOrderId, createdAfterMs));
    }

    @Override
    public List<AccountOrder> listAllOpenOrders() throws IOException {
        return bounded(this.venue::listAllOpenOrders);
    }

    @Override
    public boolean cancel(final Listing listing, final VenueOrder order) throws IOException {
        return bounded(() -> this.venue.cancel(listing, order));
    }

    @Override
    public boolean cancel(final AccountOrder order) throws IOException {
        return bounded(() -> this.venue.cancel(order));
    }

    private <T> T bounded(final Callable<T> call) throws IOException {
        try {
            return this.calls.call(call, this.timeoutMillis);
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
