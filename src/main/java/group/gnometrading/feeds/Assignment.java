package group.gnometrading.feeds;

import java.util.Map;

/** Which streams a process reads; read again and again, so streams can be added and removed while it runs. */
@FunctionalInterface
public interface Assignment {

    /**
     * The streams to read, a generation that grows with every change, the market-data account each source is bound to
     * by label (a source not named uses {@link MarketDataCredentials#DEFAULT_LABEL}), and the minutes each stream may
     * write: epoch nanos from which ({@code writeFrom}) and before which ({@code writeUntil}) its minute files are
     * uploaded, for a stream handed between shards at a minute cut. A stream not named in either writes every minute.
     * {@code publish} names the streams sessions lease, which a shard also sends over Aeron.
     */
    record Streams(
            long generation,
            int[] streamIds,
            Map<String, String> accounts,
            Map<Integer, Long> writeFrom,
            Map<Integer, Long> writeUntil,
            int[] publish) {
        public Streams(final long generation, final int[] streamIds) {
            this(generation, streamIds, Map.of());
        }

        public Streams(final long generation, final int[] streamIds, final Map<String, String> accounts) {
            this(generation, streamIds, accounts, Map.of(), Map.of());
        }

        public Streams(
                final long generation,
                final int[] streamIds,
                final Map<String, String> accounts,
                final Map<Integer, Long> writeFrom,
                final Map<Integer, Long> writeUntil) {
            this(generation, streamIds, accounts, writeFrom, writeUntil, new int[0]);
        }

        /** Epoch nanos from which {@code streamId}'s minutes are written; {@link Long#MIN_VALUE} when unbounded. */
        public long writeFromNanos(final int streamId) {
            return writeFrom.getOrDefault(streamId, Long.MIN_VALUE);
        }

        /** Epoch nanos before which {@code streamId}'s minutes are written; {@link Long#MAX_VALUE} when unbounded. */
        public long writeUntilNanos(final int streamId) {
            return writeUntil.getOrDefault(streamId, Long.MAX_VALUE);
        }
    }

    /** The current streams; throws if they can't be read, and the reader keeps what it has. */
    Streams read() throws Exception;

    /** Streams that never change, a trading session's. */
    static Assignment fixed(final int... streamIds) {
        final Streams streams = new Streams(1, streamIds.clone());
        return () -> streams;
    }
}
