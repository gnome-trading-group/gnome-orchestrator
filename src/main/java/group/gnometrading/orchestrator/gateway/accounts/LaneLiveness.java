package group.gnometrading.orchestrator.gateway.accounts;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Whether an account's lane is still running, read by its Polymarket Intl heartbeat keeper before every beat: the lane's
 * runner counts its passes, idle or not, so passes that stop moving mean the lane is stuck or gone, and the keeper then
 * lets the venue cancel the key's orders rather than keep them alive with nothing behind them. Read on one thread;
 * allocates nothing.
 */
public final class LaneLiveness implements BooleanSupplier {

    public static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final LongSupplier passes;
    private final LongSupplier nanoClock;
    private final long stallNanos;
    private long lastPasses = -1;
    private long lastMovedNanos;

    public LaneLiveness(final LongSupplier passes, final LongSupplier nanoClock, final long stallNanos) {
        this.passes = passes;
        this.nanoClock = nanoClock;
        this.stallNanos = stallNanos;
    }

    @Override
    public boolean getAsBoolean() {
        final long now = this.nanoClock.getAsLong();
        final long passes = this.passes.getAsLong();
        if (passes != this.lastPasses) {
            this.lastPasses = passes;
            this.lastMovedNanos = now;
        }
        return passes > 0 && now - this.lastMovedNanos < this.stallNanos;
    }
}
