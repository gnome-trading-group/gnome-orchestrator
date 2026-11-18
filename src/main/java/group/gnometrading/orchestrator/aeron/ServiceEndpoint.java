package group.gnometrading.orchestrator.aeron;

/**
 * Where a process sessions connect to over Aeron can be reached, as its item in {@code service-endpoints}.
 *
 * @param kind what the process is
 * @param id the process's own name: a shard id, or an order gateway's venue
 * @param instance the task or instance it runs as, so a replacement is told apart from its predecessor
 * @param region the region it runs in
 * @param privateIp its private IP on the platform network
 * @param port its Aeron control (shard) or ingress (gateway) port
 * @param state what it says about itself, e.g. {@code ACTIVE}, {@code READY}, {@code STOPPING}
 * @param version the orchestrator version it runs
 * @param writtenAtNanos when it last wrote the item, epoch nanoseconds
 * @param linkedSessions an order gateway's sessions linked or saying hello; 0 for a shard
 * @param workingOrders an order gateway's orders working; 0 for a shard
 * @param canHandOver whether an order gateway's release stops without cancelling when its reconciler rolls it
 * @param handover an order gateway stopping for a roll, its orders left resting for the next process on its host
 * @param startedAtMs when an order gateway's process started, epoch milliseconds, so a restart is told apart from a
 *     process still running; 0 for a shard
 * @param paper a paper gateway's load against its caps; null for any other
 * @param zone the availability zone it runs in; null off EC2 or from a release that didn't say
 */
public record ServiceEndpoint(
        Kind kind,
        String id,
        String instance,
        String region,
        String privateIp,
        int port,
        String state,
        String version,
        long writtenAtNanos,
        int linkedSessions,
        long workingOrders,
        boolean canHandOver,
        boolean handover,
        long startedAtMs,
        PaperLoad paper,
        HostZone zone) {

    /** What a paper gateway carries against its caps: books (session x listing), distinct streams, records/s. */
    public record PaperLoad(int books, int streams, long recordsPerSecond, int maxBooks, int maxStreams) {}

    public ServiceEndpoint(
            final Kind kind,
            final String id,
            final String instance,
            final String region,
            final String privateIp,
            final int port,
            final String state,
            final String version,
            final long writtenAtNanos,
            final int linkedSessions,
            final long workingOrders,
            final boolean canHandOver,
            final boolean handover) {
        this(
                kind,
                id,
                instance,
                region,
                privateIp,
                port,
                state,
                version,
                writtenAtNanos,
                linkedSessions,
                workingOrders,
                canHandOver,
                handover,
                0,
                null,
                null);
    }

    public ServiceEndpoint(
            final Kind kind,
            final String id,
            final String instance,
            final String region,
            final String privateIp,
            final int port,
            final String state,
            final String version,
            final long writtenAtNanos,
            final int linkedSessions,
            final long workingOrders) {
        this(
                kind,
                id,
                instance,
                region,
                privateIp,
                port,
                state,
                version,
                writtenAtNanos,
                linkedSessions,
                workingOrders,
                false,
                false);
    }

    public enum Kind {
        SHARD("shard"),
        ORDER_GATEWAY("order-gateway");

        private final String identifier;

        Kind(final String identifier) {
            this.identifier = identifier;
        }

        public String identifier() {
            return this.identifier;
        }

        public static Kind forIdentifier(final String identifier) {
            for (final Kind kind : values()) {
                if (kind.identifier.equals(identifier)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown service kind: " + identifier);
        }
    }

    /** The item's key, {@code <kind>#<id>}. */
    public String serviceId() {
        return serviceId(this.kind, this.id);
    }

    public static String serviceId(final Kind kind, final String id) {
        return kind.identifier() + "#" + id;
    }
}
