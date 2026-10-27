package group.gnometrading.feeds;

import group.gnometrading.SecurityMaster;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.feed.Feeds;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.probe.BookProber;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.md.Source;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketResponse;
import group.gnometrading.networking.websockets.drafts.RFC6455;
import group.gnometrading.networking.websockets.enums.Opcode;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.Mbp10Encoder;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.shared.SecretCache;
import group.gnometrading.shared.SecurityMasterModule;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Reads live markets through the feeds a shard and a session use, for {@code -Dseconds}, and reports what each
 * published (book updates, trades, whether the last book is sane), its market's state and resyncs, what the REST probe
 * found, and any reconnect or error logged. Read-only. Pick markets with
 * {@code -Dmarkets=HYPERLIQUID:ETH,KALSHI:KXBTC-26:yes,...} as {@code SOURCE:exchangeSecurityId}; capacities come from
 * the registry's sources; dev properties, so Kalshi is its demo.
 *
 * <p>{@code -DmaxPerConnection=n} stands in for a capacity the registry stage doesn't have yet (default 50).
 * {@code -Draw=<dir>} writes every connection's frames, one per line. {@code -Ddrop=N} makes every connection lose
 * its Nth text frame after connecting, once, to see the gap path (flagged books, resync, fresh snapshot) live.
 */
public final class FeedLiveCheck {

    // Probed every 20 seconds rather than 15 minutes, so a short check sees the probe at work.
    private static final BookProber.Settings PROBE = new BookProber.Settings(
            TimeUnit.SECONDS.toNanos(20),
            TimeUnit.SECONDS.toNanos(60),
            BookProber.Settings.DEFAULT.silenceGapMultiple());

    private FeedLiveCheck() {}

