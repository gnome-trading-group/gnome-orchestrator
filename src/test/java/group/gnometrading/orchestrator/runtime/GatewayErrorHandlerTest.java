package group.gnometrading.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import group.gnometrading.gateways.GatewayConnectFailedException;
import group.gnometrading.gateways.SocketClosedException;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayErrorHandlerTest {

    private final Logger logger = mock(Logger.class);
    private final List<Integer> exits = new ArrayList<>();
    private final List<Throwable> causes = new ArrayList<>();
    private int reconnects;
    private final GatewayErrorHandler handler =
            new GatewayErrorHandler(logger, "test gateway", () -> reconnects++, (status, cause) -> {
                exits.add(status);
                causes.add(cause);
            });

    @Test
    void ordinaryError_Reconnects() {
        handler.onError(new IllegalStateException("bad frame"));

        assertEquals(1, reconnects);
        assertTrue(exits.isEmpty());
    }

    @Test
    void droppedSocket_ReconnectsWithoutLoggingAnError() {
        handler.onError(new SocketClosedException(new IOException("The venue closed the websocket")));

        assertEquals(1, reconnects);
        verify(logger).logf(eq(LogMessage.DEBUG), eq("Error occurred in %s: %s"), any(), any());
        verify(logger, never()).logf(eq(LogMessage.UNKNOWN_ERROR), anyString(), any(), any());
    }

    @Test
    void anyOtherError_IsLoggedAsAnError() {
        handler.onError(new IllegalStateException("bad frame"));

        verify(logger).logf(eq(LogMessage.UNKNOWN_ERROR), eq("Error occurred in %s: %s"), any(), any());
    }

    @Test
    void gatewayGaveUpConnecting_ExitsAtOnce() {
        handler.onError(new GatewayConnectFailedException("gave up", new IOException("connection refused")));

        assertEquals(List.of(1), exits);
        assertEquals(0, reconnects, "a reconnect is what just failed");
        verify(logger).logf(LogMessage.UNKNOWN_ERROR, "Giving up on %s", "test gateway");
    }

    @Test
    void tenErrorsInAMinute_Exits() {
        for (int i = 0; i < 10; i++) {
            handler.onError(new IllegalStateException("bad frame"));
        }

        assertEquals(List.of(1), exits);
        assertEquals(9, reconnects);
        assertTrue(causes.get(0).getMessage().startsWith("10 errors in a minute from test gateway"), "says why");
    }
}
