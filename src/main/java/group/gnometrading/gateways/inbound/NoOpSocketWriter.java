package group.gnometrading.gateways.inbound;

import java.nio.ByteBuffer;

public final class NoOpSocketWriter extends InboundSocketWriter {

    @Override
    protected void write(ByteBuffer buffer) {}
}
