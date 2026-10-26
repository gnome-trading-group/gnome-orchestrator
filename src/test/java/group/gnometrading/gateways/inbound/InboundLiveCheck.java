package group.gnometrading.gateways.inbound;

import group.gnometrading.SecurityMaster;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.GatewayRunners;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.md.Stream;
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
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Runs each market data reader against its live feed, wired by its real orchestrator as a session wires it, for
 * {@code -Dseconds}, and reports what it published: book updates, trades, whether the last book is sane, and any
 * reconnect or error it logged. Read-only. Pick feeds with {@code -Dfeeds=HYPERLIQUID:ETH,LIGHTER:0,...} as
 * {@code EXCHANGE_CODE:exchangeSecurityId}; dev properties, so Kalshi is its demo.
 */
public final class InboundLiveCheck {

    private InboundLiveCheck() {}

    public static void main(final String[] args) throws Exception {
        final int seconds = Integer.getInteger("seconds", 60);
        final Session session = new Session(args);
        final List<Feed> feeds = new ArrayList<>();
        int id = 1;
        for (final String spec : System.getProperty("feeds").split(",")) {
            final int colon = spec.indexOf(':');
            feeds.add(new Feed(session, spec.substring(0, colon), spec.substring(colon + 1), id++));
        }
        for (final Feed feed : feeds) {
            feed.start();
        }
        final long deadline = System.currentTimeMillis() + seconds * 1_000L;
        while (System.currentTimeMillis() < deadline) {
            int polled = 0;
            for (final Feed feed : feeds) {
                polled += feed.poller.poll();
            }
            if (polled == 0) {
                Thread.sleep(1);
            }
        }
        for (final Feed feed : feeds) {
            feed.close();
            System.out.println("#### " + feed.report(session.logged(feed.exchangeCode)));
        }
        System.exit(0);
    }

    private static final class Feed {
        final String exchangeCode;
        final String securityId;
        final DefaultInboundOrchestrator<?> orchestrator;
        final SequencedPoller poller;
        GatewayRunners runners;
        java.io.PrintWriter rawOut;
        int books;
        int trades;
        int crossedOrEmptyBooks;
        int suspectBooks;
        int booksAfterSuspect;
        long bid = Mbp10Encoder.bidPrice0NullValue();
        long ask = Mbp10Encoder.askPrice0NullValue();
        long lastTrade = Mbp10Encoder.priceNullValue();

        @SuppressWarnings("unchecked")
        Feed(final Session session, final String exchangeCode, final String securityId, final int id) {
            this.exchangeCode = exchangeCode;
            this.securityId = securityId;
            final Listing listing = new Listing(
                    id,
                    new Exchange(id, exchangeCode, exchangeCode, "global"),
                    new Security(id, securityId, null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                    securityId,
                    securityId);
            this.orchestrator = session.child(listing);
            final SequencedRingBuffer<Mbp10Schema> buffer =
                    (SequencedRingBuffer<Mbp10Schema>) this.orchestrator.getSequencedRingBuffer();
            this.poller = buffer.createPoller((seq, templateId, bytes, length) -> {
                final Mbp10Schema schema = new Mbp10Schema();
                schema.buffer.putBytes(0, bytes, 0, length);
                schema.wrap(schema.buffer);
                onSchema(schema);
            });
        }

        void start() throws IOException {
            final String rawDir = System.getProperty("raw");
            if (rawDir != null) {
                // Every message as received, one per line, for studying the feed: -Draw=<directory>.
                final java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(
                        rawDir + "/" + this.exchangeCode + "-" + Math.abs(this.securityId.hashCode()) + ".jsonl"));
                this.rawOut = out;
                this.orchestrator.setRawDataSink((recv, buffer) ->
                        out.println(recv + " " + java.nio.charset.StandardCharsets.UTF_8.decode(buffer.duplicate())));
            }
            this.runners = this.orchestrator.startGatewayAgents();
        }

        void close() throws Exception {
            this.runners.close();
            if (this.rawOut != null) {
                this.rawOut.close();
            }
        }

