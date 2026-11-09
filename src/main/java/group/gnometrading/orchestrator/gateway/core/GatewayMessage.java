package group.gnometrading.orchestrator.gateway.core;

import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * A message passed between the gateway's threads on a ring: from ingress to the control thread (a session's hello,
 * goodbye or query, with the image it came on), or from the control thread to egress (a message for a session, with
 * whether it is numbered with the session's reports). Pooled in its ring.
 */
public final class GatewayMessage {

    public static final int MAX_LENGTH = 1024;

    /** A session's message for the control thread. */
    public static final int FROM_SESSION = 1;

    /** An image sent something the gateway can't read: its session is evicted. */
    public static final int UNREADABLE = 2;

    /** For a session, numbered with its reports and kept for replay (an order status). */
    public static final int NUMBERED = 3;

    /** For a session, ahead of anything numbered (a welcome, a re-hello request, a goodbye). */
    public static final int UNNUMBERED = 4;

    /**
     * A welcome whose session can't be sent its reports as they are (they come from before a restart, or are past its
     * ring): what is held is skipped, and an order status for each of its orders follows the welcome instead.
     */
    public static final int RESYNC = 5;

    public int kind;
    public int image;
    public long slot;
    // For an UNNUMBERED welcome: replay what follows this report sequence; -1 for any other message.
    public long resumeAfter = -1;
    // A welcome's publication for its session, which egress switches to as it sends the welcome; null otherwise.
    public Publication reply;
    public final UnsafeBuffer buffer = new UnsafeBuffer(new byte[MAX_LENGTH]);
    public int length;
}
