package group.gnometrading.gateways.inbound;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.gnometrading.gateways.GatewayConnectFailedException;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InboundGatewayErrorHandlerTest {

    private final Logger logger = mock(Logger.class);
    private final List<Integer> exits = new ArrayList<>();
    private int reconnects;
    private final InboundGatewayErrorHandler handler =
            new InboundGatewayErrorHandler(logger, () -> reconnects++, exits::add);

    @Test
    void ordinaryError_Reconnects() {
        handler.onError(new IllegalStateException("bad frame"));

        assertEquals(1, reconnects);
        assertTrue(exits.isEmpty());
    }

    @Test
    void gatewayGaveUpConnecting_ExitsAtOnce() {
        handler.onError(new GatewayConnectFailedException("gave up", new IOException("connection refused")));

        assertEquals(List.of(1), exits);
        assertEquals(0, reconnects, "a reconnect is what just failed");
        verify(logger).log(LogMessage.FATAL_ERROR_EXITING);
    }

    @Test
    void tenErrorsInAMinute_Exits() {
        for (int i = 0; i < 10; i++) {
            handler.onError(new IllegalStateException("bad frame"));
        }

        assertEquals(List.of(1), exits);
        assertEquals(9, reconnects);
    }
}
