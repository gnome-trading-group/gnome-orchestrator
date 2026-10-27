package group.gnometrading.collectors;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * Whether the collector process can still record: its agent keeps cycling, so every stream's ring is drained, its
 * runtime keeps ticking, so assignments, rolls and retries still happen, and its uploader isn't stuck on one file. A stream that is quarantined or quiet doesn't fail it; that is the stream's own
 * state, in the heartbeat, and replacing the task wouldn't bring a quarantined venue back any sooner.
 */
final class CollectorHealth implements BooleanSupplier {

    static final long AGENT_STALL_NANOS = TimeUnit.SECONDS.toNanos(30);
    // An upload's own retries take under a minute; one in progress this long is hung in the client.
    static final long UPLOAD_STALL_NANOS = TimeUnit.MINUTES.toNanos(5);
    // A tick that rolls a connection waits up to 70 s for it; one this long is hung.
    static final long RUNTIME_STALL_NANOS = TimeUnit.MINUTES.toNanos(5);

    private final LongSupplier agentCycles;
    private final LongSupplier lastTickNanos;
    private final LongPredicate uploaderStuckAt;
    private final LongSupplier nanoTime;
    private final long startNanos;
    private long lastCycles = -1;
    private long lastAdvanceNanos;

    /** @param lastTickNanos when the runtime's last tick finished, on {@code nanoTime}'s clock; 0 before the first */
    CollectorHealth(
            LongSupplier agentCycles,
            LongSupplier lastTickNanos,
            LongPredicate uploaderStuckAt,
            LongSupplier nanoTime) {
        this.agentCycles = agentCycles;
        this.lastTickNanos = lastTickNanos;
        this.uploaderStuckAt = uploaderStuckAt;
        this.nanoTime = nanoTime;
        this.startNanos = nanoTime.getAsLong();
        this.lastAdvanceNanos = startNanos;
    }

    // Synchronized: the health server may answer two checks at once.
    @Override
    public synchronized boolean getAsBoolean() {
        final long now = nanoTime.getAsLong();
        final long cycles = agentCycles.getAsLong();
        if (cycles != lastCycles) {
            lastCycles = cycles;
            lastAdvanceNanos = now;
        }
        final long lastTick = lastTickNanos.getAsLong();
        final long ticked = lastTick == 0 ? startNanos : lastTick;
        return now - lastAdvanceNanos <= AGENT_STALL_NANOS
                && now - ticked <= RUNTIME_STALL_NANOS
                && !uploaderStuckAt.test(now);
    }
}