        private void onSchema(final Mbp10Schema schema) {
            if (schema.decoder.action() == Action.Trade) {
                this.trades++;
                this.lastTrade = schema.decoder.price();
                return;
            }
            this.books++;
            if (schema.decoder.flags().badData()) {
                this.suspectBooks++;
            } else if (this.suspectBooks > 0) {
                this.booksAfterSuspect++;
            }
            this.bid = schema.decoder.bidPrice0();
            this.ask = schema.decoder.askPrice0();
            final boolean empty =
                    this.bid == Mbp10Encoder.bidPrice0NullValue() && this.ask == Mbp10Encoder.askPrice0NullValue();
            final boolean crossed = this.bid != Mbp10Encoder.bidPrice0NullValue()
                    && this.ask != Mbp10Encoder.askPrice0NullValue()
                    && this.bid >= this.ask;
            if (empty || crossed) {
                this.crossedOrEmptyBooks++;
            }
        }

        String report(final Map<LogMessage, Integer> logged) {
            return this.exchangeCode + ":" + this.securityId + "  books=" + this.books + " trades=" + this.trades
                    + " crossedOrEmpty=" + this.crossedOrEmptyBooks + " suspect=" + this.suspectBooks
                    + " booksAfterSuspect=" + this.booksAfterSuspect + " lastBid=" + px(this.bid) + " lastAsk="
                    + px(this.ask) + " lastTrade=" + px(this.lastTrade) + "  logged=" + logged;
        }

        private static String px(final long price) {
            return price == Mbp10Encoder.priceNullValue()
                    ? "-"
                    : String.format("%.6f", (double) price / Statics.PRICE_SCALING_FACTOR);
        }
    }

    /** What a trading session gives its market data gateways. */
    static final class Session extends Orchestrator {
        private final String[] args;
        private final CountingLogger logger = new CountingLogger();

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

        @Override
        public void configure() {}

        DefaultInboundOrchestrator<?> child(final Listing listing) {
            final Stream stream = getInstance(SecurityMaster.class).streamForListing(listing.listingId());
            final Integer dropAt = Integer.getInteger("drop");
            if (dropAt == null) {
                return createChildOrchestrator(
                        DefaultInboundOrchestrator.findInboundOrchestrator(listing),
                        Map.of(Listing.class, listing, Stream.class, stream));
            }
            // -Ddrop=N: the feed's Nth message after connecting never reaches the reader, as if the venue had lost it,
            // to see the whole gap path (flagged book, reconnect, fresh snapshot) against the live feed.
            try {
                final String property = WS_URL_PROPERTIES.get(listing.exchange().exchangeCode());
                final URI uri = new URI(getInstance(Properties.class).getStringProperty(property));
                return createChildOrchestrator(
                        DefaultInboundOrchestrator.findInboundOrchestrator(listing),
                        Map.of(
                                Listing.class,
                                listing,
                                Stream.class,
                                stream,
                                WebSocketClient.class,
                                new DroppingClient(uri, dropAt)));
            } catch (final IOException | URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The notable messages logged; the gateways all share one logger, so they aren't split by venue. */
        Map<LogMessage, Integer> logged(final String exchangeCode) {
            final Map<LogMessage, Integer> counts = new java.util.TreeMap<>();
            this.logger.counts.forEach((message, count) -> counts.put(message, count.get()));
            return counts;
        }
    }

    private static final Map<String, String> WS_URL_PROPERTIES = Map.of(
            "KALSHI", "kalshi.ws.url",
            "POLYMARKET_INTL", "polymarket.intl.ws.url",
            "LIGHTER", "lighter.ws.url",
            "POLYMARKET_US", "polymarket.us.ws.url",
            "HYPERLIQUID", "hyperliquid.ws.url");

    /** Loses one message, the Nth after the first connect, once. */
    static final class DroppingClient extends WebSocketClient {
        private final int dropAt;
        private int seen;
        private boolean dropped;

        DroppingClient(final URI uri, final int dropAt) throws IOException {
            super(uri, new RFC6455(), new NativeSSLSocketFactory(), 1 << 18, 1 << 16);
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
