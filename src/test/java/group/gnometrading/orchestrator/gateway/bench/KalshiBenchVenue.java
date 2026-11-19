package group.gnometrading.orchestrator.gateway.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kalshi's trade API as the bench needs it: the gateway's real Ed25519-signed creates, cancels and amends answered as
 * Kalshi answers them (the venue's {@code order_id} in each), and the {@code user_orders} channel carrying each order's
 * {@code user_order} updates. Signatures are never checked. Like Kalshi, it pings the socket every 10 s.
 *
 * <p>Timing as {@link PolymarketIntlBenchVenue.Timing}: each answer after {@code answerNanos}, each update
 * {@code eventLagNanos} after it, one in {@code lagOneIn} later by {@code lagNanos}, and one resting order in
 * {@code fillOneIn} filled in full {@code fillAfterNanos} after it rests.
 */
public final class KalshiBenchVenue implements BenchVenue {

    /** Kalshi's answers from us-east-2 to a gateway there: a few milliseconds. */
    public static PolymarketIntlBenchVenue.Timing liveTiming() {
        return new PolymarketIntlBenchVenue.Timing(
                TimeUnit.MILLISECONDS.toNanos(6),
                TimeUnit.MILLISECONDS.toNanos(2),
                50,
                TimeUnit.MILLISECONDS.toNanos(100),
                20,
                TimeUnit.MILLISECONDS.toNanos(200));
    }

    private static final String ORDERS = "/trade-api/v2/portfolio/events/orders";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PING_SECONDS = 10;

    private record Order(
            String orderId, String clientOrderId, String ticker, String side, BigDecimal price, BigDecimal count) {}

    private final PolymarketIntlBenchVenue.Timing timing;
    private final ScheduledExecutorService events = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "bench-kalshi-events");
        thread.setDaemon(true);
        return thread;
    });
    private final List<TlsVenueServer.Socket> sockets = new CopyOnWriteArrayList<>();
    private final Map<String, Order> open = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicLong orders = new AtomicLong();
    private final AtomicLong cancels = new AtomicLong();
    private final AtomicLong amends = new AtomicLong();
    private final AtomicLong notFound = new AtomicLong();
    private final AtomicLong fills = new AtomicLong();
    private final Map<String, AtomicLong> unknown = new ConcurrentHashMap<>();

    public KalshiBenchVenue(final PolymarketIntlBenchVenue.Timing timing) {
        this.timing = timing;
        this.events.scheduleAtFixedRate(this::ping, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public TlsVenueServer.Reply onRequest(final TlsVenueServer.Request request) {
        final String path = request.path();
        final int query = path.indexOf('?');
        final String route = query < 0 ? path : path.substring(0, query);
        try {
            if (request.method().equals("POST") && route.equals(ORDERS)) {
                return create(request.body());
            }
            if (request.method().equals("DELETE") && route.startsWith(ORDERS + "/")) {
                return cancel(route.substring(ORDERS.length() + 1));
            }
            if (request.method().equals("POST") && route.startsWith(ORDERS + "/") && route.endsWith("/amend")) {
                return amend(route.substring(ORDERS.length() + 1, route.length() - "/amend".length()), request.body());
            }
            if (route.startsWith("/trade-api/v2/markets/")) {
                return TlsVenueServer.Reply.ok(
                        "{\"market\":{\"ticker\":\"" + route.substring("/trade-api/v2/markets/".length())
                                + "\",\"status\":\"active\",\"exchange_index\":0}}",
                        0);
            }
            if (route.equals("/trade-api/v2/exchange/status")) {
                return TlsVenueServer.Reply.ok("{\"exchange_active\":true,\"trading_active\":true}", 0);
            }
            if (route.equals("/trade-api/v2/portfolio/orders")) {
                return TlsVenueServer.Reply.ok("{\"orders\":[],\"cursor\":\"\"}", this.timing.answerNanos());
            }
            if (route.equals("/trade-api/v2/portfolio/balance")) {
                return TlsVenueServer.Reply.ok("{\"balance\":1000000}", this.timing.answerNanos());
            }
        } catch (Exception e) {
            return new TlsVenueServer.Reply(500, "{\"error\":\"" + e + "\"}", 0, null);
        }
        this.unknown
                .computeIfAbsent(request.method() + " " + route, k -> new AtomicLong())
                .incrementAndGet();
        return new TlsVenueServer.Reply(404, "{\"error\":{\"code\":\"not_found\"}}", 0, null);
    }

    private TlsVenueServer.Reply create(final String body) throws Exception {
        this.orders.incrementAndGet();
        final JsonNode order = MAPPER.readTree(body);
        final Order held = new Order(
                "bench-" + this.ids.incrementAndGet(),
                order.get("client_order_id").asText(),
                order.get("ticker").asText(),
                order.get("side").asText(),
                new BigDecimal(order.get("price").asText()),
                new BigDecimal(order.get("count").asText()));
        this.open.put(held.orderId(), held);
        return new TlsVenueServer.Reply(
                        201,
                        "{\"order\":" + orderJson(held, "resting", BigDecimal.ZERO) + ",\"ts_ms\":"
                                + System.currentTimeMillis() + "}",
                        this.timing.answerNanos(),
                        null)
                .then(() -> afterPlaced(held));
    }

    private void afterPlaced(final Order order) {
        later(eventLag(), () -> push(update(order, "resting", BigDecimal.ZERO)));
        final int fillOneIn = this.timing.fillOneIn();
        if (fillOneIn > 0 && ThreadLocalRandom.current().nextInt(fillOneIn) == 0) {
            later(this.timing.fillAfterNanos(), () -> {
                if (this.open.remove(order.orderId()) != null) {
                    this.fills.incrementAndGet();
                    push(update(order, "executed", order.count()));
                }
            });
        }
    }

    private TlsVenueServer.Reply cancel(final String orderId) {
        this.cancels.incrementAndGet();
        final Order order = this.open.remove(orderId);
        if (order == null) {
            this.notFound.incrementAndGet();
            return new TlsVenueServer.Reply(
                    404,
                    "{\"error\":{\"code\":\"not_found\",\"message\":\"order not found\"}}",
                    this.timing.answerNanos(),
                    null);
        }
        return TlsVenueServer.Reply.ok(
                        "{\"order\":" + orderJson(order, "canceled", BigDecimal.ZERO) + ",\"reduced_by_fp\":\""
                                + order.count() + "\",\"ts_ms\":" + System.currentTimeMillis() + "}",
                        this.timing.answerNanos())
                .then(() -> later(eventLag(), () -> push(update(order, "canceled", BigDecimal.ZERO))));
    }

    private TlsVenueServer.Reply amend(final String orderId, final String body) throws Exception {
        this.amends.incrementAndGet();
        final Order order = this.open.get(orderId);
        if (order == null) {
            return new TlsVenueServer.Reply(
                    404, "{\"error\":{\"code\":\"not_found\"}}", this.timing.answerNanos(), null);
        }
        final JsonNode amend = MAPPER.readTree(body);
        final Order amended = new Order(
                order.orderId(),
                order.clientOrderId(),
                order.ticker(),
                order.side(),
                new BigDecimal(amend.get("price").asText()),
                new BigDecimal(amend.get("count").asText()));
        this.open.put(orderId, amended);
        return TlsVenueServer.Reply.ok(
                        "{\"old_order\":" + orderJson(order, "resting", BigDecimal.ZERO) + ",\"order\":"
                                + orderJson(amended, "resting", BigDecimal.ZERO) + ",\"remaining_count\":\""
                                + amended.count().setScale(2) + "\",\"ts_ms\":" + System.currentTimeMillis() + "}",
                        this.timing.answerNanos())
                .then(() -> later(eventLag(), () -> push(update(amended, "resting", BigDecimal.ZERO))));
    }

    private String update(final Order order, final String status, final BigDecimal filled) {
        return "{\"type\":\"user_order\",\"sid\":1,\"msg\":" + orderJson(order, status, filled) + "}";
    }

    private static String orderJson(final Order order, final String status, final BigDecimal filled) {
        final BigDecimal remaining = status.equals("resting") ? order.count().subtract(filled) : BigDecimal.ZERO;
        return "{\"order_id\":\"" + order.orderId() + "\",\"client_order_id\":\"" + order.clientOrderId()
                + "\",\"ticker\":\"" + order.ticker() + "\",\"status\":\"" + status + "\",\"side\":\"yes\","
                + "\"action\":\"" + (order.side().equals("bid") ? "buy" : "sell") + "\",\"yes_price_dollars\":\""
                + order.price() + "\",\"fill_count_fp\":\"" + filled.setScale(2) + "\",\"remaining_count_fp\":\""
                + remaining.setScale(2) + "\",\"initial_count_fp\":\""
                + order.count().setScale(2)
                + "\",\"taker_fill_cost_dollars\":\"0.0000\",\"maker_fill_cost_dollars\":\""
                + order.price().multiply(filled).setScale(4) + "\",\"taker_fees_dollars\":\"0.0000\","
                + "\"maker_fees_dollars\":\"0.0000\",\"last_updated_ts_ms\":" + System.currentTimeMillis() + "}";
    }

    @Override
    public void onSocketOpen(final TlsVenueServer.Socket socket) {
        this.sockets.add(socket);
    }

    @Override
    public void onSocketText(final TlsVenueServer.Socket socket, final String text) {
        if (text.contains("\"subscribe\"")) {
            socket.sendText("{\"id\":1,\"type\":\"subscribed\",\"msg\":{\"channel\":\"user_orders\",\"sid\":1}}");
        }
    }

    @Override
    public void onSocketClosed(final TlsVenueServer.Socket socket) {
        this.sockets.remove(socket);
    }

    private void ping() {
        for (final TlsVenueServer.Socket socket : this.sockets) {
            socket.ping();
        }
    }

    private void push(final String event) {
        for (final TlsVenueServer.Socket socket : this.sockets) {
            socket.sendText(event);
        }
    }

    private long eventLag() {
        final int oneIn = this.timing.lagOneIn();
        final boolean late = oneIn > 0 && ThreadLocalRandom.current().nextInt(oneIn) == 0;
        return this.timing.eventLagNanos() + (late ? this.timing.lagNanos() : 0);
    }

    private void later(final long delayNanos, final Runnable action) {
        this.events.schedule(action, delayNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public Map<String, Long> counts() {
        final Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("orders", this.orders.get());
        counts.put("cancels", this.cancels.get());
        counts.put("amends", this.amends.get());
        counts.put("cancelNotFound", this.notFound.get());
        counts.put("fills", this.fills.get());
        counts.put("open", (long) this.open.size());
        this.unknown.forEach((route, count) -> counts.put("unknown " + route, count.get()));
        return counts;
    }

    @Override
    public void close() {
        this.events.shutdownNow();
    }
}
