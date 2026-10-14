package group.gnometrading.gateways;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.util.ArrayList;
import java.util.List;
import org.agrona.ErrorHandler;

/**
 * Reconnects a gateway after an error from its reader or supervisor, and exits the process once errors come too fast
 * to recover from. A dropped socket is one of these errors: the reader reports it so the gateway reconnects at once.
 */
public final class GatewayErrorHandler implements ErrorHandler {

    private static final int MAX_ERRORS_PER_WINDOW = 10;
    private static final long ERROR_WINDOW_MILLIS = 60_000;

    private final Logger logger;
    private final String gatewayName;
    private final Runnable forceReconnect;
    private final ProcessExit exit;
    private final List<Long> errorTimestamps = new ArrayList<>();

    public GatewayErrorHandler(
            final Logger logger, final String gatewayName, final Runnable forceReconnect, final ProcessExit exit) {
        this.logger = logger;
        this.gatewayName = gatewayName;
        this.forceReconnect = forceReconnect;
        this.exit = exit;
    }

    @Override
    public void onError(final Throwable error) {
        this.logger.logf(LogMessage.UNKNOWN_ERROR, "Error occurred in %s: %s", this.gatewayName, error.getMessage());

        // The gateway already retried for several connect cycles; another reconnect would only repeat them.
        if (error instanceof GatewayConnectFailedException) {
            this.logger.log(LogMessage.FATAL_ERROR_EXITING);
            this.exit.exit(1, error);
            return;
        }

        final long currentTime = System.currentTimeMillis();
        synchronized (this.errorTimestamps) {
            this.errorTimestamps.add(currentTime);
            this.errorTimestamps.removeIf(timestamp -> currentTime - timestamp > ERROR_WINDOW_MILLIS);

            if (this.errorTimestamps.size() >= MAX_ERRORS_PER_WINDOW) {
                this.logger.log(LogMessage.FATAL_ERROR_EXITING);
                this.exit.exit(
                        1,
                        new IllegalStateException(
                                MAX_ERRORS_PER_WINDOW + " errors in a minute from " + this.gatewayName + ", the last: "
                                        + error,
                                error));
                return;
            }
            this.forceReconnect.run();
        }
    }
}
