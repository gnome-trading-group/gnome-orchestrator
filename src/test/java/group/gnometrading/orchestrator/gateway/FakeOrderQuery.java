package group.gnometrading.orchestrator.gateway;

import group.gnometrading.gateways.orders.recovery.AccountOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A venue's orders held in memory, by the id execution reports carry: what a test says the venue has. */
public final class FakeOrderQuery implements VenueOrderQuery {

    public final Map<String, VenueOrder> orders = new ConcurrentHashMap<>();
    public final List<String> cancelled = new CopyOnWriteArrayList<>();
    public final AtomicInteger lookups = new AtomicInteger();
    // When the venue says each order was placed, for the account-wide list; 0 when not set.
    public final Map<String, Long> created = new ConcurrentHashMap<>();
    public volatile IOException listAllFails;

    @Override
    public List<VenueOrder> listOpenOrders(final Listing listing) {
        return this.orders.values().stream().filter(order -> !order.terminal()).toList();
    }

    @Override
    public Optional<VenueOrder> getOrder(
            final Listing listing, final String exchangeOrderId, final long createdAfterMs) {
        this.lookups.incrementAndGet();
        return Optional.ofNullable(this.orders.get(exchangeOrderId));
    }

    /** Ends the order where it stands, as a venue taking the cancel would. */
    @Override
    public boolean cancel(final Listing listing, final VenueOrder order) {
        this.cancelled.add(order.exchangeOrderId());
        this.orders.put(
                order.exchangeOrderId(),
                new VenueOrder(
                        order.exchangeOrderId(),
                        order.venueId(),
                        order.filledQty(),
                        order.filledNotional(),
                        order.fees(),
                        true));
        return true;
    }

    @Override
    public List<AccountOrder> listAllOpenOrders() throws IOException {
        if (this.listAllFails != null) {
            throw this.listAllFails;
        }
        return this.orders.values().stream()
                .filter(order -> !order.terminal())
                .map(order -> new AccountOrder(order, "MARKET", this.created.getOrDefault(order.exchangeOrderId(), 0L)))
                .toList();
    }

    @Override
    public boolean cancel(final AccountOrder order) {
        return cancel(null, order.order());
    }
}
