package group.gnometrading.feeds;

import group.gnometrading.gateways.credentials.KalshiCredentials;
import group.gnometrading.gateways.credentials.PolymarketUsCredentials;
import group.gnometrading.gateways.feed.SubjectTable;
import group.gnometrading.gateways.feed.VenueFeed;
import group.gnometrading.gateways.feed.VenueProtocol;
import group.gnometrading.gateways.feed.probe.BookProbeClient;
import group.gnometrading.gateways.feed.transport.WebSocketChannel;
import group.gnometrading.gateways.venues.hyperliquid.HyperliquidProtocol;
import group.gnometrading.gateways.venues.hyperliquid.HyperliquidVenue;
import group.gnometrading.gateways.venues.kalshi.KalshiBookProbe;
import group.gnometrading.gateways.venues.kalshi.KalshiProtocol;
import group.gnometrading.gateways.venues.kalshi.KalshiVenue;
import group.gnometrading.gateways.venues.lighter.LighterBookProbe;
import group.gnometrading.gateways.venues.lighter.LighterProtocol;
import group.gnometrading.gateways.venues.lighter.LighterVenue;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlBookProbe;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlProtocol;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlVenue;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsAuthSigner;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsProtocol;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsVenue;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.resources.Properties;
import group.gnometrading.shared.SecretCache;
import java.net.URI;
import java.util.Map;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochClock;

/**
 * Every market data source a process can read, keyed by {@code md.source.adapter}: the venue's own description of its
 * feed (gnome-gateways {@code venues/<venue>/<Venue>Venue.FEED}), and how this process builds its protocol, with the
 * credentials it loads, and its REST probe. Adding a source is a venue package in gnome-gateways and an entry here; one
 * on another kind of connection, such as FIX, brings its own opener.
 */
public final class FeedVenues {

    /**
     * What a source's connections and probe are built from. {@code credentials} names the secret a signed source's
     * connections sign in with: a shard's bound market-data account, or a session's per-exchange key.
     */
    public record Context(
            Logger logger,
            Properties properties,
            SecretCache secrets,
            MarketDataCredentials credentials,
            EpochNanoClock clock) {}

    /** The protocol for one new connection, speaking on its channel and finding markets in its table. */
    @FunctionalInterface
    public interface Protocols {
        VenueProtocol<WebSocketChannel> create(Context context, SubjectTable table, WebSocketChannel channel)
                throws Exception;
    }

    /** The source's REST book reader, on its own client: the prober's thread is its only user. */
    @FunctionalInterface
    public interface Probes {
        BookProbeClient create(HTTPClient http, Properties properties);
    }

    /**
     * @param signed whether its connections sign in with a market-data key, so a shard spends one of the source's
     *     accounts on them; public feeds need none
     * @param probes reads the source's REST book; null for a source that sends whole books, which isn't probed
     */
    public record Venue(VenueFeed feed, boolean signed, Protocols protocols, Probes probes) {}

    private static final Map<String, Venue> VENUES = Map.of(
            "HYPERLIQUID",
            new Venue(
                    HyperliquidVenue.FEED,
                    false,
                    (context, table, channel) -> new HyperliquidProtocol(table, SystemEpochClock.INSTANCE),
                    null),
            "LIGHTER",
            new Venue(
                    LighterVenue.FEED,
                    false,
                    (context, table, channel) -> new LighterProtocol(table, channel),
                    (http, properties) -> new LighterBookProbe(http, hostOf(properties, "lighter.ws.url"))),
            "POLYMARKET_INTL",
            new Venue(
                    PolymarketIntlVenue.FEED,
                    false,
                    (context, table, channel) -> new PolymarketIntlProtocol(table),
                    (http, properties) ->
                            new PolymarketIntlBookProbe(http, hostOf(properties, "polymarket.intl.clob.url"))),
            "POLYMARKET_US",
            new Venue(
                    PolymarketUsVenue.FEED,
                    true,
                    (context, table, channel) -> {
                        final PolymarketUsCredentials credentials = PolymarketUsCredentials.fromJson(
                                context.secrets().get(context.credentials().secretFor("POLYMARKET_US")));
                        return new PolymarketUsProtocol(
                                context.logger(),
                                table,
                                new PolymarketUsAuthSigner(credentials.apiKey(), credentials.privateKey()),
                                context.clock());
                    },
                    null),
            "KALSHI",
            new Venue(
                    KalshiVenue.FEED,
                    true,
                    (context, table, channel) -> {
                        final KalshiCredentials credentials = KalshiCredentials.fromJson(
                                context.secrets().get(context.credentials().secretFor("KALSHI")));
                        return new KalshiProtocol(
                                context.logger(),
                                table,
                                credentials.apiKey(),
                                credentials.privateKey(),
                                context.clock());
                    },
                    (http, properties) -> new KalshiBookProbe(http, hostOf(properties, "kalshi.api.url"))));

    private FeedVenues() {}

    public static Venue forAdapter(final String adapter) {
        final Venue venue = VENUES.get(adapter);
        if (venue == null) {
            throw new IllegalArgumentException("No feed for source adapter " + adapter);
        }
        return venue;
    }

    private static String hostOf(final Properties properties, final String urlProperty) {
        return URI.create(properties.getStringProperty(urlProperty)).getHost();
    }
}
