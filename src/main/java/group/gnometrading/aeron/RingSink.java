package group.gnometrading.aeron;

import group.gnometrading.sequencer.SequencedRingBuffer;
import java.nio.ByteOrder;
import org.agrona.DirectBuffer;

/**
 * An {@link AeronIngressAgent.Sink} into a ring on the session's sequence: each message is a whole SBE message (its
 * 8-byte header first), as a ring slot holds it, so it is copied in as it came, its template id read from the header.
 * The ring must have this sink as its only producer.
 */
public final class RingSink implements AeronIngressAgent.Sink {

    /** The SBE message header's templateId: a little-endian uint16 after blockLength. */
    private static final int TEMPLATE_ID_OFFSET = 2;

    private final SequencedRingBuffer<?> ring;

    public RingSink(final SequencedRingBuffer<?> ring) {
        this.ring = ring;
    }

    @Override
    public boolean accept(final DirectBuffer buffer, final int offset, final int length) {
        final int templateId = buffer.getShort(offset + TEMPLATE_ID_OFFSET, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
        return this.ring.tryPublishRaw(buffer, offset, templateId, length);
    }
}
