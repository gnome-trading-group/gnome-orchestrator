package group.gnometrading.collectors;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.utils.CauseChain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Drains every stream's ring into its minute files on one thread, the only one that writes them. Streams join and
 * leave while it runs: a leaving one is drained for {@link #RETIRE_GRACE_NANOS} more, for whatever its market's reader
 * was publishing as it left, then flushed.
 *
 * <p>Receive timestamps are stamped by the connections' readers, not here, so this agent can sleep when idle without
 * delaying them. It only has to keep up well enough that no ring fills and blocks its reader.
 *
 * <p>Every {@link #EXPIRE_EVERY_NANOS} it also hands over minutes that have ended by the wall clock, so a quiet
 * market's minute is written when it ends, not whenever its next record arrives.
 */
public final class MarketDataCollectorAgent implements GnomeAgent {

    static final long RETIRE_GRACE_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final long EXPIRE_EVERY_NANOS = TimeUnit.SECONDS.toNanos(1);

    private record Join(ShardStream stream) {}

    private record Leave(ShardStream stream, long atNanos, CountDownLatch done) {}

    private final Logger logger;
    private final LongSupplier nanoTime;
    private final LongSupplier wallNanos;
    private long nextExpireNanos;
    private final ConcurrentLinkedQueue<Object> commands = new ConcurrentLinkedQueue<>();
    private final List<ShardStream> streams = new ArrayList<>();
    private final List<Leave> leaving = new ArrayList<>();

    /**
     * @param nanoTime a monotonic clock, for timing
     * @param wallNanos epoch nanoseconds, for which minutes have ended
     */
    MarketDataCollectorAgent(final Logger logger, final LongSupplier nanoTime, final LongSupplier wallNanos) {
        this.logger = logger;
        this.nanoTime = nanoTime;
        this.wallNanos = wallNanos;
    }

    /** From any thread: starts draining the stream. */
    void add(final ShardStream stream) {
        commands.add(new Join(stream));
    }

    /** From any thread: drains the stream a moment longer, flushes it, then counts {@code done} down if given. */
    void retire(final ShardStream stream, final CountDownLatch done) {
        commands.add(new Leave(stream, nanoTime.getAsLong() + RETIRE_GRACE_NANOS, done));
    }

    @Override
    public int doWork() throws Exception {
        Object command;
        while ((command = commands.poll()) != null) {
            if (command instanceof Join join) {
                streams.add(join.stream());
            } else if (command instanceof Leave leave) {
                streams.remove(leave.stream());
                leaving.add(leave);
            }
        }
        final long now = nanoTime.getAsLong();
        int work = 0;
        for (int i = 0; i < streams.size(); i++) {
            work += streams.get(i).poll(now);
        }
        expire(now);
        for (int i = leaving.size() - 1; i >= 0; i--) {
            final Leave leave = leaving.get(i);
            final int drained = leave.stream().poll(now);
            work += drained;
            if (drained == 0 && now >= leave.atNanos()) {
                leaving.remove(i);
                flush(leave.stream());
                if (leave.done() != null) {
                    leave.done().countDown();
                }
            }
        }
        return work;
    }

    private void expire(final long now) {
        if (now < nextExpireNanos) {
            return;
        }
        nextExpireNanos = now + EXPIRE_EVERY_NANOS;
        final long wall = wallNanos.getAsLong();
        for (int i = 0; i < streams.size(); i++) {
            streams.get(i).expire(wall);
        }
    }

    private void flush(final ShardStream stream) {
        try {
            stream.flush();
        } catch (Throwable error) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Failed to flush %s: %s", stream.name(), CauseChain.of(error));
        }
    }

    @Override
    public String roleName() {
        return "market-data-collector";
    }
}
