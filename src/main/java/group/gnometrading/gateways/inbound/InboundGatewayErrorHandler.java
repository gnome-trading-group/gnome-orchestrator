package group.gnometrading.gateways.inbound;

import group.gnometrading.gateways.GatewayConnectFailedException;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import org.agrona.ErrorHandler;

/**
 * Reconnects a market data gateway after an error, and exits the process once errors come too fast to recover from.
 */
public final class InboundGatewayErrorHandler implements ErrorHandler {

    private static final int MAX_ERRORS_PER_WINDOW = 10;
    private static final long ERROR_WINDOW_MILLIS = 60_000;

    private final Logger logger;
    private final Runnable forceReconnect;
    private final IntConsumer exit;
    private final List<Long> errorTimestamps = new ArrayList<>();

    public InboundGatewayErrorHandler(final Logger logger, final Runnable forceReconnect, final IntConsumer exit) {
        this.logger = logger;
        this.forceReconnect = forceReconnect;
        this.exit = exit;
    }

    @Override
    public void onError(final Throwable error) {
        this.logger.logf(LogMessage.UNKNOWN_ERROR, "Error occurred in market inbound gateway: %s", error.getMessage());

        // The gateway already retried for several connect cycles; another reconnect would only repeat them.
        if (error instanceof GatewayConnectFailedException) {
            this.logger.log(LogMessage.FATAL_ERROR_EXITING);
            this.exit.accept(1);
            return;
        }

        final long currentTime = System.currentTimeMillis();
        synchronized (this.errorTimestamps) {
            this.errorTimestamps.add(currentTime);
            this.errorTimestamps.removeIf(timestamp -> currentTime - timestamp > ERROR_WINDOW_MILLIS);

            if (this.errorTimestamps.size() >= MAX_ERRORS_PER_WINDOW) {
                this.logger.log(LogMessage.FATAL_ERROR_EXITING);
                this.exit.accept(1);
                return;
            }
            this.forceReconnect.run();
        }
    }
}
