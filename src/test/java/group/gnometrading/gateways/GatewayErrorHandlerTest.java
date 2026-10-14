package group.gnometrading.gateways;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
        assertTrue(causes.get(0).getMessage().startsWith("10 errors in a minute from test gateway"), "says why");
    }
}
