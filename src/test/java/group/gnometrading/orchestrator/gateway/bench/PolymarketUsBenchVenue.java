package group.gnometrading.orchestrator.gateway.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
 * Polymarket US's API as the bench needs it: the gateway's real Ed25519-signed creates (one at a time or batched),
 * cancels (likewise) and modifies, answered with the venue's ids, and the private websocket's order subscription
 * carrying each order's executions. Signatures are never checked. Orders are told apart in events by the
 * {@code goodTillTime} stamp the gateway writes, which the venue echoes, as it does live.
 *
 * <p>Timing as {@link PolymarketIntlBenchVenue.Timing}.
 */
public final class PolymarketUsBenchVenue implements BenchVenue {

    /** Polymarket US's answers to a gateway in its region: tens of milliseconds of processing. */
    public static PolymarketIntlBenchVenue.Timing liveTiming() {
        return new PolymarketIntlBenchVenue.Timing(
                TimeUnit.MILLISECONDS.toNanos(35),
                TimeUnit.MILLISECONDS.toNanos(3),
                50,
                TimeUnit.MILLISECONDS.toNanos(100),
                20,
                TimeUnit.MILLISECONDS.toNanos(200));
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Order(
            String id, String marketSlug, String intent, String price, BigDecimal quantity, String goodTillTime) {}

    private final PolymarketIntlBenchVenue.Timing timing;
    private final ScheduledExecutorService events = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "bench-pmus-events");
        thread.setDaemon(true);
        return thread;
    });
    private final List<TlsVenueServer.Socket> sockets = new CopyOnWriteArrayList<>();
    private final Map<String, Order> open = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicLong executions = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong orders = new AtomicLong();
    private final AtomicLong cancels = new AtomicLong();
    private final AtomicLong notFound = new AtomicLong();
    private final AtomicLong fills = new AtomicLong();
    private final Map<String, AtomicLong> unknown = new ConcurrentHashMap<>();

    public PolymarketUsBenchVenue(final PolymarketIntlBenchVenue.Timing timing) {
        this.timing = timing;
    }

    @Override
    public TlsVenueServer.Reply onRequest(final TlsVenueServer.Request request) {
        final String path = request.path();
        final int query = path.indexOf('?');
        final String route = query < 0 ? path : path.substring(0, query);
        final boolean post = request.method().equals("POST");
        try {
            if (post && route.equals("/v1/orders")) {
                this.requests.incrementAndGet();
                final Order order = create(MAPPER.readTree(request.body()));
                return TlsVenueServer.Reply.ok("{\"id\":\"" + order.id() + "\"}", this.timing.answerNanos())
                        .then(() -> afterPlaced(order));
            }
            if (post && route.equals("/v1/orders/batched")) {
                this.requests.incrementAndGet();
                final List<Order> created = new ArrayList<>();
                for (final JsonNode order : MAPPER.readTree(request.body()).get("orders")) {
                    created.add(create(order));
                }
                final StringBuilder ids = new StringBuilder("{\"createdOrderIds\":[");
                for (int i = 0; i < created.size(); i++) {
                    ids.append(i == 0 ? "" : ",")
                            .append('"')
                            .append(created.get(i).id())
                            .append('"');
                }
                return TlsVenueServer.Reply.ok(ids.append("]}").toString(), this.timing.answerNanos())
                        .then(() -> created.forEach(this::afterPlaced));
            }
            if (post && route.equals("/v1/orders/batched/cancel")) {
                this.requests.incrementAndGet();
                final List<Order> canceled = new ArrayList<>();
                for (final JsonNode cancel : MAPPER.readTree(request.body()).get("orders")) {
                    final Order order = cancel(cancel.get("orderId").asText());
                    if (order != null) {
                        canceled.add(order);
                    }
                }
                return TlsVenueServer.Reply.ok("{}", this.timing.answerNanos())
                        .then(() -> canceled.forEach(this::afterCanceled));
            }
            if (post && route.startsWith("/v1/order/") && route.endsWith("/cancel")) {
                this.requests.incrementAndGet();
                final Order order = cancel(route.substring("/v1/order/".length(), route.length() - "/cancel".length()));
                if (order == null) {
                    return new TlsVenueServer.Reply(
                            404, "{\"code\":5,\"message\":\"order not found\"}", this.timing.answerNanos(), null);
                }
                return TlsVenueServer.Reply.ok("{}", this.timing.answerNanos()).then(() -> afterCanceled(order));
            }
            if (post && route.startsWith("/v1/order/") && route.endsWith("/modify")) {
                this.requests.incrementAndGet();
                return TlsVenueServer.Reply.ok("{}", this.timing.answerNanos());
            }
            if (route.startsWith("/v1/market/slug/")) {
                return TlsVenueServer.Reply.ok(
                        "{\"market\":{\"slug\":\"" + route.substring("/v1/market/slug/".length())
                                + "\",\"orderPriceMinTickSize\":0.001,\"minimumTradeQty\":1,\"active\":true}}",
                        0);
            }
            if (route.equals("/v1/orders/open")) {
                return TlsVenueServer.Reply.ok("{\"orders\":[]}", this.timing.answerNanos());
            }
            if (route.equals("/v1/portfolio/activities")) {
                return TlsVenueServer.Reply.ok(
                        "{\"activities\":[],\"nextCursor\":\"\",\"eof\":true}", this.timing.answerNanos());
            }
        } catch (Exception e) {
            return new TlsVenueServer.Reply(500, "{\"message\":\"" + e + "\"}", 0, null);
        }
        this.unknown
                .computeIfAbsent(request.method() + " " + route, k -> new AtomicLong())
                .incrementAndGet();
        return new TlsVenueServer.Reply(404, "{\"code\":5,\"message\":\"not found\"}", 0, null);
    }

    private Order create(final JsonNode body) {
        this.orders.incrementAndGet();
        final Order order = new Order(
                "BENCH" + this.ids.incrementAndGet(),
                body.get("marketSlug").asText(),
                body.get("intent").asText(),
                body.get("price").get("value").asText(),
                new BigDecimal(body.get("quantity").asText()),
                body.has("goodTillTime") ? body.get("goodTillTime").asText() : null);
        this.open.put(order.id(), order);
        return order;
    }

    private Order cancel(final String id) {
        this.cancels.incrementAndGet();
        final Order order = this.open.remove(id);
        if (order == null) {
            this.notFound.incrementAndGet();
        }
        return order;
    }

    private void afterPlaced(final Order order) {
        later(eventLag(), () -> push(execution(order, "EXECUTION_TYPE_NEW", "ORDER_STATE_NEW", BigDecimal.ZERO)));
        final int fillOneIn = this.timing.fillOneIn();
        if (fillOneIn > 0 && ThreadLocalRandom.current().nextInt(fillOneIn) == 0) {
            later(this.timing.fillAfterNanos(), () -> {
                if (this.open.remove(order.id()) != null) {
                    this.fills.incrementAndGet();
                    push(execution(order, "EXECUTION_TYPE_FILL", "ORDER_STATE_FILLED", order.quantity()));
                }
            });
        }
    }

    private void afterCanceled(final Order order) {
        later(
                eventLag(),
                () -> push(execution(order, "EXECUTION_TYPE_CANCELED", "ORDER_STATE_CANCELED", BigDecimal.ZERO)));
    }

    private String execution(final Order order, final String type, final String state, final BigDecimal filled) {
        final String now = Instant.now().toString();
        final BigDecimal leaves = state.equals("ORDER_STATE_NEW") ? order.quantity() : BigDecimal.ZERO;
        return "{\"requestId\":\"orders\",\"subscriptionType\":\"SUBSCRIPTION_TYPE_ORDER\","
                + "\"orderSubscriptionUpdate\":{\"execution\":{\"id\":\"X" + this.executions.incrementAndGet()
                + "\",\"order\":{\"id\":\"" + order.id() + "\",\"marketSlug\":\"" + order.marketSlug()
                + "\",\"side\":\"ORDER_SIDE_BUY\",\"type\":\"ORDER_TYPE_LIMIT\",\"price\":{\"value\":\""
                + order.price() + "\",\"currency\":\"USD\"},\"quantity\":"
                + order.quantity().toPlainString()
                + ",\"cumQuantity\":" + filled.toPlainString() + ",\"leavesQuantity\":" + leaves.toPlainString()
                + ",\"tif\":\"TIME_IN_FORCE_GOOD_TILL_DATE\",\"goodTillTime\":"
                + (order.goodTillTime() == null ? "null" : "\"" + order.goodTillTime() + "\"") + ",\"intent\":\""
                + order.intent() + "\",\"state\":\"" + state + "\",\"commissionNotionalTotalCollected\":{\"value\":"
                + "\"0.0000\",\"currency\":\"USD\"},\"avgPx\":{\"value\":\"" + order.price()
                + "\",\"currency\":\"USD\"},\"createTime\":\"" + now + "\"},\"lastShares\":\""
                + filled.toPlainString() + "\",\"lastPx\":{\"value\":\"" + order.price()
                + "\",\"currency\":\"USD\"},\"type\":\"" + type + "\",\"text\":\"\",\"transactTime\":\"" + now
                + "\",\"aggressor\":false}}}";
    }

    @Override
    public void onSocketOpen(final TlsVenueServer.Socket socket) {
        this.sockets.add(socket);
    }

    @Override
    public void onSocketText(final TlsVenueServer.Socket socket, final String text) {}

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
        counts.put("requests", this.requests.get());
        counts.put("orders", this.orders.get());
        counts.put("cancels", this.cancels.get());
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
