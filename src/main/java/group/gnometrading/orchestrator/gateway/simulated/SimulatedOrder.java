package group.gnometrading.orchestrator.gateway.simulated;

import group.gnometrading.gateways.account.admission.AdmissionEntry;
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
    }
}
