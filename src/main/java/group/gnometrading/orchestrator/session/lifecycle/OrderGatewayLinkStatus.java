package group.gnometrading.orchestrator.session.lifecycle;

/**
 * A live session's link to one venue's order gateway, as the heartbeat reports it: whether orders can go out, and how
 * many of the session's orders are working on that venue meanwhile. Read from the heartbeat's thread.
 */
public interface OrderGatewayLinkStatus {

    String gatewayKey();

    /** Whether the gateway has welcomed the session and is heard from. */
    boolean linked();

    /** The session's orders on this venue not yet known to have ended. */
    int workingOrders();
}
