package group.gnometrading.feeds;

import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.ProcessExit;
import group.gnometrading.gateways.feed.Feeds;
import group.gnometrading.gateways.feed.RateBudget;
import group.gnometrading.md.Source;
import group.gnometrading.networking.http.HTTPClient;
import java.util.function.Function;

/**
 * Adds a process's sources to its {@link Feeds} as their streams arrive: each with its venue's connections, sized and
 * paced by the source's capacity in the registry, and its REST probe.
 */
public final class FeedSources {

    private final Feeds feeds;
    private final FeedVenues.Context context;
    private final boolean spinReads;
    private final ProcessExit exit;
    private final Function<String, WebSocketFeedOpener.RawCaptures> rawBySource;
    private final WebSocketFeedOpener.Clients clients;

    /**
     * @param spinReads whether readers spin rather than block, for readers on their own isolated cores
     * @param exit what a connection that can't connect or keeps failing does
     * @param rawBySource where each source's connections keep their raw frames
     * @param clients makes each connection's websocket client
     */
    public FeedSources(
            final Feeds feeds,
            final FeedVenues.Context context,
            final boolean spinReads,
            final ProcessExit exit,
            final Function<String, WebSocketFeedOpener.RawCaptures> rawBySource,
            final WebSocketFeedOpener.Clients clients) {
        this.feeds = feeds;
        this.context = context;
        this.spinReads = spinReads;
        this.exit = exit;
        this.rawBySource = rawBySource;
        this.clients = clients;
    }

    /** Adds the source unless it is already there. */
    public void ensure(final Source source) {
        if (feeds.hasSource(source.code())) {
            return;
        }
        // Read as 0 from a registry without migration 051, which would open a connection for every market.
        if (source.maxPerConnection() <= 0) {
            throw new IllegalStateException("Source " + source.code()
                    + " has no max_per_connection in the registry; apply migration 051_md_source_capacity");
        }
        final FeedVenues.Venue venue = FeedVenues.forAdapter(source.adapter());
        feeds.addSource(
                new Feeds.Source(
                        source.code(),
                        venue.feed().subjects(),
                        venue.feed().levelsPerSide(),
                        venue.probes() == null ? null : venue.probes().create(new HTTPClient(), context.properties()),
                        // Its own budget: probes use the venue's REST limits, not its socket's.
                        RateBudget.named(source.code() + ":probe", venue.feed().probesPerMinute())),
                source.maxPerConnection(),
                new WebSocketFeedOpener(
                        venue,
                        context,
                        ConnectLimiter.forSource(source.code()),
                        RateBudget.named(source.code(), source.clientMessagesPerMinute()),
                        spinReads,
                        exit,
                        rawBySource.apply(source.code()),
                        clients));
    }
}
