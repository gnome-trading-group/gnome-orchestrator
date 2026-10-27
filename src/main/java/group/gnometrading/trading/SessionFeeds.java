package group.gnometrading.trading;

import group.gnometrading.SecurityMaster;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.feeds.FeedSources;
import group.gnometrading.feeds.FeedVenues;
import group.gnometrading.feeds.WebSocketFeedOpener;
import group.gnometrading.gateways.GatewayHealth;
import group.gnometrading.gateways.ProcessExit;
import group.gnometrading.gateways.feed.Feeds;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectState;
import group.gnometrading.gateways.feed.probe.BookProber;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.md.Source;
import group.gnometrading.md.Stream;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.utils.CauseChain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;

/**
 * A session's market data: each listing's book stream read through the same feeds a collector shard uses, its markets
 * on their sources' connections (the YES and NO listings of one market share a subscription), with a REST probe, and
 * ticked once a second on a side thread for connection rolls and quarantine retries.
 *
 * <p>The rings exist from construction, so pollers can be created before anything publishes; {@link #start} opens the
 * connections.
 */
final class SessionFeeds implements TradingOrchestrator.MarketData {

    private static final long TICK_MILLIS = 1_000;
    private static final long CLOSE_TIMEOUT_SECONDS = 10;

    private record Market(Listing listing, Stream stream, Source source, SequencedRingBuffer<Mbp10Schema> ring) {}

    /**
     * A listing's market as the heartbeat sees it: not delivering unless its book is trusted. An EMPTY market is
     * connected and simply has no book; a prediction market can sit like that for hours.
     */
    record Health(String name, Subject subject) implements GatewayHealth {
        @Override
        public boolean isReconnecting() {
            final SubjectState state = subject.state();
            return !state.trusted() && state != SubjectState.EMPTY;
        }

        @Override
        public long bookResyncs() {
            return subject.resyncs();
        }
    }

    private final Logger logger;
    private final Feeds feeds;
    private final FeedSources sources;
    private final BookProber prober;
    private final List<Market> markets = new ArrayList<>();
    private final List<GatewayHealth> health = new ArrayList<>();
    private ScheduledExecutorService ticker;
    private GnomeAgentRunner proberRunner;

    /**
     * @param sequence the session sequence for a lone listing, whose ring the strategy reads directly; null gives each
     *     listing's ring a sequence of its own, for the multiplexer to copy from
     * @param spinReads whether readers spin rather than block, for readers on their own isolated cores
     * @param exit what a connection that can't connect or keeps failing does: end the session
     * @param clients makes each connection's websocket client: TLS, or a replay of recorded frames in a test
     */
    SessionFeeds(
            final Logger logger,
            final SecurityMaster securityMaster,
            final FeedVenues.Context context,
            final List<Listing> listings,
            final GlobalSequence sequence,
            final boolean spinReads,
            final ProcessExit exit,
            final EpochNanoClock clock,
            final WebSocketFeedOpener.Clients clients) {
        this.logger = logger;
        this.prober = new BookProber(logger, clock, BookProber.Settings.DEFAULT);
        this.feeds = new Feeds(logger, prober);
        this.sources = new FeedSources(
                feeds, context, spinReads, exit, source -> WebSocketFeedOpener.RawCaptures.NONE, clients);
        for (Listing listing : listings) {
            final Stream stream = securityMaster.streamForListing(listing.listingId());
            if (stream == null) {
                throw new IllegalStateException("Listing " + listing.listingId() + " has no book stream");
            }
            markets.add(new Market(
                    listing,
                    stream,
                    securityMaster.getSource(stream.sourceId()),
                    // A market's producer changes thread when a roll moves it to a new connection.
                    new SequencedRingBuffer<>(
                            Mbp10Schema::new,
                            sequence != null ? sequence : new GlobalSequence(),
                            SequencedRingBuffer.DEFAULT_SIZE,
                            SequencedRingBuffer.Producers.TAKING_TURNS)));
        }
    }

    /** Each listing's ring, in the order of the listings. */
    List<SequencedRingBuffer<?>> rings() {
        final List<SequencedRingBuffer<?>> rings = new ArrayList<>(markets.size());
        for (Market market : markets) {
            rings.add(market.ring());
        }
        return rings;
    }

    /** Opens the connections and starts the probe and the tick; the strategy's pollers must already exist. */
    void start(final ErrorHandler errorHandler) throws Exception {
        final long now = System.nanoTime();
        for (Market market : markets) {
            sources.ensure(market.source());
            feeds.addStream(
                    market.stream().streamId(),
                    market.source().code(),
                    market.stream().nativeSubjectId(),
                    market.ring(),
                    now);
            health.add(new Health(
                    "market data " + market.listing().listingId(),
                    feeds.subject(market.stream().streamId())));
        }
        proberRunner = new GnomeAgentRunner(prober, errorHandler);
        GnomeAgentRunner.startOnThread(proberRunner);
        ticker = Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, "feed-tick");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleWithFixedDelay(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            feeds.tick(System.nanoTime());
        } catch (Throwable error) {
            // A scheduled task that throws is never run again; a failed roll mustn't end every later one.
            logger.logf(LogMessage.UNKNOWN_ERROR, "Feed tick failed: %s", CauseChain.of(error));
        }
    }

    @Override
    public List<GatewayHealth> health() {
        return health;
    }

    /** Stops the tick first, as the feeds are only ever touched by one thread at a time, then every connection. */
    @Override
    public void close() throws Exception {
        if (proberRunner != null) {
            proberRunner.close();
        }
        if (ticker == null) {
            feeds.closeAll();
            return;
        }
        // On the ticker's thread, after any tick: a roll in progress there changes the connections closeAll walks.
        ticker.execute(feeds::closeAll);
        ticker.shutdown();
        if (!ticker.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Feed tick still running; leaving its connections to the exit");
        }
    }
}
