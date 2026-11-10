package group.gnometrading.orchestrator.gateway.core;

import group.gnometrading.orchestrator.aeron.PendingMessages;
import io.aeron.Publication;
import org.agrona.collections.LongHashSet;

/**
 * One session the gateway serves, under its slot (the registry's {@code session_seq}). It outlives the session's link:
 * a session that reconnects finds its replay ring, and after a restart the session's orders are reported into it before
 * the session says hello again.
 *
 * <p>The control thread writes what a hello settles (the link, the leases, the state); ingress reads it and writes what
 * the session's heartbeats say; egress alone numbers, holds and sends what goes to the session.
 */
public final class GatewaySession {

    /** Known, but with no link yet: after a restart, or before its hello. Its reports wait. */
    public static final int HELLO_PENDING = 0;

    /** Linked and trading. */
    public static final int ACTIVE = 1;

    /** Its link is gone or cut: its reports wait, its orders are refused until it says hello again. */
    public static final int UNLINKED = 2;

    public final long slot;
    // Null for a session whose orders a restart recovered without knowing whose: set at its hello, which the registry
    // vouches for by slot. Written by the control thread only.
    public volatile String sessionId;
    public final int strategyId;
    public final AccountLink account;
    public final ReplayRing replay;
    public final OrderTotals totals;

    public volatile int state = HELLO_PENDING;
    // The Aeron session id of the image its messages arrive on; 0 while unlinked.
    public volatile int image;
    // Written by egress as it sends a welcome, so nothing goes out on a new link before it; read by the control thread.
    public volatile Publication reply;
    // The listings it may trade, each a PublishedMap listing key; replaced whole at each hello.
    public volatile LongHashSet leases = new LongHashSet();

    // Ingress's: when it last heard the session, and the newest gateway heartbeat the session said it had.
    public volatile long lastHeardNanos;
    public volatile long echoedHeartbeatSeq;

    // Egress's: the newest report sequence sent, and unnumbered messages waiting to go before any report.
    public long sentSeq;
    public final PendingMessages unnumbered = new PendingMessages(16, ReplayRing.SLOT_LENGTH);
    // Egress's: the message to send once everything else has gone, and whether it has; the control thread waits on it.
    public final PendingMessages last = new PendingMessages(1, ReplayRing.SLOT_LENGTH);
    public volatile boolean lastSent;

    public GatewaySession(
            final long slot,
            final String sessionId,
            final int strategyId,
            final AccountLink account,
            final int replayCapacity) {
        this.slot = slot;
        this.sessionId = sessionId;
        this.strategyId = strategyId;
        this.account = account;
        this.replay = new ReplayRing(replayCapacity);
        this.totals = new OrderTotals(replayCapacity);
    }

    public boolean leases(final long listingKey) {
        return this.leases.contains(listingKey);
    }
}
