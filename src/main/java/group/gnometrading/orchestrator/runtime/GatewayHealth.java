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

    /**
     * Records that found the ring full and waited for room, each counted once however long it waited; -1 where it
     * doesn't count them.
     */
    default long ringHeldRecords() {
        return -1;
    }

    /** How long, in nanoseconds, its records waited for room in the ring; -1 where it doesn't count it. */
    default long ringHeldNanos() {
        return -1;
    }

    /**
     * Times it fell behind its shard's publication and was subscribed again at the live position, skipping what it
     * hadn't read; -1 where it doesn't count them.
     */
    default long stallResubscribes() {
        return -1;
    }
}
