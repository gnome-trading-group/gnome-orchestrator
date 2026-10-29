package group.gnometrading.utils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Background schedulers that never keep the process alive on their own. */
public final class DaemonThreads {

    private DaemonThreads() {}

    /** A single daemon thread named {@code name}, for periodic work off the agents' threads. */
    public static ScheduledExecutorService scheduler(final String name) {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }
}
