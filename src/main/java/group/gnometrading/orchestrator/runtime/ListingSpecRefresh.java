package group.gnometrading.orchestrator.runtime;

import group.gnometrading.ListingSpecRefresher;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.utils.CauseChain;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a process's cached listing specs current on a thread of its own: a venue can change a listing's tick while it
 * runs, and the OMS checks every order against the spec in force. A failed refresh keeps the specs it had and is tried
 * again next time.
 */
public final class ListingSpecRefresh {

    private ListingSpecRefresh() {}

    /**
     * Refreshes every {@code intervalMillis}, then runs {@code afterEach} on the same thread.
     *
     * @param refresher with a registry connection for this thread alone
     */
    public static ScheduledExecutorService start(
            final Logger logger,
            final ListingSpecRefresher refresher,
            final long intervalMillis,
            final Runnable afterEach) {
        final ScheduledExecutorService thread = DaemonThreads.scheduler("listing-spec-refresh");
        thread.scheduleWithFixedDelay(
                () -> refreshOnce(logger, refresher, afterEach), intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return thread;
    }

    static void refreshOnce(final Logger logger, final ListingSpecRefresher refresher, final Runnable afterEach) {
        // Nothing may escape: a scheduled task that throws is never run again.
        try {
            final ListingSpecRefresher.Result result = refresher.refresh();
            if (result.changed() > 0) {
                logger.logf(LogMessage.DEBUG, "Listing specs: %d listings' specs changed", result.changed());
            }
            if (result.failed() > 0) {
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Listing spec refresh: %d listings unchecked, keeping their specs: %s",
                        result.failed(),
                        CauseChain.of(result.lastError()));
            }
            afterEach.run();
        } catch (RuntimeException e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Listing spec refresh failed: %s", CauseChain.of(e));
        }
    }
}
