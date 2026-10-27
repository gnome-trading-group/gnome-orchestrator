package group.gnometrading.feeds;

/** Which streams a process reads; read again and again, so streams can be added and removed while it runs. */
@FunctionalInterface
public interface Assignment {

    /** The streams to read, and a generation that grows with every change. */
    record Streams(long generation, int[] streamIds) {}

    /** The current streams; throws if they can't be read, and the reader keeps what it has. */
    Streams read() throws Exception;

    /** Streams that never change, a trading session's. */
    static Assignment fixed(final int... streamIds) {
        final Streams streams = new Streams(1, streamIds.clone());
        return () -> streams;
    }
}
