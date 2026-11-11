package group.gnometrading.orchestrator.gateway;

import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.gateway.accounts.GatewayAccount;
import group.gnometrading.orchestrator.gateway.control.GatewayControl;
import group.gnometrading.orchestrator.gateway.control.SessionRegistry;
import group.gnometrading.orchestrator.gateway.control.SessionsFile;
import group.gnometrading.orchestrator.gateway.journal.OwnershipLog;
import group.gnometrading.orchestrator.gateway.transport.CrossIndex;
import group.gnometrading.sm.Listing;
import group.gnometrading.utils.CauseChain;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * What a gateway takes back from the process before it, before any of its threads start: the sessions it had accepted
 * (whose slot each order is, and what the registry said of them) and, per account, the orders its ownership log still
 * holds open. Each order's listing is made tradeable and the order handed to the account's lane (never sent again) and
 * reader (its events and catch-up apply as to any other); the log then records the lane's orders from here on.
 *
 * <p>Sessions then come back with a hello and are reconciled ({@link GatewayControl.Recovered}); one that doesn't has its
 * orders cancelled. An order the log never held (the machine was lost with its log) isn't known here: its events find
 * no order; a session still holding it gets it looked up through its hello, and {@link StartupSweep} cancels what no
 * session claims.
 */
public final class GatewayRestart {

    /** What the restart took back. */
    /**
     * @param loaded per account, the listings made tradeable on it to take its orders back, by listing key
     * @param held per account, the ids execution reports carry of the orders taken back, for the startup sweep
     */
    record Result(
            SessionsFile sessionsFile,
            List<GatewayControl.Recovered> sessions,
            List<OwnershipLog> logs,
            Map<Integer, Set<Long>> loaded,
            Map<Integer, Set<String>> held) {}

    private GatewayRestart() {}

    static Result recover(
            final Logger logger,
            final Path directory,
            final long logBytes,
            final List<GatewayAccount> accounts,
            final GatewayControl.Listings listings,
            final SessionRegistry registry,
            final long nowNanos,
            final long nowMillis,
            final CrossIndex cross)
            throws IOException {
        final List<SessionsFile.Entry> entries = new ArrayList<>();
        final SessionsFile sessionsFile = SessionsFile.open(directory.resolve("sessions.jsonl"), nowMillis, entries);
        final Map<Long, SessionsFile.Entry> bySlot = new HashMap<>();
        for (final SessionsFile.Entry entry : entries) {
            bySlot.put(entry.slot(), entry);
            registry.seed(
                    entry.sessionId(),
                    entry.record(),
                    nowNanos - TimeUnit.MILLISECONDS.toNanos(nowMillis - entry.acceptedAtMillis()));
        }

        final Map<Long, Set<Long>> counters = new HashMap<>();
        final Map<Long, int[]> owners = new HashMap<>();
        final List<OwnershipLog> logs = new ArrayList<>();
        final Map<Integer, Set<Long>> loadedByAccount = new HashMap<>();
        final Map<Integer, Set<String>> heldByAccount = new HashMap<>();
        for (final GatewayAccount account : accounts) {
            final List<OrderContext> open = new ArrayList<>();
            final OwnershipLog log =
                    OwnershipLog.open(directory.resolve("account-" + account.accountId() + ".log"), logBytes, ctx -> {
                        final OrderContext copy = new OrderContext();
                        copy.copyFrom(ctx);
                        open.add(copy);
                    });
            final Set<Long> loaded = new HashSet<>();
            loadedByAccount.put(account.accountId(), loaded);
            final Set<String> held = new HashSet<>();
            heldByAccount.put(account.accountId(), held);
            for (final OrderContext ctx : open) {
                if (recoverOrder(logger, account, listings, loaded, ctx)) {
                    held.add(new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII));
                    cross.restore(
                            ctx.sessionSlot,
                            ctx.clientOidCounter,
                            PublishedMap.listingKey(ctx.exchangeId, ctx.securityId),
                            ctx.side,
                            ctx.limitPrice);
                    counters.computeIfAbsent(ctx.sessionSlot, slot -> new HashSet<>())
                            .add(ctx.clientOidCounter);
                    owners.putIfAbsent(ctx.sessionSlot, new int[] {account.accountId(), ctx.clientOidStrategyId});
                }
            }
            log.held(account.lane()::forEachHeld);
            account.lane().ownership(log);
            logs.add(log);
            logger.logf(
                    LogMessage.DEBUG,
                    "Gateway restart: %s took back %d of %d open orders",
                    account.name(),
                    account.lane().workingOrders(),
                    open.size());
        }

        final List<GatewayControl.Recovered> sessions = new ArrayList<>();
        for (final Map.Entry<Long, Set<Long>> slot : counters.entrySet()) {
            final SessionsFile.Entry entry = bySlot.get(slot.getKey());
            final int[] owner = owners.get(slot.getKey());
            sessions.add(new GatewayControl.Recovered(
                    slot.getKey(),
                    entry == null ? null : entry.sessionId(),
                    entry == null ? owner[1] : entry.strategyId(),
                    owner[0],
                    entry == null ? 0 : TimeUnit.MILLISECONDS.toNanos(entry.codTimeoutMs()),
                    slot.getValue()));
        }
        return new Result(sessionsFile, sessions, logs, loadedByAccount, heldByAccount);
    }

    /** @return whether the lane and reader hold the order */
    private static boolean recoverOrder(
            final Logger logger,
            final GatewayAccount account,
            final GatewayControl.Listings listings,
            final Set<Long> loaded,
            final OrderContext ctx) {
        final Listing listing = listings.listing(ctx.exchangeId, ctx.securityId);
        if (listing == null) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "GATEWAY_UNATTRIBUTED_EVENTS: restart has no listing %d/%d for order %d of slot %d; it rests"
                            + " at the venue unheld",
                    ctx.exchangeId,
                    ctx.securityId,
                    ctx.clientOidCounter,
                    ctx.sessionSlot);
            return false;
        }
        final long key = PublishedMap.listingKey(ctx.exchangeId, ctx.securityId);
        if (!loaded.contains(key)) {
            try {
                account.markets().load(listing, listings.fees(listing));
                loaded.add(key);
            } catch (Exception e) {
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "GATEWAY_UNATTRIBUTED_EVENTS: restart can't make listing %d tradeable, so its order rests at the"
                                + " venue unheld: %s",
                        listing.listingId(),
                        CauseChain.of(e));
                return false;
            }
        }
        if (!account.lane().recover(ctx)) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "GATEWAY_UNATTRIBUTED_EVENTS: restart: %s can't hold order %d of slot %d; it rests at the venue"
                            + " unheld",
                    account.name(),
                    ctx.clientOidCounter,
                    ctx.sessionSlot);
            return false;
        }
        account.reader().recover(ctx);
        return true;
    }
}
