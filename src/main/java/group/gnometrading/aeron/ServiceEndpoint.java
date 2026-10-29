package group.gnometrading.aeron;

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
        long writtenAtNanos) {

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
