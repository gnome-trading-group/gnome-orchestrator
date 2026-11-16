package group.gnometrading.orchestrator.gateway.core;

import group.gnometrading.schemas.gateway.GatewayState;

/**
 * The gateway's own state, which the control thread sets and every thread reads: whether it takes orders, and the
 * epoch that tells a session a restart from a reconnect.
 */
public final class GatewayStatus {

    public final long epoch;
    private volatile GatewayState state = GatewayState.STARTING;
    private volatile boolean handingOver;

    public GatewayStatus(final long epoch) {
        this.epoch = epoch;
    }

    public GatewayState state() {
        return this.state;
    }

    public void state(final GatewayState state) {
        this.state = state;
    }

    /**
     * Stopping for a roll: orders are left resting for the next process on this host, so new ones and amends are
     * refused as a session refuses them while its link is down, which strategies treat as passing.
     */
    public void handOver() {
        this.handingOver = true;
        this.state = GatewayState.STOPPING;
    }

    public boolean handingOver() {
        return this.handingOver;
    }

    /** Whether new orders are taken: READY, or DEGRADED, where each account says for itself. */
    public boolean takesOrders() {
        final GatewayState current = this.state;
        return current == GatewayState.READY || current == GatewayState.DEGRADED;
    }
}
