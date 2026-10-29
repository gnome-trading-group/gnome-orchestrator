package group.gnometrading.aeron;

/**
 * Aeron stream ids. A market-data stream's Aeron stream id is its {@code md.stream.stream_id} (an int32, registry
 * migration 049); ids from 2^30 up belong to streams that aren't market data.
 */
public final class AeronStreamIds {

    public static final int FIRST_RESERVED = 1 << 30;

    /** A shard's status stream, on its control endpoint next to the streams it publishes. */
    public static final int SHARD_STATUS = FIRST_RESERVED;

    /** Sessions to an order gateway. */
    public static final int GATEWAY_INGRESS = FIRST_RESERVED + 1;

    /** An order gateway's replies to one session. */
    public static final int GATEWAY_REPLY = FIRST_RESERVED + 2;

    private AeronStreamIds() {}

    public static boolean isMarketData(final int streamId) {
        return streamId >= 0 && streamId < FIRST_RESERVED;
    }
}