    public static void main(final String[] args) throws Exception {
        final int seconds = Integer.getInteger("seconds", 60);
        final Session session = new Session(args);
        final CountingLogger logger = session.logger;
        final SecurityMaster securityMaster = session.get(SecurityMaster.class);
        final BookProber prober = new BookProber(logger, new SystemEpochNanoClock(), PROBE);
        final Feeds feeds = new Feeds(logger, prober);
        final Integer dropAt = Integer.getInteger("drop");
        final FeedSources sources = new FeedSources(
                feeds,
                new FeedVenues.Context(
                        logger,
                        session.get(Properties.class),
                        session.get(SecretCache.class),
                        MarketDataCredentials.SESSION,
                        new SystemEpochNanoClock()),
                false,
                (status, cause) -> System.out.println("#### gave up on a connection: " + cause),
                FeedLiveCheck::rawCaptures,
                dropAt == null
                        ? WebSocketFeedOpener.Clients.TLS
                        : (uri, readBufferBytes) -> new DroppingClient(uri, readBufferBytes, dropAt));

        final List<Market> markets = new ArrayList<>();
        int streamId = 1;
        for (final String spec : System.getProperty("markets").split(",")) {
            final int colon = spec.indexOf(':');
            final Source source = withCapacity(securityMaster.getSource(spec.substring(0, colon)));
            sources.ensure(source);
            final Market market = new Market(source.code(), spec.substring(colon + 1), streamId++);
            feeds.addStream(market.streamId, market.source, market.securityId, market.ring, System.nanoTime());
            markets.add(market);
        }
        final GnomeAgentRunner proberRunner = new GnomeAgentRunner(prober, Throwable::printStackTrace);
        GnomeAgentRunner.startOnThread(proberRunner);
        final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleWithFixedDelay(
                () -> {
                    try {
                        feeds.tick(System.nanoTime());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                },
                1,
                1,
                TimeUnit.SECONDS);

        final long deadline = System.currentTimeMillis() + seconds * 1_000L;
        while (System.currentTimeMillis() < deadline) {
            int polled = 0;
            for (final Market market : markets) {
                polled += market.poller.poll();
            }
            if (polled == 0) {
                Thread.sleep(1);
            }
        }
        ticker.shutdown();
        ticker.awaitTermination(70, TimeUnit.SECONDS);
        proberRunner.close();
        feeds.closeAll();
        for (final Market market : markets) {
            System.out.println("#### " + market.report(feeds.subject(market.streamId)));
        }
        System.out.println(
                "#### probes=" + prober.probes() + " mismatches=" + prober.mismatches() + " logged=" + logger.counts);
        System.exit(0);
    }

    /**
     * A registry stage without migration 051 has no capacities; this check then packs {@code -DmaxPerConnection} (50)
     * markets a connection, where a shard would refuse to start.
     */
    private static Source withCapacity(final Source source) {
        if (source.maxPerConnection() > 0) {
            return source;
        }
        final int stand = Integer.getInteger("maxPerConnection", 50);
        System.out.println(
                "#### " + source.code() + " has no capacity in this registry; using " + stand + " a connection");
        return new Source(
                source.sourceId(),
                source.code(),
                source.kind(),
                source.exchangeId(),
                source.homeRegion(),
                source.adapter(),
                source.families(),
                source.active(),
                stand,
                "IP",
                0,
                0);
    }

    private static WebSocketFeedOpener.RawCaptures rawCaptures(final String source) {
        final String dir = System.getProperty("raw");
        if (dir == null) {
            return WebSocketFeedOpener.RawCaptures.NONE;
        }
        return connection -> {
            final PrintWriter out;
            try {
                out = new PrintWriter(new FileWriter(dir + "/" + connection + ".jsonl"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return new WebSocketFeedOpener.RawCapture() {
                @Override
                public void capture(final long recvTimestamp, final ByteBuffer buffer) {
                    out.println(recvTimestamp + " " + StandardCharsets.UTF_8.decode(buffer.duplicate()));
                }

                @Override
                public void flush() {
                    out.close();
                }
            };
        };
    }

    /** One market's stream and what it published. */
    private static final class Market {
        final String source;
        final String securityId;
        final int streamId;
        final SequencedRingBuffer<Mbp10Schema> ring = new SequencedRingBuffer<>(
                Mbp10Schema::new,
                new GlobalSequence(),
                SequencedRingBuffer.DEFAULT_SIZE,
                SequencedRingBuffer.Producers.TAKING_TURNS);
        final SequencedPoller poller;
        int books;
        int trades;
        int crossedOrEmptyBooks;
        int flaggedBooks;
        int booksAfterFlagged;
        long bid = Mbp10Encoder.bidPrice0NullValue();
        long ask = Mbp10Encoder.askPrice0NullValue();
        long lastTrade = Mbp10Encoder.priceNullValue();

        Market(final String source, final String securityId, final int streamId) {
            this.source = source;
            this.securityId = securityId;
            this.streamId = streamId;
            this.poller = ring.createPoller((seq, templateId, bytes, length) -> {
                final Mbp10Schema schema = new Mbp10Schema();
                schema.buffer.putBytes(0, bytes, 0, length);
                schema.wrap(schema.buffer);
                onSchema(schema);
            });
        }

        private void onSchema(final Mbp10Schema schema) {
            if (schema.decoder.action() == Action.Trade) {
                trades++;
                lastTrade = schema.decoder.price();
                return;
            }
            books++;
            if (schema.decoder.flags().badData()) {
                flaggedBooks++;
            } else if (flaggedBooks > 0) {
                booksAfterFlagged++;
            }
            bid = schema.decoder.bidPrice0();
            ask = schema.decoder.askPrice0();
            final boolean empty = bid == Mbp10Encoder.bidPrice0NullValue() && ask == Mbp10Encoder.askPrice0NullValue();
            final boolean crossed =
                    bid != Mbp10Encoder.bidPrice0NullValue() && ask != Mbp10Encoder.askPrice0NullValue() && bid >= ask;
            if (empty || crossed) {
                crossedOrEmptyBooks++;
            }
        }

        String report(final Subject subject) {
            final long now = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
            return source + ":" + securityId + "  state=" + (subject == null ? "-" : subject.state()) + " resyncs="
                    + (subject == null ? 0 : subject.resyncs()) + " verifiedAgoS="
                    + (subject == null || subject.lastVerifiedNanos() == 0
                            ? "never"
                            : TimeUnit.NANOSECONDS.toSeconds(now - subject.lastVerifiedNanos()))
                    + "  books=" + books + " trades=" + trades + " crossedOrEmpty=" + crossedOrEmptyBooks + " flagged="
                    + flaggedBooks + " booksAfterFlagged=" + booksAfterFlagged + " lastBid=" + px(bid) + " lastAsk="
                    + px(ask) + " lastTrade=" + px(lastTrade);
        }

        private static String px(final long price) {
            return price == Mbp10Encoder.priceNullValue()
                    ? "-"
                    : String.format("%.6f", (double) price / Statics.PRICE_SCALING_FACTOR);
        }
    }

    /** What the feeds need from a session: dev properties, the dev registry and dev secrets. */
    static final class Session extends Orchestrator {
        private final String[] args;
        final CountingLogger logger = new CountingLogger();

        Session(final String[] args) {
            this.args = args;
        }

        @Provides
        @Singleton
        public Properties provideProperties() throws IOException {
            return new Properties("orchestrator.dev.properties", this.args);
        }

        @Provides
        @Singleton
        public Logger provideLogger() {
            return this.logger;
        }

        @Provides
        @Singleton
        public SecretsManagerClient provideSecretsManager() {
            return SecretsManagerClient.builder()
                    .region(Region.US_EAST_1)
                    .credentialsProvider(ProfileCredentialsProvider.create("dev"))
                    .build();
        }

        @Provides
        @Singleton
        public SecretCache provideSecretCache(final SecretsManagerClient secretsManager) {
            return new SecretCache(secretsManager);
        }

        @Provides
        @Singleton
        public SecurityMaster provideSecurityMaster(final Properties properties) {
            final SecurityMasterModule registry = new SecurityMasterModule();
            return registry.provideSecurityMaster(
                    registry.provideRegistryConnection(registry.provideRegistryEndpoint(properties)));
        }

        @Override
        public void configure() {}

        <T> T get(final Class<T> type) {
            return getInstance(type);
        }
    }

    /** Loses one message, the Nth after the first connect, once. */
    static final class DroppingClient extends WebSocketClient {
        private final int dropAt;
        private int seen;
        private boolean dropped;

        DroppingClient(final URI uri, final int readBufferBytes, final int dropAt) throws IOException {
            super(uri, new RFC6455(), new NativeSSLSocketFactory(), readBufferBytes, 1 << 16);
            this.dropAt = dropAt;
        }

        @Override
        public WebSocketResponse read() throws IOException {
            final WebSocketResponse response = super.read();
            if (!this.dropped
                    && response.isSuccess()
                    && response.getOpcode() == Opcode.TEXT
                    && response.getBody() != null
                    && ++this.seen >= this.dropAt
                    && shouldDrop(response)) {
                this.dropped = true;
                System.out.println("#### dropped message " + this.dropAt + ": "
                        + java.nio.charset.StandardCharsets.UTF_8
                                .decode(response.getBody().duplicate())
                                .toString()
                                .substring(0, Math.min(160, response.getBody().remaining())));
                return super.read();
            }
            return response;
        }
    }

    /**
     * With {@code -Ddrop.best=<token>}, only a Polymarket change that removes that token's best bid or ask counts: the
     * venue's best price moves, and a book that missed the removal still shows the old one. (A change deeper in the
     * book, or of size only, leaves the best prices alike, so the best-price check can't see it.)
     */
    private static boolean shouldDrop(final WebSocketResponse response) {
        final String token = System.getProperty("drop.best");
        if (token == null) {
            return true;
        }
        final String text = java.nio.charset.StandardCharsets.UTF_8
                .decode(response.getBody().duplicate())
                .toString();
        final java.util.regex.Matcher entry = java.util.regex.Pattern.compile("\\{\"asset_id\":\"" + token
                        + "\", \"price\":\"([^\"]+)\", \"size\":\"([^\"]+)\", \"side\":\"([A-Z]+)\".*?"
                        + "\"best_bid\":\"([^\"]+)\", \"best_ask\":\"([^\"]+)\"")
                .matcher(text);
        while (entry.find()) {
            final double price = Double.parseDouble(entry.group(1));
            final boolean removed = Double.parseDouble(entry.group(2)) == 0;
            final boolean bid = entry.group(3).equals("BUY");
            if (removed
                    && (bid
                            ? price > Double.parseDouble(entry.group(4))
                            : price < Double.parseDouble(entry.group(5)))) {
                return true;
            }
        }
        return false;
    }

    /** Prints everything, and counts what says something went wrong. */
    private static final class CountingLogger implements Logger {
        final Map<LogMessage, AtomicInteger> counts = new ConcurrentHashMap<>();
        private final ConsoleLogger console = new ConsoleLogger(new SystemEpochNanoClock());

        private void count(final LogMessage msg) {
            if (msg != LogMessage.SOCKET_CONNECTING && msg != LogMessage.SOCKET_CONNECTED && msg != LogMessage.DEBUG) {
                this.counts.computeIfAbsent(msg, m -> new AtomicInteger()).incrementAndGet();
            }
        }

        @Override
        public void log(final LogMessage msg) {
            count(msg);
            this.console.log(msg);
        }

        @Override
        public void log(final LogMessage msg, final long v1) {
            count(msg);
            this.console.log(msg, v1);
        }

        @Override
        public void log(final LogMessage msg, final long v1, final long v2) {
            count(msg);
            this.console.log(msg, v1, v2);
        }

        @Override
        public void log(final LogMessage msg, final long v1, final long v2, final long v3) {
            count(msg);
            this.console.log(msg, v1, v2, v3);
        }

        @Override
        public void log(final LogMessage msg, final long v1, final long v2, final long v3, final long v4) {
            count(msg);
            this.console.log(msg, v1, v2, v3, v4);
        }

        @Override
        public void log(
                final LogMessage msg, final long v1, final long v2, final long v3, final long v4, final long v5) {
            count(msg);
            this.console.log(msg, v1, v2, v3, v4, v5);
        }

        @Override
        public void log(
                final LogMessage msg,
                final long v1,
                final long v2,
                final long v3,
                final long v4,
                final long v5,
                final long v6) {
            count(msg);
            this.console.log(msg, v1, v2, v3, v4, v5, v6);
        }

        @Override
        public void logf(final LogMessage msg, final String format, final Object... args) {
            count(msg);
            this.console.logf(msg, format, args);
        }
    }
}
