package group.gnometrading.aeron;

/**
 * How a host's media driver runs, per kind of host.
 *
 * <ul>
 *   <li>{@link #STANDARD}: shards, paper and standard sessions, on burstable or one-core hosts: one shared driver
 *       thread that backs off when idle.
 *   <li>{@link #GATEWAY}: order gateways, whose venue round trips are 20-100 ms: the same as standard.
 *   <li>{@link #LATENCY}: latency-tier shards and low-latency sessions: the network (sender and receiver together, as a
 *       session mostly receives) on one spinning thread, the conductor backing off.
 * </ul>
 */
public enum AeronProfile {
    STANDARD(false),
    GATEWAY(false),
    LATENCY(true);

    private final boolean dedicated;

    AeronProfile(final boolean dedicated) {
        this.dedicated = dedicated;
    }

    public boolean dedicated() {
        return this.dedicated;
    }
}
