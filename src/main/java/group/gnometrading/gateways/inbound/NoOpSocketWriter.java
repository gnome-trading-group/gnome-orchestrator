package group.gnometrading.gateways.inbound;

import java.nio.ByteBuffer;

public final class NoOpSocketWriter extends SocketWriter {

    @Override
    protected void write(ByteBuffer buffer) {}
}
