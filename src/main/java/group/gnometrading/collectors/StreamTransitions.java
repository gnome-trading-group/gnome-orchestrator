package group.gnometrading.collectors;

import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectState;

/**
 * The shard's latest stream state changes, oldest first, at most {@link #CAPACITY}: the reconciler opens and closes
 * coverage at these times rather than at its own once-a-minute view, and since every status repeats the latest ones, a
 * status it never read loses nothing. Kept in fixed arrays, as the runtime records them on every tick.
 *
 * <p>States are coded as bytes: {@link #NONE} (not running), {@link #FAILED} (its collector threw), then a
 * {@link SubjectState} each.
 */
final class StreamTransitions {

    static final int CAPACITY = 256;
    static final byte NONE = 0;
    static final byte FAILED = 1;
    private static final SubjectState[] SUBJECT_STATES = SubjectState.values();
    private static final int SUBJECT_OFFSET = 2;

    private final int[] streamIds = new int[CAPACITY];
    private final byte[] from = new byte[CAPACITY];
    private final byte[] to = new byte[CAPACITY];
    private final long[] atNanos = new long[CAPACITY];
    private long recorded;

    /** A running stream's state now: a market not yet placed is subscribing. */
    static byte stateOf(final ShardStream stream) {
        if (stream.failure() != null) {
            return FAILED;
        }
        final Subject subject = stream.subject();
        return code(subject == null ? SubjectState.SUBSCRIBING : subject.state());
    }

    static byte code(final SubjectState state) {
        return (byte) (SUBJECT_OFFSET + state.ordinal());
    }

    static String name(final byte state) {
        return switch (state) {
            case NONE -> "NONE";
            case FAILED -> CollectorHeartbeat.FAILED;
            default -> SUBJECT_STATES[state - SUBJECT_OFFSET].name();
        };
    }

    synchronized void record(final int streamId, final byte fromState, final byte toState, final long wallNanos) {
        final int slot = (int) (recorded % CAPACITY);
        streamIds[slot] = streamId;
        from[slot] = fromState;
        to[slot] = toState;
        atNanos[slot] = wallNanos;
        recorded++;
    }

    /** How many have been recorded since the shard started, kept or not. */
    synchronized long recorded() {
        return recorded;
    }

    /** Copies the kept changes, oldest first, into arrays of {@link #CAPACITY}; returns how many. */
    synchronized int copyTo(final int[] ids, final byte[] fromStates, final byte[] toStates, final long[] at) {
        final int kept = (int) Math.min(recorded, CAPACITY);
        final long first = recorded - kept;
        for (int i = 0; i < kept; i++) {
            final int slot = (int) ((first + i) % CAPACITY);
            ids[i] = streamIds[slot];
            fromStates[i] = from[slot];
            toStates[i] = to[slot];
            at[i] = atNanos[slot];
        }
        return kept;
    }
}
