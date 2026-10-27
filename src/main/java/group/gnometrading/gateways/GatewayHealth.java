package group.gnometrading.gateways;

/** A gateway as a session's heartbeat reports it; read from the heartbeat's thread. */
public interface GatewayHealth {

    String name();

    /** Whether it isn't delivering right now: reconnecting, or a market without a trusted book. */
    boolean isReconnecting();

    /** How many times it has found its book wrong and resynced, since it started. */
    long bookResyncs();
}
