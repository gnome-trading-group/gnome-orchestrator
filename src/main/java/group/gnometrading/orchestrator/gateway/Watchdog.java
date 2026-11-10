package group.gnometrading.orchestrator.gateway;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.runtime.ProcessExit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Exits the process when a thread on the order path stops making progress: ingress, egress, a lane or an account's
 * reader whose passes haven't moved for the stall time. A hung lane would leave orders resting with nothing to cancel
 * them on a venue without a dead-man switch, and the container's health check takes over a minute to act; exiting lets ECS restart
 * the gateway at once. A thread that is waiting on purpose (a reader paused while its socket reconnects) is excused.
 */
public final class Watchdog {

    /** One thread to watch, and how long it may go without progress. */
    /** @param thread the watched thread's name, whose stack is logged when it stalls; null when it has no one thread */
    record CheckIn(String name, String thread, LongSupplier passes, BooleanSupplier excused, long stallNanos) {}

    // How many of a stalled thread's frames go into the reason it exits with.
    private static final int REASON_FRAMES = 4;

    private final Logger logger;
    private final List<CheckIn> checkIns;
    private final long[] lastPasses;
    private final long[] lastMovedNanos;
    private final LongSupplier nanoClock;
    private final long checkIntervalMillis;
    private final ProcessExit exit;
    private boolean fired;

    Watchdog(final Logger logger, final List<CheckIn> checkIns, final LongSupplier nanoClock, final ProcessExit exit) {
        this.logger = logger;
        this.checkIns = List.copyOf(checkIns);
        this.lastPasses = new long[checkIns.size()];
        this.lastMovedNanos = new long[checkIns.size()];
        this.nanoClock = nanoClock;
        this.checkIntervalMillis = Math.max(
                1,
                TimeUnit.NANOSECONDS.toMillis(checkIns.stream()
                                .mapToLong(CheckIn::stallNanos)
                                .min()
                                .orElse(1_000_000_000L))
                        / 10);
        this.exit = exit;
        final long now = nanoClock.getAsLong();
        for (int i = 0; i < checkIns.size(); i++) {
            this.lastPasses[i] = -1;
            this.lastMovedNanos[i] = now;
        }
    }

    /** Checks every thread once; exits the process, once, if one has stalled. */
    void check() {
        final long now = this.nanoClock.getAsLong();
        for (int i = 0; i < this.checkIns.size(); i++) {
            final CheckIn checkIn = this.checkIns.get(i);
            final long passes = checkIn.passes().getAsLong();
            if (passes != this.lastPasses[i] || checkIn.excused().getAsBoolean()) {
                this.lastPasses[i] = passes;
                this.lastMovedNanos[i] = now;
                continue;
            }
            if (now - this.lastMovedNanos[i] > checkIn.stallNanos() && !this.fired) {
                this.fired = true;
                final StackTraceElement[] stack = stackOf(checkIn.thread());
                final String why = checkIn.name() + " made no progress for "
                        + TimeUnit.NANOSECONDS.toMillis(now - this.lastMovedNanos[i]) + " ms" + at(stack);
                this.logger.logf(LogMessage.FATAL_ERROR_EXITING, "Gateway watchdog: %s", why);
                if (stack.length > 0) {
                    this.logger.logf(
                            LogMessage.FATAL_ERROR_EXITING,
                            "Gateway watchdog: %s's stack:%n  %s",
                            checkIn.thread(),
                            Arrays.stream(stack).map(String::valueOf).collect(Collectors.joining("\n  ")));
                }
                final IllegalStateException stalled = new IllegalStateException(why);
                stalled.setStackTrace(stack);
                this.exit.exit(1, stalled);
            }
        }
    }

    /** Checks every tenth of the shortest stall time on a daemon thread of its own. */
    Thread start() {
        final long interval = this.checkIntervalMillis;
        final Thread thread = new Thread(
                () -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        check();
                        try {
                            Thread.sleep(interval);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                },
                "gateway-watchdog");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Where the named thread is now; empty when there is no such thread. */
    static StackTraceElement[] stackOf(final String threadName) {
        if (threadName == null) {
            return new StackTraceElement[0];
        }
        for (final Map.Entry<Thread, StackTraceElement[]> thread :
                Thread.getAllStackTraces().entrySet()) {
            if (threadName.equals(thread.getKey().getName())) {
                return thread.getValue();
            }
        }
        return new StackTraceElement[0];
    }

    /** What it is blocked in, then the first of its own frames under that: the JDK's say little about why. */
    private static String at(final StackTraceElement[] stack) {
        if (stack.length == 0) {
            return "";
        }
        final String own = Arrays.stream(stack)
                .skip(1)
                .filter(frame -> !isJdk(frame.getClassName()))
                .limit(REASON_FRAMES - 1)
                .map(String::valueOf)
                .collect(Collectors.joining(" <- "));
        return ", at " + stack[0] + (own.isEmpty() ? "" : " ... " + own);
    }

    private static boolean isJdk(final String className) {
        return className.startsWith("java.") || className.startsWith("jdk.") || className.startsWith("sun.");
    }
}
