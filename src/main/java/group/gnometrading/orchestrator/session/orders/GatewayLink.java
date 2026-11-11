package group.gnometrading.orchestrator.session.orders;

import group.gnometrading.orchestrator.aeron.PendingMessages;
import group.gnometrading.orchestrator.session.lifecycle.OrderGatewayLinkStatus;
import group.gnometrading.schemas.gateway.HelloRefusal;
import io.aeron.Publication;
import io.aeron.Subscription;
import java.util.List;

/**
 * The session's link to one venue's order gateway. The control thread ({@link GatewayLinks}) opens its reply
 * subscription and ingress publication, and opens them again whenever the link goes down; the gateway client's thread
 * does everything else: says hello once the publication connects, waits for the welcome, trades, and takes the link down
 * when the gateway goes quiet, refuses it, or asks for a new hello.
 */
public final class GatewayLink implements OrderGatewayLinkStatus {

    /** No publication, or one the client gave up on: the control thread opens it again. */
    static final int DOWN = 0;

    /** Freshly opened: the client says hello once the publication connects. */
    static final int CONNECTING = 1;

    /** Hello sent, waiting for the welcome. */
    static final int HELLO_SENT = 2;

    /** Welcomed: orders go out. */
    static final int READY = 3;

    /** The session said goodbye on it while closing: it is never opened again. */
    static final int GONE = 4;

    final String gatewayKey;
    final int exchangeId;
    final int venueAccountId;
    // The session's listings on this venue, as (exchange id, security id) pairs.
    final List<long[]> listings;

    volatile int state = DOWN;
    volatile Publication publication;
    volatile Subscription replies;
    volatile String replyIp;
    volatile int replyPort;
    // Why the last hello was refused, for the control thread to report; NONE otherwise.
    volatile HelloRefusal refusal = HelloRefusal.NONE;
    volatile long gatewayHeardNanos;
    // When the control thread opened the publication, so the client can give up on an address nothing answers on.
    volatile long connectingSinceNanos;
    // Set by the client when it gave up on that address, for the control thread to report.
    volatile boolean connectTimedOut;
    // The client's open orders on this venue, published when it changes, for the heartbeat.
    volatile int workingOrders;

    // The client's: where its hello stands, the gateway's numbering, and cancels held until the link is ready.
    int helloPage;
    long welcomeDeadlineNanos;
    long epoch;
    long lastReportSeq;
    long gatewayHeartbeatSeq;
    long nextHeartbeatNanos;
    final PendingMessages pending;

    GatewayLink(
            final String gatewayKey,
            final int exchangeId,
            final int venueAccountId,
            final List<long[]> listings,
            final int pendingCapacity) {
        this.gatewayKey = gatewayKey;
        this.exchangeId = exchangeId;
        this.venueAccountId = venueAccountId;
        this.listings = List.copyOf(listings);
        this.pending = new PendingMessages(pendingCapacity, 128);
    }

    @Override
    public String gatewayKey() {
        return this.gatewayKey;
    }

    @Override
    public boolean linked() {
        return this.state == READY;
    }

    @Override
    public int workingOrders() {
        return this.workingOrders;
    }
}
