package group.gnometrading.orchestrator.gateway;

import group.gnometrading.gateways.orders.recovery.AccountOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.gateway.core.GatewayAlerts;
import group.gnometrading.utils.CauseChain;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Cancels what rests on the gateway's accounts that it has no record of: orders an earlier process placed whose state
 * never reached this one (its host was lost or changed). The record is what the restart took back from the ownership
 * logs; orders this process places are newer than its start, so a venue's creation time tells them apart from the
 * earlier process's without reading the logs again.
 *
 * <p>It waits first: a session still holding such an order lists it in its hello, and that path looks it up and reports
 * it as the venue has it, so the session learns of the cancel from its own gateway. What no session claims by then is
 * cancelled here and alerted, as its fills may be missing from the ledger.
 *
 * <p>Runs once, on its own thread. Each account's queries go through its bounded query, one at a time with the control
 * thread's. A venue that can't be read is tried again every {@link #RETRY_MILLIS}, alerting past
 * {@link #ALERT_AFTER_MILLIS}.
 */
final class StartupSweep implements Runnable {

    static final long RETRY_MILLIS = 10_000;
    static final long ALERT_AFTER_MILLIS = 300_000;
    static final String FAILED = "GATEWAY_SWEEP_FAILED";
    static final String UNATTRIBUTED = "GATEWAY_UNATTRIBUTED_ORDERS";

    /** One account to sweep: its query and the ids (as execution reports carry them) of the orders the restart holds. */
    record Account(String name, VenueOrderQuery query, Set<String> held) {}

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final Logger logger;
    private final GatewayAlerts alerts;
    private final List<Account> accounts;
    private final long startedMillis;
    private final long waitMillis;
    private final LongSupplier clock;
    private final Sleeper sleeper;

    /**
     * @param startedMillis when this process started, before any account could send: orders the venue created earlier
     *     are the earlier process's
     * @param waitMillis how long after the start sessions have to come back and claim what they hold
     */
    StartupSweep(
            final Logger logger,
            final GatewayAlerts alerts,
            final List<Account> accounts,
            final long startedMillis,
            final long waitMillis,
            final LongSupplier clock,
            final Sleeper sleeper) {
        this.logger = logger;
        this.alerts = alerts;
        this.accounts = accounts;
        this.startedMillis = startedMillis;
        this.waitMillis = waitMillis;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public void run() {
        try {
            final long due = this.startedMillis + this.waitMillis;
            for (long now = this.clock.getAsLong(); now < due; now = this.clock.getAsLong()) {
                this.sleeper.sleep(due - now);
            }
            for (final Account account : this.accounts) {
                sweepUntilRead(account);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sweepUntilRead(final Account account) throws InterruptedException {
        final long firstTry = this.clock.getAsLong();
        boolean alerted = false;
        while (true) {
            try {
                sweep(account);
                if (alerted) {
                    this.alerts.resolved(FAILED, account.name());
                }
                return;
            } catch (IOException e) {
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Gateway %s: can't list its open orders to sweep them, trying again in %d s: %s",
                        account.name(),
                        RETRY_MILLIS / 1_000,
                        CauseChain.of(e));
                if (!alerted && this.clock.getAsLong() - firstTry >= ALERT_AFTER_MILLIS) {
                    alerted = true;
                    this.alerts.firing(
                            FAILED,
                            account.name(),
                            "Gateway can't list " + account.name()
                                    + "'s open orders, so orders an earlier gateway left may still rest",
                            Map.of("account", account.name()));
                }
                this.sleeper.sleep(RETRY_MILLIS);
            }
        }
    }

    private void sweep(final Account account) throws IOException {
        int cancelled = 0;
        for (final AccountOrder order : account.query().listAllOpenOrders()) {
            if (order.order().terminal()
                    || order.createdMillis() >= this.startedMillis
                    || account.held().contains(order.order().exchangeOrderId())) {
                continue;
            }
            final boolean accepted = account.query().cancel(order);
            cancelled++;
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "GATEWAY_UNATTRIBUTED_EVENTS: %s: order %s on %s rested with no record and no session claiming it; %s",
                    account.name(),
                    order.order().exchangeOrderId(),
                    order.market(),
                    accepted ? "cancelled" : "the venue refused the cancel");
            this.alerts.firing(
                    UNATTRIBUTED,
                    account.name() + ":" + order.order().exchangeOrderId(),
                    "Gateway " + (accepted ? "cancelled" : "couldn't cancel") + " order "
                            + order.order().exchangeOrderId() + " on " + order.market()
                            + " that no gateway or session had a record of: any fills it had may be missing from"
                            + " the ledger",
                    Map.of(
                            "account",
                            account.name(),
                            "order",
                            order.order().exchangeOrderId(),
                            "market",
                            order.market()));
        }
        this.logger.logf(
                LogMessage.DEBUG, "Gateway %s: swept its open orders, %d unrecorded", account.name(), cancelled);
    }
}
