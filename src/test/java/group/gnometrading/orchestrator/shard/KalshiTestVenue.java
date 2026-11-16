package group.gnometrading.orchestrator.shard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for Kalshi's market data websocket, for load tests of a shard: it serves any market it is asked for and
 * sends book changes and trades at a set total rate, so the records a shard collects are chosen rather than whatever the
 * live markets happen to do. Frames have the shape, fields and sizes of Kalshi's own (about 320 bytes a delta), and
 * each sid numbers every frame it sends, so a shard's reader sees nothing it wouldn't from Kalshi.
 *
 * <p>Plain {@code ws://}, no TLS, and the upgrade's signed headers are not checked. The rate is spread over every
 * market subscribed on every connection, so each connection carries its share.
 *
 * <p>Run: {@code java -cp <jar>:<test-classes> group.gnometrading.orchestrator.shard.KalshiTestVenue --port=9000
 * --rate=5000 --trades=0.02}. The shard points {@code kalshi.ws.url} at {@code ws://<host>:<port>/trade-api/ws/v2}.
 * What it sends ({@link Load}):
 *
 * <ul>
 *   <li>{@code rate}: frames a second over all connections; {@code trades}: the share of them that are trades.
 *   <li>{@code hot} (tickers, comma-separated) and {@code hot-share}: that share of the rate goes to the hot markets,
 *       evenly, and the rest evenly to all others, as a few busy markets among many quiet ones.
 *   <li>{@code burst-ticker}, {@code burst-frames} and {@code burst-every}: every {@code burst-every} seconds, that
 *       many deltas on that market in one write, on top of the rate, as a sweep through a book arrives.
 * </ul>
 *
 * <p>{@code --control=<file or http(s) URL>} names a file of the same options, one {@code key=value} a line, read again
 * whenever it changes (checked every second), so a load test steps through loads without dropping the shard's
 * connections. Its values replace the command line's. A URL (a presigned S3 object, say) lets a driver elsewhere change
 * the load of a venue in a container.
 */
public final class KalshiTestVenue {

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final int LEVELS = 10;
    private static final int BOOK_SID = 1;
    private static final int TRADE_SID = 2;
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern CMD = Pattern.compile("\"cmd\"\\s*:\\s*\"(\\w+)\"");
    private static final Pattern ACTION = Pattern.compile("\"action\"\\s*:\\s*\"(\\w+)\"");
    private static final Pattern TICKERS = Pattern.compile("\"market_tickers\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    private volatile Load load;
    private final Set<Connection> live = ConcurrentHashMap.newKeySet();
    private final AtomicInteger markets = new AtomicInteger();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicLong frames = new AtomicLong();

    /** What the venue sends; see the class comment for each option. */
    record Load(
            double rate,
            double trades,
            Set<String> hot,
            double hotShare,
            String burstTicker,
            int burstFrames,
            long burstEveryNanos) {

        static Load of(final Map<String, String> options) {
            final String hot = options.getOrDefault("hot", "").trim();
            final String burstTicker = options.getOrDefault("burst-ticker", "").trim();
            return new Load(
                    Double.parseDouble(options.getOrDefault("rate", "1000")),
                    Double.parseDouble(options.getOrDefault("trades", "0.02")),
                    hot.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(hot.split("\\s*,\\s*"))),
                    Double.parseDouble(options.getOrDefault("hot-share", "0")),
                    burstTicker.isEmpty() ? null : burstTicker,
                    Integer.parseInt(options.getOrDefault("burst-frames", "0")),
                    Math.round(Double.parseDouble(options.getOrDefault("burst-every", "0")) * 1e9));
        }

        boolean bursts() {
            return burstTicker != null && burstFrames > 0 && burstEveryNanos > 0;
        }

        @Override
        public String toString() {
            return String.format(
                    Locale.ROOT,
                    "%.0f frames/s, %.0f%% trades, %d hot markets with %.0f%% of the rate, bursts %s",
                    rate,
                    trades * 100,
                    hot.size(),
                    hotShare * 100,
                    bursts()
                            ? burstFrames + " frames on " + burstTicker + " every " + burstEveryNanos / 1e9 + " s"
                            : "off");
        }
    }

    KalshiTestVenue(final double framesPerSecond, final double tradeShare) {
        this(new Load(framesPerSecond, tradeShare, Set.of(), 0, null, 0, 0));
    }

    KalshiTestVenue(final Load load) {
        this.load = load;
    }

    void load(final Load load) {
        this.load = load;
    }

    public static void main(final String[] args) throws Exception {
        final Map<String, String> options = options(args);
        final int port = Integer.parseInt(options.getOrDefault("port", "9000"));
        final KalshiTestVenue venue = new KalshiTestVenue(Load.of(options));
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            System.out.printf(Locale.ROOT, "Kalshi test venue on :%d: %s%n", port, venue.load);
            if (options.containsKey("control")) {
                venue.followControl(options.get("control"), options);
            }
            venue.reportEvery(TimeUnit.SECONDS.toMillis(10));
            venue.serve(server);
        }
    }

    /**
     * Reads {@code control} again every second, applying its options over {@code base}'s whenever its text changes. One
     * that can't be read or parsed is reported and the load kept.
     */
    private void followControl(final String control, final Map<String, String> base) {
        final HttpClient http = HttpClient.newHttpClient();
        final Thread follower = new Thread(
                () -> {
                    String seen = null;
                    while (true) {
                        try {
                            final String text = read(http, control);
                            if (text != null && !text.equals(seen)) {
                                seen = text;
                                final Map<String, String> options = new HashMap<>(base);
                                options.putAll(control(List.of(text.split("\\R"))));
                                load = Load.of(options);
                                System.out.printf(Locale.ROOT, "Load now: %s%n", load);
                            }
                        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
                            System.out.printf(Locale.ROOT, "Control %s not applied: %s%n", control, e);
                        } catch (InterruptedException e) {
                            return;
                        }
                        try {
                            Thread.sleep(1_000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                },
                "venue-control");
        follower.setDaemon(true);
        follower.start();
    }

    /** The control's text, or null while there is none. */
    private static String read(final HttpClient http, final String control) throws IOException, InterruptedException {
        if (control.startsWith("http://") || control.startsWith("https://")) {
            final HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(control))
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            return response.body();
        }
        final Path file = Path.of(control);
        return Files.exists(file) ? Files.readString(file) : null;
    }

    static Map<String, String> control(final List<String> lines) {
        final Map<String, String> options = new HashMap<>();
        for (String line : lines) {
            final String trimmed = line.trim();
            final int equals = trimmed.indexOf('=');
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && equals > 0) {
                options.put(
                        trimmed.substring(0, equals).trim(),
                        trimmed.substring(equals + 1).trim());
            }
        }
        return options;
    }

    /** Accepts connections until the socket closes, each on threads of its own. */
    void serve(final ServerSocket server) throws IOException {
        while (!server.isClosed()) {
            final Socket socket = server.accept();
            socket.setTcpNoDelay(true);
            final Thread thread = new Thread(() -> new Connection(socket).run(), "venue-conn");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void reportEvery(final long millis) {
        final Thread reporter = new Thread(
                () -> {
                    long last = 0;
                    while (true) {
                        try {
                            Thread.sleep(millis);
                        } catch (InterruptedException e) {
                            return;
                        }
                        final long now = frames.get();
                        System.out.printf(
                                Locale.ROOT,
                                "%d connections, %d markets, %.0f frames/s%n",
                                connections.get(),
                                markets.get(),
                                (now - last) * 1000.0 / millis);
                        last = now;
                    }
                },
                "venue-report");
        reporter.setDaemon(true);
        reporter.start();
    }

    private static final class Market {
        final String ticker;
        final String id = UUID.randomUUID().toString();
        final int mid;
        final long[] bids = new long[LEVELS];
        final long[] asks = new long[LEVELS];

        Market(final String ticker, final SplittableRandom random) {
            this.ticker = ticker;
            this.mid = 15 + random.nextInt(70);
            for (int i = 0; i < LEVELS; i++) {
                bids[i] = 100 + random.nextInt(5_000);
                asks[i] = 100 + random.nextInt(5_000);
            }
        }
    }

    /** One client: its reader takes commands, its writer (the only one touching the socket's output) sends. */
    private final class Connection {
        private final Socket socket;
        private final SplittableRandom random = new SplittableRandom();
        private final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<>();
        private final List<Market> subscribed = new ArrayList<>();
        private final Map<String, Market> byTicker = new HashMap<>();
        // The subscribed markets split by the load's hot set, redone when either changes.
        private final List<Market> hotMarkets = new ArrayList<>();
        private final List<Market> coldMarkets = new ArrayList<>();
        private Load classified;
        private boolean subscriptionsChanged = true;
        // Its hot markets, for the other connections to count them all.
        private volatile int hotCount;
        private long nextBurstNanos;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        private final StringBuilder text = new StringBuilder(4096);
        private volatile boolean open = true;
        private boolean sidsOpen;
        private long bookSeq = 1;
        private long tradeSeq = 1;

        Connection(final Socket socket) {
            this.socket = socket;
        }

        void run() {
            connections.incrementAndGet();
            live.add(this);
            try {
                final InputStream in = socket.getInputStream();
                final OutputStream output = socket.getOutputStream();
                handshake(in, output);
                final Thread reader = new Thread(() -> read(in), "venue-read");
                reader.setDaemon(true);
                reader.start();
                write(output);
            } catch (IOException e) {
                // The client went away; nothing to tell anyone.
            } finally {
                open = false;
                live.remove(this);
                markets.addAndGet(-subscribed.size());
                connections.decrementAndGet();
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
        }

        private void handshake(final InputStream in, final OutputStream output) throws IOException {
            final StringBuilder request = new StringBuilder();
            while (request.indexOf("\r\n\r\n") < 0) {
                final int b = in.read();
                if (b < 0) {
                    throw new IOException("closed during the upgrade");
                }
                request.append((char) b);
            }
            String key = null;
            for (String line : request.toString().split("\r\n")) {
                final int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Key")) {
                    key = line.substring(colon + 1).trim();
                }
            }
            if (key == null) {
                throw new IOException("no Sec-WebSocket-Key");
            }
            final String accept;
            try {
                accept = Base64.getEncoder()
                        .encodeToString(MessageDigest.getInstance("SHA-1")
                                .digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII)));
            } catch (Exception e) {
                throw new IOException(e);
            }
            output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        /** Client frames are masked; only text commands, pings and a close matter. */
        private void read(final InputStream in) {
            try {
                final ByteArrayOutputStream message = new ByteArrayOutputStream();
                while (open) {
                    final int first = in.read();
                    final int second = in.read();
                    if (first < 0 || second < 0) {
                        break;
                    }
                    final int opcode = first & 0x0F;
                    long length = second & 0x7F;
                    if (length == 126) {
                        length = (in.read() << 8) | in.read();
                    } else if (length == 127) {
                        length = 0;
                        for (int i = 0; i < 8; i++) {
                            length = (length << 8) | in.read();
                        }
                    }
                    final byte[] mask = (second & 0x80) != 0 ? in.readNBytes(4) : null;
                    final byte[] payload = in.readNBytes((int) length);
                    if (mask != null) {
                        for (int i = 0; i < payload.length; i++) {
                            payload[i] ^= mask[i & 3];
                        }
                    }
                    if (opcode == 0x8) {
                        break;
                    } else if (opcode == 0x9) {
                        commands.add("\u0000ping" + new String(payload, StandardCharsets.ISO_8859_1));
                    } else if (opcode == 0x1 || opcode == 0x0) {
                        message.write(payload);
                        if ((first & 0x80) != 0) {
                            commands.add(message.toString(StandardCharsets.UTF_8));
                            message.reset();
                        }
                    }
                }
            } catch (IOException e) {
                // gone
            }
            open = false;
        }

        private void write(final OutputStream output) throws IOException {
            long last = System.nanoTime();
            double owedHot = 0;
            double owedCold = 0;
            while (open) {
                LockSupport.parkNanos(TICK_NANOS);
                String command;
                while ((command = commands.poll()) != null) {
                    if (command.startsWith("\u0000ping")) {
                        frame(0xA, command.substring(5).getBytes(StandardCharsets.ISO_8859_1));
                    } else {
                        command(command);
                    }
                }
                final long now = System.nanoTime();
                final Load current = load;
                classify(current);
                final int total = markets.get();
                if (!subscribed.isEmpty() && total > 0) {
                    final int hotTotal = liveHotMarkets();
                    double hotWeight = 1.0 / total;
                    double coldWeight = 1.0 / total;
                    if (hotTotal > 0 && hotTotal < total && current.hotShare() > 0) {
                        hotWeight = current.hotShare() / hotTotal;
                        coldWeight = (1 - current.hotShare()) / (total - hotTotal);
                    }
                    final double seconds = (now - last) / 1e9;
                    owedHot += current.rate() * hotMarkets.size() * hotWeight * seconds;
                    owedCold += current.rate() * coldMarkets.size() * coldWeight * seconds;
                    final long nowMs = System.currentTimeMillis();
                    owedHot = emit(hotMarkets, owedHot, current, nowMs);
                    owedCold = emit(coldMarkets, owedCold, current, nowMs);
                    burst(current, now, nowMs);
                } else {
                    owedHot = 0;
                    owedCold = 0;
                }
                last = now;
                if (out.size() > 0) {
                    out.writeTo(output);
                    output.flush();
                    out.reset();
                }
            }
        }

        /** Sends the whole frames owed, spread evenly over {@code pool}; returns what is still owed. */
        private double emit(final List<Market> pool, final double owed, final Load current, final long nowMs) {
            if (pool.isEmpty()) {
                return 0;
            }
            double left = owed;
            while (left >= 1) {
                left -= 1;
                final Market market = pool.get(random.nextInt(pool.size()));
                if (random.nextDouble() < current.trades()) {
                    trade(market, nowMs);
                } else {
                    delta(market, nowMs);
                }
            }
            return left;
        }

        private void burst(final Load current, final long now, final long nowMs) {
            final Market market = current.bursts() ? byTicker.get(current.burstTicker()) : null;
            if (market == null) {
                nextBurstNanos = 0;
                return;
            }
            if (nextBurstNanos == 0) {
                nextBurstNanos = now + current.burstEveryNanos();
            } else if (now >= nextBurstNanos) {
                nextBurstNanos = now + current.burstEveryNanos();
                for (int i = 0; i < current.burstFrames(); i++) {
                    delta(market, nowMs);
                }
            }
        }

        private void classify(final Load current) {
            if (current == classified && !subscriptionsChanged) {
                return;
            }
            classified = current;
            subscriptionsChanged = false;
            hotMarkets.clear();
            coldMarkets.clear();
            for (Market market : subscribed) {
                (current.hot().contains(market.ticker) ? hotMarkets : coldMarkets).add(market);
            }
            hotCount = hotMarkets.size();
        }

        private void command(final String json) {
            final Matcher id = ID.matcher(json);
            final Matcher cmd = CMD.matcher(json);
            if (!id.find() || !cmd.find()) {
                return;
            }
            final long commandId = Long.parseLong(id.group(1));
            final List<String> tickers = tickers(json);
            final long nowMs = System.currentTimeMillis();
            if (cmd.group(1).equals("subscribe")) {
                if (!sidsOpen) {
                    sidsOpen = true;
                    text("{\"id\":" + commandId
                            + ",\"type\":\"subscribed\",\"msg\":{\"channel\":\"orderbook_delta\",\"sid\":" + BOOK_SID
                            + "}}");
                    text("{\"id\":" + commandId + ",\"type\":\"subscribed\",\"msg\":{\"channel\":\"trade\",\"sid\":"
                            + TRADE_SID + "}}");
                }
                for (String ticker : tickers) {
                    Market market = byTicker.get(ticker);
                    if (market == null) {
                        market = new Market(ticker, random);
                        byTicker.put(ticker, market);
                        subscribed.add(market);
                        subscriptionsChanged = true;
                        markets.incrementAndGet();
                    }
                    snapshot(market, nowMs);
                }
                return;
            }
            final Matcher action = ACTION.matcher(json);
            if (!action.find()) {
                return;
            }
            if (action.group(1).equals("get_snapshot")) {
                for (String ticker : tickers) {
                    final Market market = byTicker.get(ticker);
                    if (market != null) {
                        snapshot(market, nowMs);
                    }
                }
            } else if (action.group(1).equals("delete_markets")) {
                final boolean book = json.contains("[" + BOOK_SID + "]");
                if (book) {
                    for (String ticker : tickers) {
                        final Market market = byTicker.remove(ticker);
                        if (market != null) {
                            subscribed.remove(market);
                            subscriptionsChanged = true;
                            markets.decrementAndGet();
                        }
                    }
                }
                final int sid = book ? BOOK_SID : TRADE_SID;
                text("{\"id\":" + commandId + ",\"type\":\"ok\",\"sid\":" + sid + ",\"seq\":" + nextSeq(sid)
                        + ",\"msg\":{}}");
            }
        }

        private long nextSeq(final int sid) {
            return sid == BOOK_SID ? bookSeq++ : tradeSeq++;
        }

        private void snapshot(final Market market, final long nowMs) {
            text.setLength(0);
            text.append("{\"type\":\"orderbook_snapshot\",\"sid\":")
                    .append(BOOK_SID)
                    .append(",\"seq\":")
                    .append(nextSeq(BOOK_SID))
                    .append(",\"msg\":{\"market_ticker\":\"")
                    .append(market.ticker)
                    .append("\",\"market_id\":\"")
                    .append(market.id)
                    .append("\",\"yes_dollars_fp\":[");
            levels(market.bids, market.mid, -1);
            text.append("],\"no_dollars_fp\":[");
            levels(market.asks, market.mid, 1);
            text.append("]},\"sending_ts_ms\":").append(nowMs).append('}');
            send();
        }

        private void levels(final long[] sizes, final int mid, final int direction) {
            boolean first = true;
            for (int i = 0; i < LEVELS; i++) {
                if (sizes[i] <= 0) {
                    continue;
                }
                if (!first) {
                    text.append(',');
                }
                first = false;
                text.append("[\"");
                price(mid + direction * (i + 1));
                text.append("\",\"").append(sizes[i]).append(".00\"]");
            }
        }

        private void delta(final Market market, final long nowMs) {
            final boolean yes = random.nextBoolean();
            final int level = random.nextInt(LEVELS);
            final long[] sizes = yes ? market.bids : market.asks;
            long change = 1 + random.nextInt(200);
            if (random.nextBoolean() && sizes[level] > 0) {
                change = -Math.min(change, sizes[level]);
            }
            sizes[level] += change;
            text.setLength(0);
            text.append("{\"type\":\"orderbook_delta\",\"sid\":")
                    .append(BOOK_SID)
                    .append(",\"seq\":")
                    .append(nextSeq(BOOK_SID))
                    .append(",\"msg\":{\"market_ticker\":\"")
                    .append(market.ticker)
                    .append("\",\"market_id\":\"")
                    .append(market.id)
                    .append("\",\"clock\":")
                    .append(nowMs * 70)
                    .append(",\"price_dollars\":\"");
            price(market.mid + (yes ? -1 : 1) * (level + 1));
            text.append("\",\"delta_fp\":\"")
                    .append(change)
                    .append(".00\",\"side\":\"")
                    .append(yes ? "yes" : "no")
                    .append("\",\"ts\":\"2026-11-15T18:02:59.992Z\",\"ts_ms\":")
                    .append(nowMs)
                    .append("},\"sending_ts_ms\":")
                    .append(nowMs)
                    .append('}');
            send();
        }

        private void trade(final Market market, final long nowMs) {
            final boolean bid = random.nextBoolean();
            final int cents = market.mid + (bid ? -1 : 1);
            text.setLength(0);
            text.append("{\"type\":\"trade\",\"sid\":")
                    .append(TRADE_SID)
                    .append(",\"seq\":")
                    .append(nextSeq(TRADE_SID))
                    .append(",\"msg\":{\"trade_id\":\"072661e2-9909-b724-18f9-41f09580f8f2\",\"market_ticker\":\"")
                    .append(market.ticker)
                    .append("\",\"yes_price_dollars\":\"");
            price(cents);
            text.append("\",\"no_price_dollars\":\"");
            price(100 - cents);
            text.append("\",\"count_fp\":\"")
                    .append(1 + random.nextInt(50))
                    .append(".00\",\"taker_side\":\"yes\",\"taker_outcome_side\":\"yes\",\"taker_book_side\":\"")
                    .append(bid ? "bid" : "ask")
                    .append("\",\"is_block_trade\":false,\"ts\":")
                    .append(nowMs / 1000)
                    .append(",\"ts_ms\":")
                    .append(nowMs)
                    .append("},\"sending_ts_ms\":")
                    .append(nowMs)
                    .append('}');
            send();
        }

        private void price(final int cents) {
            final int clamped = Math.max(1, Math.min(99, cents));
            text.append("0.").append(clamped < 10 ? "0" : "").append(clamped).append("00");
        }

        private void text(final String json) {
            text.setLength(0);
            text.append(json);
            send();
        }

        private void send() {
            frame(0x1, text.toString().getBytes(StandardCharsets.UTF_8));
            frames.incrementAndGet();
        }

        private void frame(final int opcode, final byte[] payload) {
            out.write(0x80 | opcode);
            if (payload.length < 126) {
                out.write(payload.length);
            } else if (payload.length < 65536) {
                out.write(126);
                out.write(payload.length >>> 8);
                out.write(payload.length & 0xFF);
            } else {
                out.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) ((long) payload.length >>> shift) & 0xFF);
                }
            }
            out.write(payload, 0, payload.length);
        }
    }

    private int liveHotMarkets() {
        int hot = 0;
        for (Connection connection : live) {
            hot += connection.hotCount;
        }
        return hot;
    }

    private static List<String> tickers(final String json) {
        final List<String> tickers = new ArrayList<>();
        final Matcher list = TICKERS.matcher(json);
        if (list.find()) {
            final Matcher quoted = QUOTED.matcher(list.group(1));
            while (quoted.find()) {
                tickers.add(quoted.group(1));
            }
        }
        return tickers;
    }

    private static Map<String, String> options(final String[] args) {
        final Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                options.put(arg.substring(2, arg.indexOf('=')), arg.substring(arg.indexOf('=') + 1));
            }
        }
        return options;
    }
}
