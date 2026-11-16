package group.gnometrading.orchestrator.shard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
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
 * --rate=5000 --trades=0.02}, where {@code rate} is frames a second over all connections and {@code trades} the share
 * of them that are trades. The shard points {@code kalshi.ws.url} at {@code ws://<host>:<port>/trade-api/ws/v2}.
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

    private final double framesPerSecond;
    private final double tradeShare;
    private final AtomicInteger markets = new AtomicInteger();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicLong frames = new AtomicLong();

    KalshiTestVenue(final double framesPerSecond, final double tradeShare) {
        this.framesPerSecond = framesPerSecond;
        this.tradeShare = tradeShare;
    }

    public static void main(final String[] args) throws Exception {
        final Map<String, String> options = options(args);
        final int port = Integer.parseInt(options.getOrDefault("port", "9000"));
        final KalshiTestVenue venue = new KalshiTestVenue(
                Double.parseDouble(options.getOrDefault("rate", "1000")),
                Double.parseDouble(options.getOrDefault("trades", "0.02")));
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            System.out.printf(Locale.ROOT, "Kalshi test venue on :%d at %.0f frames/s%n", port, venue.framesPerSecond);
            venue.reportEvery(TimeUnit.SECONDS.toMillis(10));
            venue.serve(server);
        }
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
            double owed = 0;
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
                final int total = markets.get();
                if (!subscribed.isEmpty() && total > 0) {
                    owed += framesPerSecond * subscribed.size() / total * (now - last) / 1e9;
                    final long nowMs = System.currentTimeMillis();
                    while (owed >= 1) {
                        owed -= 1;
                        final Market market = subscribed.get(random.nextInt(subscribed.size()));
                        if (random.nextDouble() < tradeShare) {
                            trade(market, nowMs);
                        } else {
                            delta(market, nowMs);
                        }
                    }
                } else {
                    owed = 0;
                }
                last = now;
                if (out.size() > 0) {
                    out.writeTo(output);
                    output.flush();
                    out.reset();
                }
            }
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
