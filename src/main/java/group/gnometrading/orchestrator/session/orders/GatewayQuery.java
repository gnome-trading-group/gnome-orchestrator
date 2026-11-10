package group.gnometrading.orchestrator.session.orders;

import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.schemas.gateway.QueryKind;
import group.gnometrading.schemas.gateway.QueryStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One question a starting session asks the venue through its gateway, handed from the starting thread to the gateway
 * client's thread, which sends it once the link is ready and collects the answer's pages. Startup only: allocates.
 */
final class GatewayQuery {

    final long queryId;
    final int exchangeId;
    final long securityId;
    // OPEN_ORDERS or BY_EXCHANGE_ORDER_ID; null for a cancel of an ended session's order.
    final QueryKind kind;
    final String exchangeOrderId;

    // The client thread's: whether it went out, and the answer's orders so far.
    boolean sent;
    final List<VenueOrder> orders = new ArrayList<>();
    private volatile QueryStatus status;
    private final CountDownLatch answered = new CountDownLatch(1);

    private GatewayQuery(
            final long queryId,
            final int exchangeId,
            final long securityId,
            final QueryKind kind,
            final String exchangeOrderId) {
        this.queryId = queryId;
        this.exchangeId = exchangeId;
        this.securityId = securityId;
        this.kind = kind;
        this.exchangeOrderId = exchangeOrderId;
    }

    static GatewayQuery openOrders(final long queryId, final int exchangeId, final long securityId) {
        return new GatewayQuery(queryId, exchangeId, securityId, QueryKind.OPEN_ORDERS, "");
    }

    static GatewayQuery byExchangeOrderId(
            final long queryId, final int exchangeId, final long securityId, final String exchangeOrderId) {
        return new GatewayQuery(queryId, exchangeId, securityId, QueryKind.BY_EXCHANGE_ORDER_ID, exchangeOrderId);
    }

    static GatewayQuery cancelEnded(
            final long queryId, final int exchangeId, final long securityId, final String exchangeOrderId) {
        return new GatewayQuery(queryId, exchangeId, securityId, null, exchangeOrderId);
    }

    boolean isCancel() {
        return this.kind == null;
    }

    /** The client thread, on the answer's last page. */
    void answered(final QueryStatus answer) {
        this.status = answer;
        this.answered.countDown();
    }

    /** @return the answer's status, or null if none came in time */
    QueryStatus await(final long timeoutNanos) throws InterruptedException {
        return this.answered.await(timeoutNanos, TimeUnit.NANOSECONDS) ? this.status : null;
    }
}
