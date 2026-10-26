package group.gnometrading.gateways.outbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.codecs.json.JsonDecoder;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.GatewayRunners;
import group.gnometrading.gateways.credentials.KalshiCredentials;
import group.gnometrading.gateways.credentials.PolymarketUsCredentials;
import group.gnometrading.gateways.inbound.InboundGateway;
import group.gnometrading.gateways.inbound.InboundJsonWebSocketWriter;
import group.gnometrading.gateways.inbound.InboundSocketReader;
import group.gnometrading.gateways.inbound.exchanges.kalshi.KalshiInboundReader;
import group.gnometrading.gateways.inbound.exchanges.polymarket.us.PolymarketUsInboundReader;
import group.gnometrading.gateways.outbound.exchanges.polymarket.us.PolymarketUsAuthSigner;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.book.Mbp10Decoder;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;

/**
 * Read-only, against the real venue: runs the real inbound gateway on several listings, one connection each as a
 * session wires them, and keeps checking the books it publishes against the venue's REST book, in the listing's own
 * terms (a NO listing's book is the complement). Not a unit test.
 *
 * <p>{@code -Dvenue=kalshi} (production; {@code TICKER:yes}, {@code TICKER:no}, or a bare ticker) or
 * {@code -Dvenue=pmus} ({@code slug:long}, {@code slug:short}); {@code -Dlistings=a,b,...}, {@code -Dminutes} (default
 * 10), {@code -Dsample.seconds} (default 5, per listing). The REST book and the socket are read at different moments, so a sample matches if the top
 * five levels equal any book published from {@value #SKEW_MILLIS} ms before the REST call to as long after its answer.
 * A missed or misapplied delta leaves the book wrong until the next snapshot, so it shows as mismatches that persist.
 */
public final class InboundCrossCheck {

    private static final String KALSHI_API = "https://external-api.kalshi.com";
    private static final String KALSHI_WS = "wss://external-api-ws.kalshi.com/trade-api/ws/v2";
    private static final String PM_US_WS = "wss://api.polymarket.us/v1/ws/markets";
    private static final String PM_US_BOOK = "https://gateway.polymarket.us/v1/markets/";
    private static final long SKEW_MILLIS = 2_000;
    private static final int LEVELS = 5;
    private static final ObjectMapper JSON = new ObjectMapper();

    private InboundCrossCheck() {}

