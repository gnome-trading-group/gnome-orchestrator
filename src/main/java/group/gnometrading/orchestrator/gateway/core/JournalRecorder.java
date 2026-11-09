package group.gnometrading.orchestrator.gateway.core;

import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.nio.ByteOrder;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Where one gateway thread records what it handles for the journal. A record is the session's slot (8 bytes,
 * little-endian; 0 for a message from an unlinked image) followed by the message as sent, under the message's template
 * id: core orders and reports carry no session, so the slot is what tells sessions apart in the journal. One thread per
 * recorder; allocates nothing; never waits (the journal reads the ring without holding it back).
 */
public interface JournalRecorder {

    JournalRecorder NONE = (slot, message, offset, length) -> {};

    /** Size of the slot before each message. */
    int SLOT_LENGTH = Long.BYTES;

    void record(long slot, DirectBuffer message, int offset, int length);

    /** Records into {@code ring}, which the journal reads through a lapping poller. */
    static JournalRecorder into(final SequencedRingBuffer<?> ring) {
        final UnsafeBuffer scratch = new UnsafeBuffer(new byte[GatewayMessage.MAX_LENGTH]);
        return (slot, message, offset, length) -> {
            if (length + SLOT_LENGTH > scratch.capacity()) {
                return;
            }
            scratch.putLong(0, slot, ByteOrder.LITTLE_ENDIAN);
            scratch.putBytes(SLOT_LENGTH, message, offset, length);
            ring.tryPublishRaw(scratch, 0, SbeMessage.templateId(message, offset), length + SLOT_LENGTH);
        };
    }
}
