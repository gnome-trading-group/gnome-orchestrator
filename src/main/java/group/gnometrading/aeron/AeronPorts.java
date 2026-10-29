package group.gnometrading.aeron;

/**
 * How the platform security group's Aeron range (UDP 40000-40999, gnome-shared-cdk {@code AERON_PORTS}) is split.
 * Mirrored by {@code AERON_PORT_MAP} in gnome-shared-cdk; change both together.
 */
public final class AeronPorts {

    /** A shard's MDC control endpoint: every stream it publishes, and its status stream. One shard per host. */
    public static final int SHARD_CONTROL = 40000;

    /** Order gateways' ingress, one port per gateway. */
    public static final int GATEWAY_INGRESS_FIRST = 40010;

    public static final int GATEWAY_INGRESS_LAST = 40019;

    /**
     * Where a receiving host's driver picks ports for {@code endpoint=<ip>:0} subscriptions: one per shard or gateway
     * it reads. A fixed port can't be shared by subscriptions to different publishers, so the driver hands them out.
     */
    public static final int RECEIVE_FIRST = 40100;

    public static final int RECEIVE_LAST = 40899;

    private AeronPorts() {}

    /** The receive range in the form the media driver takes ({@code "low high"}). */
    public static String receiveRange() {
        return RECEIVE_FIRST + " " + RECEIVE_LAST;
    }
}
