package group.gnometrading.aeron;

import io.aeron.ReservedValueSupplier;

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

    private AeronReservedValues() {}
}
