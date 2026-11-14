package group.gnometrading.orchestrator.gateway.core;

import group.gnometrading.schemas.gateway.MessageHeaderEncoder;
import group.gnometrading.schemas.gateway.OrderTimingEncoder;
import org.agrona.concurrent.UnsafeBuffer;

/** One request's {@code OrderTiming}, on its way from a lane to egress for its session. Pooled in its ring. */
public final class TimingMessage {

    public static final int MAX_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH + OrderTimingEncoder.BLOCK_LENGTH;

    public long slot;
    public final UnsafeBuffer buffer = new UnsafeBuffer(new byte[MAX_LENGTH]);
    public int length;
}
