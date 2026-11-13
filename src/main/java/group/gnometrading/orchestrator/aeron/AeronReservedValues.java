package group.gnometrading.orchestrator.aeron;

import io.aeron.ReservedValueSupplier;
import org.agrona.DirectBuffer;

/**
 * What a frame's reserved value says about the message in it. Aeron carries the value in the frame header, outside the
 * message, so marking a message never changes its bytes.
 */
public final class AeronReservedValues {

    /** A message sent as usual: Aeron's default when the publisher supplies none. */
    public static final long NONE = 0;

    /**
     * A shard sending a stream's last record again. Real records can repeat byte for byte (a venue message with several
     * identical fills), so only this mark tells a session it already has the record.
     */
    public static final long RESENT = 1;

    public static final ReservedValueSupplier RESENT_SUPPLIER = (termBuffer, termOffset, frameLength) -> RESENT;

    /**
     * When a shard sent the record, epoch nanoseconds, from a frame's reserved value; 0 when it carries none (a re-send,
     * or a shard from before the stamp). Any stamp is far above {@link #RESENT}, so the two marks never meet.
     */
    public static long sentNanos(final long reservedValue) {
        return reservedValue > RESENT ? reservedValue : 0;
    }

    /**
     * Stamps each frame with the time set last: one per publication, set and offered on one thread, so stamping
     * allocates nothing.
     */
    public static final class SentStamp implements ReservedValueSupplier {
        private long sentNanos;

        public void set(final long sentNanos) {
            this.sentNanos = sentNanos;
        }

        @Override
        public long get(final DirectBuffer termBuffer, final int termOffset, final int frameLength) {
            return sentNanos;
        }
    }

    private AeronReservedValues() {}
}
