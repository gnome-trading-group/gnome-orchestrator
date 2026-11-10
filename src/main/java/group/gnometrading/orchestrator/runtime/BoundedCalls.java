package group.gnometrading.orchestrator.runtime;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Blocking calls to one client (a venue's API, the registry) made with a deadline, so the thread asking never waits
 * past it however long the client takes. Calls run one at a time on a daemon thread of their own, as the clients aren't
 * safe to share between threads: one still running past its deadline holds the ones after it, which time out too
 * until it returns. Allocates; never for the order path.
 */
public final class BoundedCalls {

    private final ExecutorService executor;

    /** @param name the thread's name, for stacks and logs */
    public BoundedCalls(final String name) {
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * @return what the call returned
     * @throws TimeoutException when it didn't return within {@code timeoutMillis}; it is interrupted, and left to finish
     * @throws Exception what the call threw
     */
    public <T> T call(final Callable<T> call, final long timeoutMillis) throws Exception {
        final Future<T> result = this.executor.submit(call);
        try {
            return result.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            result.cancel(true);
            throw new TimeoutException("no answer within " + timeoutMillis + " ms");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
