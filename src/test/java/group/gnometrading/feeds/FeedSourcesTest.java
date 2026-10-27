package group.gnometrading.feeds;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.feed.Feeds;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.md.Source;
import group.gnometrading.md.SourceKind;
import group.gnometrading.resources.Properties;
import org.junit.jupiter.api.Test;

class FeedSourcesTest {

    private final Feeds feeds = new Feeds(new NullLogger(), null);
    private final FeedSources sources;

    FeedSourcesTest() throws Exception {
        sources = new FeedSources(
                feeds,
                new FeedVenues.Context(
                        new NullLogger(),
                        new Properties("orchestrator.dev.properties"),
                        null,
                        MarketDataCredentials.SESSION,
                        () -> 0),
                false,
                (status, cause) -> {},
                source -> WebSocketFeedOpener.RawCaptures.NONE,
                WebSocketFeedOpener.Clients.TLS);
    }

    private static Source source(final String code, final int maxPerConnection) {
        return new Source(
                1,
                code,
                SourceKind.VENUE,
                1,
                "us-east-1",
                code,
                new String[] {"book"},
                true,
                maxPerConnection,
                "IP",
                0,
                0);
    }

    /** A registry without migration 051 reads every capacity as 0, which would mean a connection per market. */
    @Test
    void aSourceWithNoCapacityIsRefused() {
        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> sources.ensure(source("HYPERLIQUID", 0)));
        assertTrue(refused.getMessage().contains("051"));
        assertFalse(feeds.hasSource("HYPERLIQUID"));
    }

    @Test
    void aSourceWithItsCapacityIsAddedOnce() {
        sources.ensure(source("HYPERLIQUID", 50));
        sources.ensure(source("HYPERLIQUID", 50));
        assertTrue(feeds.hasSource("HYPERLIQUID"));
    }
}
