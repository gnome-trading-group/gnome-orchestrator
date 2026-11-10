package group.gnometrading.orchestrator.gateway.accounts;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.utils.CauseChain;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Says when an account's connections to its venue can't be opened: the first failure, then at most once a minute
 * while they keep failing, with how many did, and once more when one opens again. Orders that find no connection are
 * refused, so failures here explain refusals the lane only counts. On the connector's thread; allocates when it logs.
 */
final class ConnectorLog implements HTTPConnector.Listener {

    static final long REPEAT_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final Logger logger;
    private final String account;
    private final LongSupplier nanoClock;
    private boolean failing;
    private long failuresSinceSaid;
    private long saidAtNanos;

    ConnectorLog(final Logger logger, final String account, final LongSupplier nanoClock) {
        this.logger = logger;
        this.account = account;
        this.nanoClock = nanoClock;
    }

    @Override
    public void onOpened(final String host, final int connection) {
        if (this.failing) {
            this.failing = false;
            this.logger.logf(LogMessage.DEBUG, "Gateway %s: connections to %s open again", this.account, host);
        }
    }

    @Override
    public void onOpenFailed(final String host, final int connection, final Exception cause) {
        final long now = this.nanoClock.getAsLong();
        this.failuresSinceSaid++;
        if (this.failing && now - this.saidAtNanos < REPEAT_NANOS) {
            return;
        }
        this.failing = true;
        this.saidAtNanos = now;
        this.logger.logf(
                LogMessage.UNKNOWN_ERROR,
                "Gateway %s: %d connection(s) to %s couldn't be opened: %s",
                this.account,
                this.failuresSinceSaid,
                host,
                CauseChain.of(cause));
        this.failuresSinceSaid = 0;
    }
}
