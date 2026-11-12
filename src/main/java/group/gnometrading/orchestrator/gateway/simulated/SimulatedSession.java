package group.gnometrading.orchestrator.gateway.simulated;

import group.gnometrading.gateways.ratelimit.AccountBudget;
import group.gnometrading.gateways.ratelimit.RequestClass;
import java.util.List;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * A paper session on a simulated account: its own books, one per listing it trades here, and its own budget, as if it
 * were alone on the venue's account (paper sessions never contend with each other). Built on the control thread at its
 * hello, then the lane's alone.
 */
final class SimulatedSession {

    final long slot;
    final String sessionId;
    // Null where the venue limits nothing.
    final AccountBudget budget;
    final int newRule;
    final int amendRule;
    final List<SimulatedBook> books;
    final Long2ObjectHashMap<SimulatedBook> bookByListing = new Long2ObjectHashMap<>();
    final Long2ObjectHashMap<SimulatedOrder> orders = new Long2ObjectHashMap<>();

    SimulatedSession(
            final long slot, final String sessionId, final AccountBudget budget, final List<SimulatedBook> books) {
        this.slot = slot;
        this.sessionId = sessionId;
        this.budget = budget;
        this.newRule = budget == null ? -1 : budget.ruleFor(RequestClass.NEW);
        this.amendRule = budget == null ? -1 : budget.ruleFor(RequestClass.AMEND);
        this.books = books;
        for (final SimulatedBook book : books) {
            this.bookByListing.put(book.listingKey, book);
        }
    }

    /** Whether the budget lets a request of {@code rule} go now, taking its tokens if so. */
    boolean admits(final int rule, final RequestClass type, final long nowNanos) {
        return this.budget == null || rule < 0 || this.budget.tryAcquire(rule, type, nowNanos);
    }
}
