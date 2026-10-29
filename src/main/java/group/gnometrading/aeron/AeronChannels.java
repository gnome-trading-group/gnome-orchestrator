package group.gnometrading.aeron;

/**
 * The channel URIs shards, sessions and gateways use. Built once per publication or subscription (they allocate), so
 * never on a hot path.
 */
public final class AeronChannels {

    /** Fits the inter-region peering path; Aeron's default of 1408 already does, stated so it can't drift. */
    public static final int MTU = 1408;

    /** A market-data stream's term: room for a few seconds of a busy book, so a brief pause loses nothing. */
    public static final int MARKET_DATA_TERM_LENGTH = 256 * 1024;

    /** The order gateway's links: an order is ~100 bytes, so a term still holds hundreds. */
    public static final int GATEWAY_TERM_LENGTH = 64 * 1024;

    private AeronChannels() {}

    /**
     * A stream a shard publishes to whoever joins its control endpoint. {@code fc=max} follows the fastest receiver,
     * so a session that stops reading never slows the shard or the other sessions.
     */
    public static String shardPublication(final String shardIp) {
        return shardPublication(shardIp, AeronPorts.SHARD_CONTROL);
    }

    public static String shardPublication(final String shardIp, final int controlPort) {
        return "aeron:udp?control=" + shardIp + ":" + controlPort + "|control-mode=dynamic|fc=max|term-length="
                + MARKET_DATA_TERM_LENGTH + "|mtu=" + MTU;
    }

    /**
     * The first subscription a host makes to a shard: it opens a receive port from the driver's range and names it by
     * {@code tag}. The shard's other streams join that port with {@link #tagged(long)}.
     */
    public static String shardSubscription(final String localIp, final String shardIp, final long tag) {
        return shardSubscription(localIp, shardIp, AeronPorts.SHARD_CONTROL, tag);
    }

    public static String shardSubscription(
            final String localIp, final String shardIp, final int controlPort, final long tag) {
        return "aeron:udp?endpoint=" + localIp + ":0|control=" + shardIp + ":" + controlPort
                + "|control-mode=dynamic|tags=" + tag;
    }

    /** Another stream on the receive port of the subscription created with {@code tag}. */
    public static String tagged(final long tag) {
        return "aeron:udp?tags=" + tag;
    }
}