    public static void main(final String[] args) throws Exception {
        try {
            run();
        } catch (final Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run() throws Exception {
        final Venue venue = System.getProperty("venue", "kalshi").equals("pmus") ? new PolymarketUs() : new Kalshi();
        final String[] ids = System.getProperty("listings").split(",");
        final long endMillis = System.currentTimeMillis() + Long.getLong("minutes", 10) * 60_000;
        final long sampleMillis = Long.getLong("sample.seconds", 5) * 1_000;

        final SystemEpochNanoClock nanoClock = new SystemEpochNanoClock();
        final Map<String, Integer> logged = new ConcurrentHashMap<>();
        final Logger logger = new CountingLogger(nanoClock, logged);
        final List<Feed> feeds = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            feeds.add(new Feed(i + 1, ids[i], venue, logger, nanoClock));
        }
        try {
            while (System.currentTimeMillis() < endMillis) {
                final long cycleStart = System.currentTimeMillis();
                for (final Feed feed : feeds) {
                    feed.sample();
                }
                Thread.sleep(Math.max(0, sampleMillis - (System.currentTimeMillis() - cycleStart)));
            }
        } finally {
            for (final Feed feed : feeds) {
                feed.close();
            }
        }
        System.out.println("\n#### logged: " + new TreeMap<>(logged));
        for (final Feed feed : feeds) {
            feed.report();
        }
    }

    /** One listing: its own reader, connection and gateway, the books it published, and what REST said. */
    private static final class Feed {
        private final String listingId;
        private final Venue venue;
        private final GatewayRunners runners;
        private final GnomeAgentRunner booksRunner;
        private final List<Long> bookTimes = new ArrayList<>();
        private final List<String> bookStates = new ArrayList<>();
        private final Mbp10Schema schema = new Mbp10Schema();
        private int books;
        private int crossed;
        private int samples;
        private int noBookYet;
        private int matched;
        private int mismatched;
        private int runLength;
        private int longestRun;
        private final List<String> mismatches = new ArrayList<>();

        Feed(
                final int id,
                final String listingId,
                final Venue venue,
                final Logger logger,
                final SystemEpochNanoClock nanoClock)
                throws Exception {
            this.listingId = listingId;
            this.venue = venue;
            final SequencedRingBuffer<Mbp10Schema> ring =
                    new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
            this.booksRunner = new GnomeAgentRunner(
                    ring.createPoller((seq, templateId, buffer, length) -> onBook(buffer))::poll,
                    Throwable::printStackTrace);
            GnomeAgentRunner.startOnThread(this.booksRunner);

            final WebSocketClient ws = new WebSocketClientBuilder()
                    .withURI(venue.webSocket())
                    .withSocketFactory(new NativeSSLSocketFactory())
                    .withReadBufferSize(1 << 18)
                    .build();
            final InboundJsonWebSocketWriter writer = new InboundJsonWebSocketWriter(ws, new JsonEncoder());
            final InboundSocketReader<Mbp10Schema> reader =
                    venue.reader(logger, ring, nanoClock, writer, venue.listing(id, listingId), ws);
            final InboundGateway gateway = new InboundGateway(logger, venue.config(), reader, new SystemEpochClock());
            this.runners = GatewayRunners.marketData(
                            id,
                            reader.pauseControl,
                            new GnomeAgentRunner(reader, Throwable::printStackTrace),
                            new GnomeAgentRunner(writer, Throwable::printStackTrace),
                            new GnomeAgentRunner(gateway, Throwable::printStackTrace))
                    .start();
        }

        private synchronized void onBook(final org.agrona.concurrent.UnsafeBuffer buffer) {
            this.schema.wrap(buffer);
            final Mbp10Decoder d = this.schema.decoder;
            this.books++;
            if (d.bidPrice0() != Mbp10Decoder.bidPrice0NullValue()
                    && d.askPrice0() != Mbp10Decoder.askPrice0NullValue()
                    && d.bidPrice0() >= d.askPrice0()) {
                this.crossed++;
            }
            final String state = ourTop(d);
            if (this.bookStates.isEmpty()
                    || !this.bookStates.get(this.bookStates.size() - 1).equals(state)) {
                this.bookTimes.add(System.currentTimeMillis());
                this.bookStates.add(state);
            }
        }

        void sample() throws Exception {
            final RestBook restBook = this.venue.restTop(this.listingId);
            final String rest = restBook.top();
            synchronized (this) {
                this.samples++;
                if (this.bookStates.isEmpty()) {
                    this.noBookYet++;
                    return;
                }
                if (seenBetween(restBook.fromMillis() - SKEW_MILLIS, restBook.toMillis() + SKEW_MILLIS, rest)) {
                    this.matched++;
                    this.runLength = 0;
                    return;
                }
                this.mismatched++;
                this.runLength++;
                this.longestRun = Math.max(this.longestRun, this.runLength);
                if (this.mismatches.size() < 5) {
                    this.mismatches.add("  at " + restBook.toMillis() + "\n    rest " + rest + "\n    ours "
                            + this.bookStates.get(this.bookStates.size() - 1));
                }
            }
        }

        /** The book in force at the window's start, and every one published during it. */
        private boolean seenBetween(final long from, final long to, final String rest) {
            for (int i = this.bookTimes.size() - 1; i >= 0; i--) {
                final long t = this.bookTimes.get(i);
                if (t <= to && this.bookStates.get(i).equals(rest)) {
                    return true;
                }
                if (t < from) {
                    return false;
                }
            }
            return false;
        }

        private static String ourTop(final Mbp10Decoder d) {
            final long[] bidPx = {d.bidPrice0(), d.bidPrice1(), d.bidPrice2(), d.bidPrice3(), d.bidPrice4()};
            final long[] bidSz = {d.bidSize0(), d.bidSize1(), d.bidSize2(), d.bidSize3(), d.bidSize4()};
            final long[] askPx = {d.askPrice0(), d.askPrice1(), d.askPrice2(), d.askPrice3(), d.askPrice4()};
            final long[] askSz = {d.askSize0(), d.askSize1(), d.askSize2(), d.askSize3(), d.askSize4()};
            final StringBuilder sb = new StringBuilder("bids");
            for (int i = 0; i < LEVELS && bidPx[i] != Mbp10Decoder.bidPrice0NullValue(); i++) {
                level(
                        sb,
                        bidPx[i] / (double) Statics.PRICE_SCALING_FACTOR,
                        bidSz[i] / (double) Statics.SIZE_SCALING_FACTOR);
            }
            sb.append(" | asks");
            for (int i = 0; i < LEVELS && askPx[i] != Mbp10Decoder.askPrice0NullValue(); i++) {
                level(
                        sb,
                        askPx[i] / (double) Statics.PRICE_SCALING_FACTOR,
                        askSz[i] / (double) Statics.SIZE_SCALING_FACTOR);
            }
            return sb.toString();
        }

        static void level(final StringBuilder sb, final double price, final double size) {
            sb.append(String.format(Locale.ROOT, " %.4fx%.2f", price, size));
        }

        void close() throws Exception {
            KalshiLiveSmoke.closeTimed(this.listingId, this.runners);
            this.booksRunner.close();
        }

        synchronized void report() {
            System.out.println(
                    "#### " + this.listingId + "  books=" + this.books + " distinctTop5=" + this.bookStates.size()
                            + " crossed=" + this.crossed + "  samples=" + this.samples + " matched=" + this.matched
                            + " mismatched=" + this.mismatched + " longestMismatchRun=" + this.longestRun
                            + " noBookYet=" + this.noBookYet);
            this.mismatches.forEach(System.out::println);
        }
    }

    /** What differs by venue: how its reader is built, and how its REST book reads in a listing's terms. */
    private interface Venue {
        URI webSocket() throws Exception;

        GatewayConfig config();

        Listing listing(int id, String exchangeSecurityId);

        InboundSocketReader<Mbp10Schema> reader(
                Logger logger,
                SequencedRingBuffer<Mbp10Schema> ring,
                SystemEpochNanoClock clock,
                InboundJsonWebSocketWriter writer,
                Listing listing,
                WebSocketClient ws)
                throws Exception;

        /** The top levels a side, best first, and when the venue's book was in that state. */
        RestBook restTop(String exchangeSecurityId) throws Exception;
    }

    /** {@code top} as {@code "bids p1xs1 ... | asks ..."}; the venue's book was in that state within the window. */
    private record RestBook(String top, long fromMillis, long toMillis) {}

    /** Production Kalshi, with the prod market-data key. */
    private static final class Kalshi implements Venue {
        private final KalshiCredentials creds = KalshiLiveSmoke.credentials(
                System.getProperty("aws.profile", "prod"),
                System.getProperty("prod.secret", "gnome/market-data/kalshi"));

        @Override
        public URI webSocket() throws Exception {
            return new URI(KALSHI_WS);
        }

        @Override
        public GatewayConfig config() {
            return new GatewayConfig.Builder().build();
        }

        @Override
        public Listing listing(final int id, final String exchangeSecurityId) {
            return KalshiLiveSmoke.listing(id, exchangeSecurityId);
        }

        @Override
        public InboundSocketReader<Mbp10Schema> reader(
                final Logger logger,
                final SequencedRingBuffer<Mbp10Schema> ring,
                final SystemEpochNanoClock clock,
                final InboundJsonWebSocketWriter writer,
                final Listing listing,
                final WebSocketClient ws) {
            return new KalshiInboundReader(
                    logger,
                    ring,
                    clock,
                    writer,
                    listing,
                    77,
                    ws,
                    new JsonDecoder(),
                    this.creds.apiKey(),
                    this.creds.privateKey());
        }

        /** Kalshi lists both sides as bids, best last; a listing's asks are the other side's bids at 1 - p. */
        @Override
        public RestBook restTop(final String exchangeSecurityId) throws Exception {
            final long before = System.currentTimeMillis();
            final int colon = exchangeSecurityId.lastIndexOf(':');
            final String ticker = colon > 0 ? exchangeSecurityId.substring(0, colon) : exchangeSecurityId;
            final boolean no = exchangeSecurityId.endsWith(":no");
            final JsonNode book = JSON.readTree(KalshiLiveSmoke.signedGet(
                            this.creds,
                            KALSHI_API,
                            "/trade-api/v2/markets/" + ticker + "/orderbook",
                            "?depth=" + LEVELS))
                    .path("orderbook_fp");
            final JsonNode bids = book.path(no ? "no_dollars" : "yes_dollars");
            final JsonNode other = book.path(no ? "yes_dollars" : "no_dollars");
            final StringBuilder sb = new StringBuilder("bids");
            for (int i = bids.size() - 1; i >= Math.max(0, bids.size() - LEVELS); i--) {
                Feed.level(sb, number(bids.get(i).get(0)), number(bids.get(i).get(1)));
            }
            sb.append(" | asks");
            for (int i = other.size() - 1; i >= Math.max(0, other.size() - LEVELS); i--) {
                Feed.level(
                        sb, 1 - number(other.get(i).get(0)), number(other.get(i).get(1)));
            }
            // Kalshi's REST book isn't cached: it is the book as it stood during the request.
            return new RestBook(sb.toString(), before, System.currentTimeMillis());
        }
    }

    /** Polymarket US, with the dev secret's key; its public book is priced long, best first. */
    private static final class PolymarketUs implements Venue {
        private final PolymarketUsCredentials creds = PolymarketUsLiveSmoke.credentials();
        private final HttpClient http = HttpClient.newHttpClient();

        @Override
        public URI webSocket() throws Exception {
            return new URI(PM_US_WS);
        }

        @Override
        public GatewayConfig config() {
            // As the session wires it: quiet markets only send heartbeats.
            return new GatewayConfig.Builder()
                    .withMaxSilentInterval(Duration.ofSeconds(60))
                    .build();
        }

        @Override
        public Listing listing(final int id, final String exchangeSecurityId) {
            return new Listing(
                    id,
                    new Exchange(6, "POLYMARKET_US", "Polymarket US", "us"),
                    new Security(
                            3, exchangeSecurityId, null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                    exchangeSecurityId,
                    exchangeSecurityId);
        }

        @Override
        public InboundSocketReader<Mbp10Schema> reader(
                final Logger logger,
                final SequencedRingBuffer<Mbp10Schema> ring,
                final SystemEpochNanoClock clock,
                final InboundJsonWebSocketWriter writer,
                final Listing listing,
                final WebSocketClient ws)
                throws Exception {
            return new PolymarketUsInboundReader(
                    logger,
                    ring,
                    clock,
                    writer,
                    listing,
                    77,
                    ws,
                    new JsonDecoder(),
                    new PolymarketUsAuthSigner(this.creds.apiKey(), this.creds.privateKey()));
        }

        /**
         * A short listing's bids are the long offers at 1 - p, and its asks the long bids. The book is served through a
         * cache for up to 30 s, so it describes the second its Last-Modified names, not the moment it was fetched.
         */
        @Override
        public RestBook restTop(final String exchangeSecurityId) throws Exception {
            final boolean shortSide = exchangeSecurityId.endsWith(":short");
            final String slug = PolymarketUsInboundReader.marketSlug(exchangeSecurityId);
            final HttpResponse<String> response = this.http.send(
                    HttpRequest.newBuilder(URI.create(PM_US_BOOK + slug + "/book"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            final JsonNode book = JSON.readTree(response.body()).path("marketData");
            final JsonNode bids = book.path(shortSide ? "offers" : "bids");
            final JsonNode asks = book.path(shortSide ? "bids" : "offers");
            final StringBuilder sb = new StringBuilder("bids");
            for (int i = 0; i < Math.min(LEVELS, bids.size()); i++) {
                final double price = number(bids.get(i).path("px").path("value"));
                Feed.level(sb, shortSide ? 1 - price : price, number(bids.get(i).path("qty")));
            }
            sb.append(" | asks");
            for (int i = 0; i < Math.min(LEVELS, asks.size()); i++) {
                final double price = number(asks.get(i).path("px").path("value"));
                Feed.level(sb, shortSide ? 1 - price : price, number(asks.get(i).path("qty")));
            }
            final long modified = ZonedDateTime.parse(
                            response.headers().firstValue("last-modified").orElseThrow(),
                            DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli();
            return new RestBook(sb.toString(), modified, modified + 1_000);
        }
    }

    private static double number(final JsonNode node) {
        return Double.parseDouble(node.asText());
    }

    /** Prints everything, as the console logger does, and counts each message type. */
    private static final class CountingLogger implements Logger {
        private final Map<String, Integer> counts;
        private final ConsoleLogger console;

        CountingLogger(final SystemEpochNanoClock clock, final Map<String, Integer> counts) {
            this.console = new ConsoleLogger(clock);
            this.counts = counts;
        }

        private void count(final LogMessage msg) {
            this.counts.merge(msg.name(), 1, Integer::sum);
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
