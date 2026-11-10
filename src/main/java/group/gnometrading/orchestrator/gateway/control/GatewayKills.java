package group.gnometrading.orchestrator.gateway.control;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.gateway.core.GatewayAlerts;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.KillRules;
import group.gnometrading.orchestrator.gateway.core.Kills;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import group.gnometrading.utils.CauseChain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The gateway's kills, a second line behind each session's OMS: once a second it reads the kill switches in force,
 * publishes them for ingress to refuse what they cover ({@link Kills}), and asks each lane to cancel the covered orders
 * of every session it holds. A kill's cancels go on the pass it comes into force and once more on the next: an order
 * ingress let through just before the kill was published reaches its lane after the first sweep, and the second
 * catches it. Sweeping every pass after that would only ask again about orders already being cancelled, each a
 * refusal back to the session when the venue's id isn't known yet. A session first covered by a kill already in
 * force, as one that says hello while it stands, is swept the same way.
 *
 * <p>Cancels go order by order, by session, so a kill of one strategy never touches another's orders on the same
 * listing. A read that fails keeps the kills already in force: lifting them for want of an answer would be the unsafe
 * way to fail, and refusing everything would stop sessions the OMS already guards. Kills older than {@link #STALE_NANOS}
 * are said so once.
 *
 * <p>Runs on its own thread; allocates freely.
 */
public final class GatewayKills implements Runnable {

    /** How long the kills may go unread before that is logged. */
    public static final long STALE_NANOS = TimeUnit.SECONDS.toNanos(30);

    static final String STALE_ALERT = "GATEWAY_KILLS_STALE";

    /** A listing's key from its registry id, or 0 if the security master doesn't have it. */
    @FunctionalInterface
    public interface ListingKeys {
        long keyOf(int listingId);
    }

    private final Logger logger;
    private final GatewayAlerts alerts;
    private final KillSource source;
    private final Kills kills;
    private final SessionTable sessions;
    private final ListingKeys listingKeys;
    private final LongSupplier nanoClock;
    private final Map<Integer, Long> listingKeyCache = new HashMap<>();
    private List<KillSource.Kill> current = List.of();
    private Set<String> described = Set.of();
    // What each pass swept, as "rule|slot": a pair is swept on its first two passes only.
    private Set<String> sweptOnce = Set.of();
    private Set<String> sweptTwice = Set.of();
    private long readAtNanos;
    private boolean staleSaid;

    public GatewayKills(
            final Logger logger,
            final GatewayAlerts alerts,
            final KillSource source,
            final Kills kills,
            final SessionTable sessions,
            final ListingKeys listingKeys,
            final LongSupplier nanoClock) {
        this.logger = logger;
        this.alerts = alerts;
        this.source = source;
        this.kills = kills;
        this.sessions = sessions;
        this.listingKeys = listingKeys;
        this.nanoClock = nanoClock;
        this.readAtNanos = nanoClock.getAsLong();
    }

    /** One pass: read, publish and sweep. Never throws, so a scheduler keeps running it. */
    @Override
    public void run() {
        try {
            pass();
        } catch (RuntimeException e) {
            this.logger.logf(LogMessage.UNKNOWN_ERROR, "Gateway kills: pass failed: %s", CauseChain.of(e));
        }
    }

    private void pass() {
        read();
        final KillRules rules = rules(this.current);
        this.kills.publish(rules);
        say(rules);
        sweep(rules);
    }

    private void read() {
        final long now = this.nanoClock.getAsLong();
        try {
            this.current = this.source.fetch();
            this.readAtNanos = now;
            if (this.staleSaid) {
                this.staleSaid = false;
                this.logger.logf(LogMessage.DEBUG, "Gateway kills: read again");
                this.alerts.resolved(STALE_ALERT, "");
            }
        } catch (Exception e) {
            if (!this.staleSaid && now - this.readAtNanos > STALE_NANOS) {
                this.staleSaid = true;
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "GATEWAY_KILLS_STALE: kill switches unread for %d s, the last ones kept: %s",
                        TimeUnit.NANOSECONDS.toSeconds(now - this.readAtNanos),
                        CauseChain.of(e));
                this.alerts.firing(
                        STALE_ALERT,
                        "",
                        "Gateway can't read the kill switches: a kill turned on now won't halt its sessions",
                        Map.of(
                                "unreadSeconds",
                                Long.toString(TimeUnit.NANOSECONDS.toSeconds(now - this.readAtNanos)),
                                "error",
                                String.valueOf(CauseChain.of(e))));
            }
        }
    }

    /**
     * The kills as rules over what the gateway sees: a session by its slot, a listing by its key. A kill of a session
     * the gateway doesn't hold, or a listing the security master doesn't know, covers nothing here yet and is resolved
     * again on the next pass.
     */
    KillRules rules(final List<KillSource.Kill> kills) {
        final Map<String, Long> slots = new HashMap<>();
        for (final GatewaySession session : this.sessions.all()) {
            final String sessionId = session.sessionId;
            if (sessionId != null) {
                slots.put(sessionId, session.slot);
            }
        }
        final List<KillSource.Kill> resolved = new ArrayList<>();
        final List<long[]> targets = new ArrayList<>();
        for (final KillSource.Kill kill : kills) {
            long slot = 0;
            if (kill.sessionId() != null) {
                final Long found = slots.get(kill.sessionId());
                if (found == null) {
                    continue;
                }
                slot = found;
            }
            long listingKey = 0;
            if (kill.listingId() > 0) {
                listingKey = this.listingKeyCache.computeIfAbsent(kill.listingId(), this.listingKeys::keyOf);
                if (listingKey == 0) {
                    this.listingKeyCache.remove(kill.listingId());
                    continue;
                }
            }
            resolved.add(kill);
            targets.add(new long[] {slot, listingKey});
        }
        final int[] accountIds = new int[resolved.size()];
        final int[] exchangeIds = new int[resolved.size()];
        final long[] listingKeys = new long[resolved.size()];
        final int[] strategyIds = new int[resolved.size()];
        final long[] slotsOf = new long[resolved.size()];
        for (int i = 0; i < resolved.size(); i++) {
            final KillSource.Kill kill = resolved.get(i);
            accountIds[i] = kill.accountId();
            exchangeIds[i] = kill.exchangeId();
            listingKeys[i] = targets.get(i)[1];
            strategyIds[i] = kill.strategyId();
            slotsOf[i] = targets.get(i)[0];
        }
        return new KillRules(accountIds, exchangeIds, listingKeys, strategyIds, slotsOf);
    }

    /** Logs each kill as it comes into force here and as it lifts. */
    private void say(final KillRules rules) {
        final Set<String> now = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            now.add(rules.describe(i));
        }
        for (final String kill : now) {
            if (!this.described.contains(kill)) {
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Gateway kill in force: %s; new orders refused and working ones cancelled",
                        kill);
            }
        }
        for (final String kill : this.described) {
            if (!now.contains(kill)) {
                this.logger.logf(LogMessage.DEBUG, "Gateway kill lifted: %s", kill);
            }
        }
        this.described = now;
    }

    /**
     * Asks each covered session's lane to cancel the orders a rule covers, on the first two passes a rule covers the
     * session; one a full lane ring held back is tried again next pass.
     */
    private void sweep(final KillRules rules) {
        final Set<String> covered = new HashSet<>();
        final Set<String> twice = new HashSet<>();
        for (final GatewaySession session : this.sessions.all()) {
            for (int i = 0; i < rules.size(); i++) {
                if (!rules.matches(i, session.account.accountId(), session.strategyId, session.slot)) {
                    continue;
                }
                final int exchangeId = rules.exchangeId(i);
                if (exchangeId != 0 && exchangeId != session.account.exchangeId()) {
                    continue;
                }
                final String pair = rules.describe(i) + "|" + session.slot;
                if (this.sweptTwice.contains(pair)) {
                    covered.add(pair);
                    twice.add(pair);
                } else if (cancel(session, rules.listingKey(i))) {
                    covered.add(pair);
                    if (this.sweptOnce.contains(pair)) {
                        twice.add(pair);
                    }
                }
            }
        }
        this.sweptOnce = covered;
        this.sweptTwice = twice;
    }

    /** @return false if the lane's ring had no room, so the sweep is owed again */
    private static boolean cancel(final GatewaySession session, final long listingKey) {
        final ManyToOneRingBuffer<LaneCommand> lane = session.account.commands();
        final int index = lane.tryClaim();
        if (index < 0) {
            return false;
        }
        final LaneCommand command = lane.indexAt(index);
        command.reset();
        command.kind = LaneCommand.CANCEL_ORDERS;
        command.sessionSlot = session.slot;
        command.exchangeId = (int) (listingKey >>> 32);
        command.securityId = listingKey & 0xFFFF_FFFFL;
        lane.commit(index);
        return true;
    }
}
