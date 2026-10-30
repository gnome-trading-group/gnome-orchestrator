package group.gnometrading.collectors;

import io.aeron.Publication;
import org.agrona.DirectBuffer;

/** Where a shard sends one stream's records, or its status, to the sessions reading them: an Aeron publication. */
interface StreamPublication extends AutoCloseable {

    /** As {@link Publication#offer(DirectBuffer, int, int)}: the new position, or a negative reason it wasn't sent. */
    long offer(DirectBuffer buffer, int offset, int length);

    /** As {@link Publication#position()}: how far it has sent, which sessions compare their own position with. */
    long position();

    /** As {@link Publication#sessionId()}: which publication a position belongs to. */
    int sessionId();

    @Override
    void close();

    static StreamPublication of(final Publication publication) {
        return new StreamPublication() {
            @Override
            public long offer(final DirectBuffer buffer, final int offset, final int length) {
                return publication.offer(buffer, offset, length);
            }

            @Override
            public long position() {
                return publication.position();
            }

            @Override
            public int sessionId() {
                return publication.sessionId();
            }

            @Override
            public void close() {
                publication.close();
            }
        };
    }

    /**
     * Whether a failed offer found the publication full, so the record must wait, rather than finding nobody to send it
     * to. {@link Publication#ADMIN_ACTION} isn't one: the term rotated, and the same offer goes through at once.
     */
    static boolean full(final long result) {
        return result == Publication.BACK_PRESSURED || result == Publication.MAX_POSITION_EXCEEDED;
    }
}
