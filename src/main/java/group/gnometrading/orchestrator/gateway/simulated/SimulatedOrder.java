package group.gnometrading.orchestrator.gateway.simulated;

import group.gnometrading.gateways.account.admission.AdmissionEntry;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;

/**
 * One order a simulated account holds for a session, from its NEW until it ends: queued in the session's admission
 * until its budget lets it go, then on its book. Pooled.
 */
final class SimulatedOrder extends AdmissionEntry {

    long slot;
    long counter;
    int strategyId;
    int exchangeId;
    long securityId;
    Side side;
    long price;
    long size;
    TimeInForce timeInForce;
    OrderType orderType;
    boolean postOnly;
    // Its submit has gone to its book; until then a cancel ends it here.
    boolean sent;
    // An amend asked for while one already waited for the budget replaces it.
    boolean amendWanted;
    long amendPrice;
    long amendSize;
    boolean amendPostOnly;
    SimulatedBook book;
    // For its requests' timings, epoch: when ingress queued, and the lane took, its submit, its latest cancel and its
    // latest amend.
    long submitIngressNanos;
    long submitTakenNanos;
    long cancelIngressNanos;
    long cancelTakenNanos;
    long amendIngressNanos;
    long amendTakenNanos;
    // The request sent to its book that awaits its first report, and when it went; null when none does.
    RequestClass awaiting;
    long awaitingSentNanos;

    SimulatedOrder(final int index) {
        super(index);
    }

    @Override
    public long sessionSlot() {
        return this.slot;
    }

    @Override
    public boolean stillQueued(final int kind) {
        return kind == SUBMIT ? !this.sent : this.amendWanted;
    }

    void reuse() {
        resetQueueing();
        this.slot = 0;
        this.counter = 0;
        this.strategyId = 0;
        this.exchangeId = 0;
        this.securityId = 0;
        this.side = null;
        this.price = 0;
        this.size = 0;
        this.timeInForce = null;
        this.orderType = null;
        this.postOnly = false;
        this.sent = false;
        this.amendWanted = false;
        this.amendPrice = 0;
        this.amendSize = 0;
        this.amendPostOnly = false;
        this.book = null;
        this.submitIngressNanos = 0;
        this.submitTakenNanos = 0;
        this.cancelIngressNanos = 0;
        this.cancelTakenNanos = 0;
        this.amendIngressNanos = 0;
        this.amendTakenNanos = 0;
        this.awaiting = null;
        this.awaitingSentNanos = 0;
    }
}
