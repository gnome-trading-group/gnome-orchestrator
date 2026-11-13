package group.gnometrading.orchestrator.runtime;

/** A gateway as a session's heartbeat reports it; read from the heartbeat's thread. */
public interface GatewayHealth {

    String name();

    /** Whether it isn't delivering right now: reconnecting, or a market without a trusted book. */
    boolean isReconnecting();

    /** How many times it has found its book wrong and resynced, since it started. */
    long bookResyncs();

    /** Records it has passed on since it started; -1 where it doesn't count them. */
    default long records() {
        return -1;
    }

    /** Their bytes; -1 where it doesn't count them. */
    default long bytes() {
        return -1;
    }

    /** Repeats of its last record it dropped (a shard re-sending a quiet stream); -1 where it doesn't count them. */
    default long resentRepeats() {
        return -1;
    }

    /** Messages it couldn't pass on (too big, or not a record); -1 where it doesn't count them. */
    default long refusedRecords() {
        return -1;
    }
}
