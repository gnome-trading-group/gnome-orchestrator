package group.gnometrading.feeds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.feed.SubjectRef;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.resources.Properties;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeedVenuesTest {

    private static final List<String> ADAPTERS =
            List.of("HYPERLIQUID", "LIGHTER", "KALSHI", "POLYMARKET_INTL", "POLYMARKET_US");

    private static Properties stage(final String stage) throws Exception {
        return new Properties("orchestrator." + stage + ".properties");
    }

    /** Each source's websocket, and its REST probe if it has one, read URLs every stage must have. */
    @Test
    void everySourceConnectsAndProbesWithEachStagesUrls() throws Exception {
        for (String stage : List.of("dev", "prod")) {
            Properties properties = stage(stage);
            for (String adapter : ADAPTERS) {
                FeedVenues.Venue venue = FeedVenues.forAdapter(adapter);
                assertTrue(
                        properties.getStringProperty(venue.feed().urlProperty()).startsWith("wss://"),
                        adapter + " " + stage);
                if (venue.probes() != null) {
                    assertNotNull(venue.probes().create(new HTTPClient(), properties), adapter + " " + stage);
                }
            }
        }
    }

    @Test
    void aBinaryMarketsTwoListingsReadOneSubject() {
        assertEquals(
                new SubjectRef("KXBTC", Projection.COMPLEMENT),
                FeedVenues.forAdapter("KALSHI").feed().subjects().apply("KXBTC:no"));
        assertEquals(
                new SubjectRef("token", Projection.IDENTITY),
                FeedVenues.forAdapter("POLYMARKET_INTL").feed().subjects().apply("condition:token"));
    }

    @Test
    void anUnknownAdapterFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> FeedVenues.forAdapter("UNKNOWN_VENUE"));
    }
}
