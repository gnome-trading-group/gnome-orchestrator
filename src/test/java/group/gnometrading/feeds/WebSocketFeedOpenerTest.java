package group.gnometrading.feeds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.ProcessExit;
import group.gnometrading.gateways.feed.FeedListener;
import group.gnometrading.gateways.feed.RateBudget;
import group.gnometrading.gateways.feed.SubjectTable;
import group.gnometrading.gateways.venues.hyperliquid.HyperliquidVenue;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.resources.Properties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebSocketFeedOpenerTest {

    private final List<Integer> exits = new ArrayList<>();
    private final ProcessExit exit = (status, cause) -> exits.add(status);
    private boolean carries;

    @Test
    void aConnectionNoStreamDependsOnOnlyLogsWhenItGivesUp() {
        ProcessExit guarded = WebSocketFeedOpener.onceCarrying(() -> carries, exit, new NullLogger(), "TEST-1");

        guarded.exit(1, new IllegalStateException("can't connect"));
        assertTrue(exits.isEmpty(), "a roll's replacement failing only fails the roll");

        carries = true;
        guarded.exit(1, new IllegalStateException("can't connect"));
        assertEquals(List.of(1), exits);
    }

    @Test
    void theClientIsClosedWhenItsProtocolCantBeBuilt() throws Exception {
        WebSocketClient client = mock(WebSocketClient.class);
        WebSocketFeedOpener opener = new WebSocketFeedOpener(
                new FeedVenues.Venue(
                        HyperliquidVenue.FEED,
                        false,
                        (context, table, channel) -> {
                            throw new IllegalStateException("no credentials");
                        },
                        null),
                new FeedVenues.Context(
                        new NullLogger(),
                        new Properties("orchestrator.dev.properties"),
                        null,
                        MarketDataCredentials.SESSION,
                        () -> 0),
                ConnectLimiter.UNLIMITED,
                RateBudget.UNLIMITED,
                false,
                exit,
                WebSocketFeedOpener.RawCaptures.NONE,
                (uri, readBufferBytes) -> client);

        assertThrows(
                IllegalStateException.class, () -> opener.open("TEST-0", new SubjectTable(), new FeedListener() {}));
        verify(client).close();
    }
}
