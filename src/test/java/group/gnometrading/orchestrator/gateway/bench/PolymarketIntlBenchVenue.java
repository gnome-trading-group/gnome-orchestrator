package group.gnometrading.orchestrator.gateway.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlOrderSigner;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polymarket International's CLOB as the bench needs it: the gateway's real order bodies (EIP-712 signed, L2 HMAC
 * headers) are answered as the venue answers them, and the {@code user} websocket carries each order's PLACEMENT,
 * CANCELLATION and trades. Signatures are never checked; the order hash is recomputed from the body, as the venue's
 * {@code orderID} is, because every event names an order by it.
 *
 * <p>Timing, as seen live: each answer after {@code answerNanos}; each event {@code eventLagNanos} after its answer,
 * one in {@code lagOneIn} late by {@code lagNanos} more (the websocket's stalls). A cancel that reaches the venue before
 * its order is refused "can't be found", and the order, when it comes, is refused "Already canceled". One resting
 * order in {@code fillOneIn} is filled in full, {@code fillAfterNanos} after it rests.
 */
public final class PolymarketIntlBenchVenue implements BenchVenue {

    /** How the venue answers. */
    public record Timing(
            long answerNanos, long eventLagNanos, int lagOneIn, long lagNanos, int fillOneIn, long fillAfterNanos) {

        public static Timing live() {
            return new Timing(
                    TimeUnit.MILLISECONDS.toNanos(25),
                    TimeUnit.MILLISECONDS.toNanos(2),
                    30,
                    TimeUnit.MILLISECONDS.toNanos(150),
                    20,
                    TimeUnit.MILLISECONDS.toNanos(200));
        }

        /**
         * This timing with any of {@code bench.venue.answer.us}, {@code bench.venue.lag.us} and {@code
         * bench.venue.lag.one.in} set over it: answers and events at once ({@code 0}) leave only the gateway and the
         * network in a round trip, so its microseconds show.
         */
        public Timing overridden() {
            return new Timing(
                    micros("bench.venue.answer.us", this.answerNanos),
                    micros("bench.venue.lag.us", this.eventLagNanos),
                    Integer.getInteger("bench.venue.lag.one.in", this.lagOneIn),
                    this.lagNanos,
                    this.fillOneIn,
                    this.fillAfterNanos);
        }

        private static long micros(final String property, final long nanos) {
            final Long value = Long.getLong(property);
            return value == null ? nanos : TimeUnit.MICROSECONDS.toNanos(value);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] ORDER_TYPE_HASH = PolymarketIntlOrderSigner.keccak256(
            ("Order(uint256 salt,address maker,address signer,uint256 tokenId,uint256 makerAmount,"
                            + "uint256 takerAmount,uint8 side,uint8 signatureType,uint256 timestamp,"
                            + "bytes32 metadata,bytes32 builder)")
                    .getBytes(StandardCharsets.UTF_8));
    private static final byte[] STANDARD_DOMAIN = domainSeparator(PolymarketIntlOrderSigner.STANDARD_EXCHANGE);

    /** An order the venue holds. */
    private record Order(String hash, String tokenId, String side, String price, String size) {}

    private final Timing timing;
    // By condition id: each bench market's one traded token, so no two listings price each other's complement.
    private final Map<String, String> tokenByCondition;
    private final String owner;
    private final ScheduledExecutorService events = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "bench-pmintl-events");
        thread.setDaemon(true);
        return thread;
    });
    private final List<TlsVenueServer.Socket> sockets = new CopyOnWriteArrayList<>();
    private final Map<String, Order> open = new ConcurrentHashMap<>();
    // Cancels that arrived before their order: the order, when it comes, is refused.
    private final Set<String> canceledBeforeArrival = ConcurrentHashMap.newKeySet();
    private final AtomicLong orders = new AtomicLong();
    private final AtomicLong cancels = new AtomicLong();
    private final AtomicLong refusedAsCanceled = new AtomicLong();
    private final AtomicLong fills = new AtomicLong();
    private final AtomicLong heartbeats = new AtomicLong();
    private final AtomicLong trades = new AtomicLong();
    private final Map<String, AtomicLong> unknown = new ConcurrentHashMap<>();

    /** @param tokenByCondition each market's condition id and the outcome token traded on it, in decimal */
    public PolymarketIntlBenchVenue(
            final Timing timing, final Map<String, String> tokenByCondition, final String owner) {
        this.timing = timing;
        this.tokenByCondition = tokenByCondition;
        this.owner = owner;
    }

    @Override
    public TlsVenueServer.Reply onRequest(final TlsVenueServer.Request request) {
        final String path = request.path();
        final int query = path.indexOf('?');
        final String route = query < 0 ? path : path.substring(0, query);
        try {
            if (request.method().equals("POST") && route.equals("/order")) {
                return submit(request.body());
            }
            if (request.method().equals("DELETE") && route.equals("/order")) {
                return cancel(request.body());
            }
            if (route.equals("/v1/heartbeats")) {
                return TlsVenueServer.Reply.ok(
                        "{\"heartbeat_id\":\"hb-" + this.heartbeats.incrementAndGet() + "\"}", this.timing.answerNanos);
            }
            if (route.equals("/version")) {
                return TlsVenueServer.Reply.ok("{\"version\":2}", 0);
            }
            if (route.startsWith("/clob-markets/")) {
                return TlsVenueServer.Reply.ok(marketInfo(route.substring("/clob-markets/".length())), 0);
            }
            if (route.equals("/data/orders")) {
                return TlsVenueServer.Reply.ok(openOrders(), this.timing.answerNanos);
            }
            if (route.equals("/data/trades")) {
                return TlsVenueServer.Reply.ok("{\"data\":[],\"next_cursor\":\"LTE=\"}", this.timing.answerNanos);
            }
            if (route.startsWith("/data/order/")) {
                return lookup(route.substring("/data/order/".length()));
            }
        } catch (Exception e) {
            return new TlsVenueServer.Reply(500, "{\"error\":\"" + e + "\"}", 0, null);
        }
        this.unknown
                .computeIfAbsent(request.method() + " " + route, k -> new AtomicLong())
                .incrementAndGet();
        return new TlsVenueServer.Reply(404, "{\"error\":\"not found\"}", 0, null);
    }

    private TlsVenueServer.Reply submit(final String body) throws Exception {
        this.orders.incrementAndGet();
        final JsonNode order = MAPPER.readTree(body).get("order");
        final String hash = orderHash(order);
        if (this.canceledBeforeArrival.remove(hash)) {
            this.refusedAsCanceled.incrementAndGet();
            return new TlsVenueServer.Reply(
                    400,
                    "{\"error\":\"order " + hash + " is invalid. Already canceled.\"}",
                    this.timing.answerNanos,
                    null);
        }
        final boolean buy = order.get("side").asText().equals("BUY");
        final long makerAmount = order.get("makerAmount").asLong();
        final long takerAmount = order.get("takerAmount").asLong();
        final long shares = buy ? takerAmount : makerAmount;
        final long dollars = buy ? makerAmount : takerAmount;
        final Order held = new Order(
                hash,
                order.get("tokenId").asText(),
                buy ? "BUY" : "SELL",
                decimal(BigInteger.valueOf(dollars)
                        .multiply(BigInteger.valueOf(1_000_000))
                        .divide(BigInteger.valueOf(shares))
                        .longValue()),
                decimal(shares));
        this.open.put(hash, held);
        final String answer = "{\"errorMsg\":\"\",\"orderID\":\"" + hash
                + "\",\"takingAmount\":\"\",\"makingAmount\":\"\",\"status\":\"live\",\"success\":true}";
        return TlsVenueServer.Reply.ok(answer, this.timing.answerNanos).then(() -> afterPlaced(held));
    }

    private void afterPlaced(final Order order) {
        later(eventLag(), () -> push(orderEvent(order, "PLACEMENT", "LIVE", "0")));
        final int fillOneIn = this.timing.fillOneIn;
        if (fillOneIn > 0 && ThreadLocalRandom.current().nextInt(fillOneIn) == 0) {
            later(this.timing.fillAfterNanos, () -> {
                if (this.open.remove(order.hash()) != null) {
                    this.fills.incrementAndGet();
                    push(trade(order));
                    push(orderEvent(order, "UPDATE", "MATCHED", order.size()));
                }
            });
        }
    }

    private TlsVenueServer.Reply cancel(final String body) throws Exception {
        this.cancels.incrementAndGet();
        final String hash = MAPPER.readTree(body).get("orderID").asText();
        final Order order = this.open.remove(hash);
        if (order == null) {
            this.canceledBeforeArrival.add(hash);
            return TlsVenueServer.Reply.ok(
                    "{\"canceled\":[],\"not_canceled\":{\"" + hash
                            + "\":\"order can't be found - already canceled or matched\"}}",
                    this.timing.answerNanos);
        }
        return TlsVenueServer.Reply.ok("{\"canceled\":[\"" + hash + "\"],\"not_canceled\":{}}", this.timing.answerNanos)
                .then(() -> later(eventLag(), () -> push(orderEvent(order, "CANCELLATION", "CANCELED", "0"))));
    }

    private TlsVenueServer.Reply lookup(final String hash) {
        final Order order = this.open.get(hash);
        if (order == null) {
            return TlsVenueServer.Reply.ok("null", this.timing.answerNanos);
        }
        return TlsVenueServer.Reply.ok(restOrder(order), this.timing.answerNanos);
    }

    private String openOrders() {
        final StringBuilder data = new StringBuilder("{\"data\":[");
        boolean first = true;
        for (final Order order : this.open.values()) {
            if (!first) {
                data.append(',');
            }
            data.append(restOrder(order));
            first = false;
        }
        return data.append("],\"next_cursor\":\"LTE=\"}").toString();
    }

    /** The market's traded token, then a complement nothing trades. */
    private String marketInfo(final String conditionId) {
        return "{\"mts\":0.01,\"mos\":5,\"nr\":false,\"itode\":false,\"oas\":0,\"t\":[{\"t\":\""
                + this.tokenByCondition.get(conditionId) + "\"},{\"t\":\"1\"}]}";
    }

    private String orderEvent(final Order order, final String type, final String status, final String matched) {
        return "{\"asset_id\":\"" + order.tokenId() + "\",\"associate_trades\":null,\"event_type\":\"order\",\"id\":\""
                + order.hash() + "\",\"market\":\"" + conditionOf(order) + "\",\"original_size\":\"" + order.size()
                + "\",\"outcome\":\"YES\",\"owner\":\"" + this.owner + "\",\"price\":\"" + order.price()
                + "\",\"side\":\"" + order.side() + "\",\"size_matched\":\"" + matched + "\",\"status\":\"" + status
                + "\",\"timestamp\":\"" + System.currentTimeMillis() + "\",\"type\":\"" + type + "\"}";
    }

    /** Our resting order filled in full by someone else's taker order. */
    private String trade(final Order order) {
        final String takerSide = order.side().equals("BUY") ? "SELL" : "BUY";
        return "{\"asset_id\":\"" + order.tokenId()
                + "\",\"event_type\":\"trade\",\"fee_rate_bps\":\"0\",\"id\":\"trade-"
                + this.trades.incrementAndGet() + "\",\"maker_orders\":[{\"order_id\":\"" + order.hash()
                + "\",\"owner\":\"" + this.owner + "\",\"maker_address\":\"0xabc\",\"matched_amount\":\""
                + order.size() + "\",\"price\":\"" + order.price() + "\",\"fee_rate_bps\":\"0\",\"asset_id\":\""
                + order.tokenId() + "\",\"outcome\":\"YES\",\"side\":\"" + order.side() + "\"}],\"market\":\""
                + conditionOf(order) + "\",\"outcome\":\"YES\",\"owner\":\"other\",\"price\":\"" + order.price()
                + "\",\"side\":\"" + takerSide + "\",\"size\":\"" + order.size() + "\",\"status\":\"MATCHED\","
                + "\"taker_order_id\":\"0xtaker" + this.trades.get() + "\",\"timestamp\":\""
                + System.currentTimeMillis() + "\",\"trader_side\":\"MAKER\",\"type\":\"TRADE\"}";
    }

    private String restOrder(final Order order) {
        return "{\"id\":\"" + order.hash() + "\",\"status\":\"LIVE\",\"owner\":\"" + this.owner
                + "\",\"maker_address\":\"0xabc\",\"market\":\"" + conditionOf(order) + "\",\"asset_id\":\""
                + order.tokenId() + "\",\"side\":\"" + order.side() + "\",\"original_size\":\"" + order.size()
                + "\",\"size_matched\":\"0\",\"price\":\"" + order.price() + "\",\"outcome\":\"YES\",\"created_at\":"
                + System.currentTimeMillis() / 1000 + ",\"order_type\":\"GTC\"}";
    }

    private String conditionOf(final Order order) {
        for (final Map.Entry<String, String> market : this.tokenByCondition.entrySet()) {
            if (market.getValue().equals(order.tokenId())) {
                return market.getKey();
            }
        }
        return "";
    }

    @Override
    public void onSocketOpen(final TlsVenueServer.Socket socket) {
        this.sockets.add(socket);
    }

    @Override
    public void onSocketText(final TlsVenueServer.Socket socket, final String text) {
        if (text.equals("PING")) {
            socket.sendText("PONG");
        }
    }

    @Override
    public void onSocketClosed(final TlsVenueServer.Socket socket) {
        this.sockets.remove(socket);
    }

    private void push(final String event) {
        for (final TlsVenueServer.Socket socket : this.sockets) {
            socket.sendText(event);
        }
    }

    private long eventLag() {
        final int oneIn = this.timing.lagOneIn;
        final boolean late = oneIn > 0 && ThreadLocalRandom.current().nextInt(oneIn) == 0;
        return this.timing.eventLagNanos + (late ? this.timing.lagNanos : 0);
    }

    private void later(final long delayNanos, final Runnable action) {
        this.events.schedule(action, delayNanos, TimeUnit.NANOSECONDS);
    }

    /** What the venue saw, for the bench's report. */
    @Override
    public Map<String, Long> counts() {
        final Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("orders", this.orders.get());
        counts.put("cancels", this.cancels.get());
        counts.put("refusedAsCanceled", this.refusedAsCanceled.get());
        counts.put("fills", this.fills.get());
        counts.put("heartbeats", this.heartbeats.get());
        counts.put("open", (long) this.open.size());
        this.unknown.forEach((route, count) -> counts.put("unknown " + route, count.get()));
        return counts;
    }

    @Override
    public void close() {
        this.events.shutdownNow();
    }

    /** The order's EIP-712 hash, as the venue's {@code orderID}, from the fields its body carries. */
    static String orderHash(final JsonNode order) {
        final byte[] struct = new byte[12 * 32];
        System.arraycopy(ORDER_TYPE_HASH, 0, struct, 0, 32);
        uint(new BigInteger(order.get("salt").asText()), struct, 1);
        address(order.get("maker").asText(), struct, 2);
        address(order.get("signer").asText(), struct, 3);
        uint(new BigInteger(order.get("tokenId").asText()), struct, 4);
        uint(new BigInteger(order.get("makerAmount").asText()), struct, 5);
        uint(new BigInteger(order.get("takerAmount").asText()), struct, 6);
        uint(BigInteger.valueOf(order.get("side").asText().equals("BUY") ? 0 : 1), struct, 7);
        uint(new BigInteger(order.get("signatureType").asText()), struct, 8);
        uint(new BigInteger(order.get("timestamp").asText()), struct, 9);
        bytes32(order.get("metadata").asText(), struct, 10);
        bytes32(order.get("builder").asText(), struct, 11);
        final byte[] message = new byte[66];
        message[0] = 0x19;
        message[1] = 0x01;
        System.arraycopy(STANDARD_DOMAIN, 0, message, 2, 32);
        System.arraycopy(PolymarketIntlOrderSigner.keccak256(struct), 0, message, 34, 32);
        return "0x" + HexFormat.of().formatHex(PolymarketIntlOrderSigner.keccak256(message));
    }

    private static byte[] domainSeparator(final String verifyingContract) {
        final byte[] encoded = new byte[5 * 32];
        System.arraycopy(
                PolymarketIntlOrderSigner.keccak256(
                        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
                                .getBytes(StandardCharsets.UTF_8)),
                0,
                encoded,
                0,
                32);
        System.arraycopy(
                PolymarketIntlOrderSigner.keccak256("Polymarket CTF Exchange".getBytes(StandardCharsets.UTF_8)),
                0,
                encoded,
                32,
                32);
        System.arraycopy(PolymarketIntlOrderSigner.keccak256("2".getBytes(StandardCharsets.UTF_8)), 0, encoded, 64, 32);
        uint(BigInteger.valueOf(137), encoded, 3);
        address(verifyingContract, encoded, 4);
        return PolymarketIntlOrderSigner.keccak256(encoded);
    }

    private static void uint(final BigInteger value, final byte[] dest, final int slot) {
        final byte[] raw = value.toByteArray();
        final int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, dest, slot * 32 + 32 - length, length);
    }

    private static void address(final String hex, final byte[] dest, final int slot) {
        System.arraycopy(HexFormat.of().parseHex(hex.substring(2)), 0, dest, slot * 32 + 12, 20);
    }

    private static void bytes32(final String hex, final byte[] dest, final int slot) {
        System.arraycopy(HexFormat.of().parseHex(hex.substring(2)), 0, dest, slot * 32, 32);
    }

    /** A 6-decimal amount as the venue writes it. */
    private static String decimal(final long micros) {
        return BigInteger.valueOf(micros).divide(BigInteger.valueOf(1_000_000)) + "."
                + String.format("%06d", micros % 1_000_000);
    }
}
