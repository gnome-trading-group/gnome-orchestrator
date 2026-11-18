package group.gnometrading.orchestrator.live.probes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.lane.RequestShape;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlAuthHeaders;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlLaneVenue;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlMarket;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlMarketInfo;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlOrderSigner;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.networking.http.AsyncHTTPConnections;
import group.gnometrading.networking.http.HTTPClient;
import group.gnometrading.networking.http.HTTPCompletion;
import group.gnometrading.networking.http.HTTPConnector;
import group.gnometrading.networking.http.HTTPProtocol;
import group.gnometrading.networking.http.HTTPRequestWriter;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.orchestrator.credentials.PolymarketIntlCredentials;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sm.Listing;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.agrona.concurrent.SystemEpochNanoClock;

/**
 * What Polymarket International does with a quote's cancel around the order's own answer, to decide whether the
 * gateway may report an order acknowledged from its HTTP answer, and send a cancel before that answer. Orders are
 * post-only bids at {@code -Dprice} (0.01) for the market's minimum size, far below the book, so nothing fills; every
 * order is cancelled and looked up again at the end. Orders go through the gateway's own
 * {@link PolymarketIntlLaneVenue} (signed and shaped as the lane sends them) on two keep-alive connections, and the
 * account's user websocket is read alongside, every event stamped on arrival.
 *
 * <ul>
 *   <li>{@code -Dmode=ack}: {@code -Drounds} (30) of submit, wait for its answer and the PLACEMENT event, cancel, wait
 *       for its answer and the CANCELLATION event. Prints each answer's {@code status} and how far each event trails
 *       its HTTP answer.
 *   <li>{@code -Dmode=early}: for each delay in {@code -Ddelays} (0,5,20,50 ms), {@code -Drounds} (5) of submit on one
 *       connection and a cancel on the other that long after the submit was written, without waiting for the submit's
 *       answer. Prints both answers, the events, and the order's state {@code -Dlookup.after.ms} (2000) later; an
 *       order still resting then is an ORPHAN, and is cancelled.
 *   <li>{@code -Dmode=double}: {@code -Drounds} (10) of resting an order, then two cancels at once on the two
 *       connections. Prints both answers.
 * </ul>
 *
 * <p>{@code -Dmarket=condition:token} (pick one whose best bid is well above the price), {@code -Dsecret}
 * ({@code gnome/accounts/<label>/trading}), {@code -Daws.profile} (dev), {@code -Dsize} (the market's minimum),
 * {@code -Dpause.ms} between rounds (1000). Trades real money, so {@code -Dconfirm=yes} is required; it refuses to
 * start from a geoblocked IP. Order hashes print as their first 10 characters; keys never print.
 */
public final class PolymarketIntlCancelProbe {

    private static final String CLOB = "clob.polymarket.com";
    private static final String USER_WS = "wss://ws-subscriptions-clob.polymarket.com/ws/user";
    private static final String GEOBLOCK = "https://polymarket.com/api/geoblock";
    private static final Set<String> FRONTEND_ONLY_RESTRICTED = Set.of("IE", "JP", "MT", "NL", "KR");
    private static final int EXCHANGE_ID = 2;
    private static final int MAX_PATH = 1024;
    private static final int MAX_BODY = 16 * 1024;
    private static final long ANSWER_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long EVENT_WAIT_MILLIS = 3_000;
    private static final long PING_SECONDS = 10;
    private static final String[] CAPTURED = {"server-timing"};
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int SUBMIT = 0;
    private static final int CANCEL = 1;
    private static final int LOOKUP = 2;
    private static final String[] KINDS = {"SUBMIT", "CANCEL", "LOOKUP"};

    private final PolymarketIntlLaneVenue venue;
    private final PolymarketIntlMarket market;
    private final Listing listing;
    private final long price;
    private final long size;
    private final AsyncHTTPConnections connections;
    private final HTTPConnector connector;
    private final RequestShape shape = new RequestShape(MAX_PATH);
    private final UserEvents events;
    private final Map<Long, Request> pending = new HashMap<>();
    private final List<Order> orders = new ArrayList<>();
    private long nextTag = 1;
    private int counter;

    /** One signed order, its body ready to send. */
    private record Order(OrderContext ctx, byte[] body, int length, String hash) {}

    /** One request sent and, once {@link #await} returns, its answer. Times are epoch nanoseconds on this host. */
    private static final class Request {
        final int kind;
        final Order order;
        long sentNanos;
        long answeredNanos;
        int outcome = -1;
        int status = -1;
        String body = "";
        String serverTiming = "";
        String readAs = "";

        Request(final int kind, final Order order) {
            this.kind = kind;
            this.order = order;
        }

        double answerMs() {
            return (answeredNanos - sentNanos) / 1e6;
        }

        String field(final String name) {
            try {
                final JsonNode node = JSON.readTree(body);
                return node == null || node.path(name).isMissingNode()
                        ? "-"
                        : node.path(name).asText();
            } catch (Exception e) {
                return "-";
            }
        }
    }

    private PolymarketIntlCancelProbe(final PolymarketIntlCredentials creds, final String spec) throws Exception {
        final PolymarketIntlAuthHeaders auth = new PolymarketIntlAuthHeaders(
                creds.apiKey(), creds.secret(), creds.passphrase(), creds.signerAddress());
        this.venue = new PolymarketIntlLaneVenue(
                new ConsoleLogger(new SystemEpochNanoClock()),
                new PolymarketIntlOrderSigner(
                        creds.ethereumPrivateKey(),
                        creds.signerAddress(),
                        creds.funderAddress(),
                        creds.signatureType()),
                auth);
        final String condition = spec.substring(0, spec.indexOf(':'));
        this.listing = OrderTimingProbe.listing("POLYMARKET", spec, condition);
        final PolymarketIntlMarketInfo info = PolymarketIntlMarketInfo.load(new HTTPClient(), CLOB, condition);
        this.market = PolymarketIntlMarket.of(listing, info);
        this.venue.addMarket(EXCHANGE_ID, listing.security().securityId(), market);
        this.price = OrderTimingProbe.scaled(System.getProperty("price", "0.01"), Statics.PRICE_SCALING_FACTOR);
        this.size = System.getProperty("size") != null
                ? OrderTimingProbe.scaled(System.getProperty("size"), Statics.SIZE_SCALING_FACTOR)
                : info.minOrderSize();
        if (info.minOrderAgeSeconds() > 0) {
            throw new IllegalStateException("This market refuses cancels of orders younger than "
                    + info.minOrderAgeSeconds() + " s; pick another");
        }
        this.connections = AsyncHTTPConnections.builder(HTTPProtocol.HTTPS, CLOB)
                .withConnections(2)
                .captureHeaders(CAPTURED)
                .build();
        this.connector = new HTTPConnector(List.of(connections), HTTPConnector.Listener.NONE);
        this.events = UserEvents.open(creds, condition);
    }

    public static void main(final String[] args) throws Exception {
        if (!"yes".equals(System.getProperty("confirm"))) {
            throw new IllegalArgumentException("Trades real money: -Dconfirm=yes is required");
        }
        checkNotGeoblocked();
        final PolymarketIntlCredentials creds = PolymarketIntlCredentials.fromJson(
                OrderTimingProbe.secret(System.getProperty("aws.profile", "dev"), OrderTimingProbe.required("secret")));
        final PolymarketIntlCancelProbe probe =
                new PolymarketIntlCancelProbe(creds, OrderTimingProbe.required("market"));
        final String mode = OrderTimingProbe.required("mode");
        boolean clean = false;
        try {
            probe.warm();
            System.out.printf(
                    "Mode %s on %s: bids %s x %s%n",
                    mode,
                    OrderTimingProbe.required("market").substring(0, 12) + "...",
                    probe.price / (double) Statics.PRICE_SCALING_FACTOR,
                    probe.size / (double) Statics.SIZE_SCALING_FACTOR);
            switch (mode) {
                case "ack" -> probe.ack(Integer.getInteger("rounds", 30));
                case "early" -> probe.early(delays(), Integer.getInteger("rounds", 5));
                case "double" -> probe.twoCancels(Integer.getInteger("rounds", 10));
                default -> throw new IllegalArgumentException("-Dmode is ack, early or double");
            }
            clean = true;
        } finally {
            clean &= probe.cleanUp();
            probe.events.close();
            probe.connections.close();
        }
        System.out.println(clean ? "\nDONE: nothing left resting" : "\nCHECK THE ACCOUNT: see the lines above");
        System.exit(clean ? 0 : 1);
    }

    // ----- modes -----

    private void ack(final int rounds) throws Exception {
        final List<Double> placementLag = new ArrayList<>();
        final List<Double> cancellationLag = new ArrayList<>();
        for (int round = 1; round <= rounds; round++) {
            final Order order = newOrder();
            final Request submit = start(SUBMIT, order);
            await(submit);
            final UserEvents.Event placement = events.await(order.hash(), UserEvents.Event::isPlacement);
            System.out.printf(
                    "round %d submit: %d %s, status=%s, answer %.1f ms, %s, placement %s%n",
                    round,
                    submit.status,
                    submit.readAs,
                    submit.field("status"),
                    submit.answerMs(),
                    submit.serverTiming,
                    after(placement, submit));
            if (placement != null) {
                placementLag.add((placement.receivedNanos() - submit.answeredNanos) / 1e6);
            }
            if (!SubmitResult.ACCEPTED.name().equals(submit.readAs)) {
                continue;
            }
            final Request cancel = start(CANCEL, order);
            await(cancel);
            final UserEvents.Event cancellation = events.await(order.hash(), UserEvents.Event::isCancellation);
            System.out.printf(
                    "round %d cancel: %d %s, answer %.1f ms, %s, cancellation %s%n",
                    round,
                    cancel.status,
                    cancel.readAs,
                    cancel.answerMs(),
                    cancel.serverTiming,
                    after(cancellation, cancel));
            if (cancellation != null) {
                cancellationLag.add((cancellation.receivedNanos() - cancel.answeredNanos) / 1e6);
            }
            pause();
        }
        System.out.println("\nPLACEMENT after the submit's answer, ms: " + summary(placementLag));
        System.out.println("CANCELLATION after the cancel's answer, ms: " + summary(cancellationLag));
    }

    private void early(final long[] delaysMillis, final int rounds) throws Exception {
        final long lookupAfterMillis = Long.getLong("lookup.after.ms", 2_000);
        int orphans = 0;
        for (final long delay : delaysMillis) {
            for (int round = 1; round <= rounds; round++) {
                final Order order = newOrder();
                final Request submit = start(SUBMIT, order);
                final long cancelAt = submit.sentNanos + TimeUnit.MILLISECONDS.toNanos(delay);
                while (OrderTimingProbe.epochNanos() - cancelAt < 0) {
                    Thread.onSpinWait();
                }
                final Request cancel = start(CANCEL, order);
                await(submit, cancel);
                final String first = submit.answeredNanos <= cancel.answeredNanos ? "submit" : "cancel";
                System.out.printf(
                        "delay %d round %d [%s]: cancel sent %.1f ms after the submit; %s answered first%n",
                        delay, round, short10(order.hash()), (cancel.sentNanos - submit.sentNanos) / 1e6, first);
                System.out.printf(
                        "  submit: %d %s, status=%s, answered %.1f ms after its send, %s%n",
                        submit.status, submit.readAs, submit.field("status"), submit.answerMs(), submit.serverTiming);
                System.out.printf(
                        "  cancel: %d %s, answered %.1f ms after its send, %s, body %s%n",
                        cancel.status, cancel.readAs, cancel.answerMs(), cancel.serverTiming, cancel.body);
                Thread.sleep(lookupAfterMillis);
                System.out.println("  events: " + events.describe(order.hash(), submit.sentNanos));
                final Request lookup = start(LOOKUP, order);
                await(lookup);
                final String state = lookup.field("status");
                System.out.printf(
                        "  %d ms later: lookup %d %s, status=%s, size_matched=%s%n",
                        lookupAfterMillis, lookup.status, lookup.readAs, state, lookup.field("size_matched"));
                if (state.toUpperCase().contains("LIVE")) {
                    orphans++;
                    final Request again = start(CANCEL, order);
                    await(again);
                    System.out.printf("  ORPHAN: still resting after its cancel; cancelled again: %s%n", again.readAs);
                }
                pause();
            }
        }
        System.out.println("\nOrphans: " + orphans);
    }

    private void twoCancels(final int rounds) throws Exception {
        for (int round = 1; round <= rounds; round++) {
            final Order order = newOrder();
            final Request submit = start(SUBMIT, order);
            await(submit);
            if (!SubmitResult.ACCEPTED.name().equals(submit.readAs)) {
                System.out.printf(
                        "round %d: submit %d %s, body %s%n", round, submit.status, submit.readAs, submit.body);
                continue;
            }
            events.await(order.hash(), UserEvents.Event::isPlacement);
            final Request one = start(CANCEL, order);
            final Request two = start(CANCEL, order);
            await(one, two);
            System.out.printf(
                    "round %d [%s]: second cancel sent %.2f ms after the first%n",
                    round, short10(order.hash()), (two.sentNanos - one.sentNanos) / 1e6);
            for (final Request cancel : List.of(one, two)) {
                System.out.printf(
                        "  cancel answered %.1f ms after the first was sent: %d %s, body %s%n",
                        (cancel.answeredNanos - one.sentNanos) / 1e6, cancel.status, cancel.readAs, cancel.body);
            }
            pause();
        }
    }

    /** Cancels every order the run placed and looks each up; false if any still rests. */
    private boolean cleanUp() {
        boolean clean = true;
        for (final Order order : orders) {
            try {
                final Request cancel = start(CANCEL, order);
                await(cancel);
                final Request lookup = start(LOOKUP, order);
                await(lookup);
                final String state = lookup.field("status");
                if (state.toUpperCase().contains("LIVE")) {
                    clean = false;
                    System.out.printf(
                            "STILL RESTING: %s (cancel: %s, body %s)%n",
                            short10(order.hash()), cancel.readAs, cancel.body);
                }
            } catch (Exception e) {
                clean = false;
                System.out.printf("CLEAN-UP FAILED for %s: %s%n", short10(order.hash()), e);
            }
        }
        System.out.printf("Clean-up: %d order(s) checked%n", orders.size());
        return clean;
    }

    // ----- requests -----

    private Order newOrder() {
        final LaneCommand command = new LaneCommand();
        command.reset();
        command.kind = LaneCommand.NEW;
        command.sessionSlot = 1 + (System.currentTimeMillis() / 1000) % 90_000;
        command.clientOidCounter = ++counter;
        command.clientOidStrategyId = 1;
        command.exchangeId = EXCHANGE_ID;
        command.securityId = listing.security().securityId();
        command.side = Side.Bid;
        command.price = price;
        command.size = size;
        command.timeInForce = TimeInForce.GOOD_TILL_CANCELED;
        command.orderType = OrderType.LIMIT;
        command.postOnly = true;
        final OrderContext ctx = new OrderContext();
        ctx.sessionSlot = command.sessionSlot;
        ctx.clientOidCounter = command.clientOidCounter;
        ctx.clientOidStrategyId = command.clientOidStrategyId;
        ctx.exchangeId = command.exchangeId;
        ctx.securityId = command.securityId;
        ctx.side = command.side;
        ctx.limitPrice = command.price;
        ctx.originalQty = command.size;
        ctx.leavesQty = command.size;
        final byte[] body = new byte[MAX_BODY];
        final int length = venue.prepare(command, market, ctx, body, System.currentTimeMillis());
        if (length < 0) {
            throw new IllegalStateException("The venue refused to build the order: " + ctx.rejectReason);
        }
        final Order order = new Order(
                ctx,
                body,
                length,
                new String(ctx.correlationIdBytes, 0, ctx.correlationIdLength, StandardCharsets.US_ASCII));
        orders.add(order);
        return order;
    }

    /** Writes and sends one request on an idle connection, without waiting for its answer. */
    private Request start(final int kind, final Order order) throws InterruptedException {
        final Request request = new Request(kind, order);
        final int connection = OrderTimingProbe.acquire(connections, connector);
        final HTTPRequestWriter writer;
        final long nowMillis = System.currentTimeMillis();
        switch (kind) {
            case SUBMIT -> {
                venue.submitShape(order.ctx(), market, shape);
                writer = connections.request(connection, shape.method).path(shape.path);
                venue.writeSubmit(order.ctx(), shape, order.body(), order.length(), writer, nowMillis);
            }
            case CANCEL -> {
                venue.cancelShape(order.ctx(), market, shape);
                writer = connections.request(connection, shape.method).path(shape.path);
                venue.writeCancel(order.ctx(), market, shape, writer, nowMillis);
            }
            default -> {
                venue.lookupShape(order.ctx(), market, shape);
                writer = connections.request(connection, shape.method).path(shape.path);
                venue.writeLookup(order.ctx(), market, shape, writer, nowMillis);
            }
        }
        final long tag = nextTag++;
        request.sentNanos = OrderTimingProbe.epochNanos();
        final int sent = connections.send(connection, tag, System.nanoTime() + ANSWER_TIMEOUT_NANOS);
        if (sent != AsyncHTTPConnections.SENT) {
            throw new IllegalStateException(KINDS[kind] + " not sent (" + sent + ")");
        }
        pending.put(tag, request);
        return request;
    }

    /** Polls until every one of {@code requests} has its answer, reading each while its response is valid. */
    private void await(final Request... requests) {
        while (Arrays.stream(requests).anyMatch(r -> r.outcome < 0)) {
            if (!connections.poll()) {
                Thread.onSpinWait();
                continue;
            }
            final long now = OrderTimingProbe.epochNanos();
            final HTTPCompletion completion = connections.completion();
            final Request request = pending.remove(completion.tag());
            if (request == null) {
                continue;
            }
            request.answeredNanos = now;
            request.outcome = completion.outcome();
            if (completion.outcome() != HTTPCompletion.RESPONSE) {
                request.readAs = completion.outcome() == HTTPCompletion.TIMED_OUT ? "TIMED_OUT" : "FAILED";
                continue;
            }
            final HTTPResponse response = completion.response();
            request.status = response.getStatusCode();
            request.serverTiming = response.hasHeader(0) ? "server-timing " + response.header(0) : "no server-timing";
            request.body = redact(text(response.getBody()), request.order.hash());
            request.readAs = switch (request.kind) {
                case SUBMIT -> venue.readSubmit(request.order.ctx(), response).name();
                case CANCEL -> venue.readCancel(request.order.ctx(), response).name();
                default -> venue.readLookup(request.order.ctx(), response).name();};
        }
    }

    /** Opens both connections before the first timed request, so no round pays a TLS handshake. */
    private void warm() throws InterruptedException {
        final long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        int first = AsyncHTTPConnections.NONE;
        while (true) {
            connector.doWork();
            final int connection = connections.acquire();
            if (connection != AsyncHTTPConnections.NONE) {
                if (first == AsyncHTTPConnections.NONE) {
                    first = connection;
                } else if (connection != first) {
                    return;
                }
            }
            if (System.nanoTime() - giveUp > 0) {
                throw new IllegalStateException("Two connections to " + CLOB + " didn't open");
            }
            Thread.sleep(5);
        }
    }

    // ----- helpers -----

    private static void checkNotGeoblocked() throws Exception {
        final HttpResponse<String> answer = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(GEOBLOCK))
                                .timeout(Duration.ofSeconds(10))
                                .header("User-Agent", "Mozilla/5.0")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        final JsonNode body = JSON.readTree(answer.body());
        final String country = body.path("country").asText("?");
        if (!body.path("blocked").asBoolean(true)) {
            System.out.println("Geoblock check: " + country + ", not blocked");
        } else if (FRONTEND_ONLY_RESTRICTED.contains(country)) {
            // The endpoint answers for the website; docs.polymarket.com/api-reference/geoblock lists these countries as
            // close-only on the frontend with the API itself not restricted.
            System.out.println("Geoblock check: " + country + " is close-only on the frontend only; the API is open");
        } else {
            throw new IllegalStateException("Geoblocked from here (" + country + "); turn the VPN off");
        }
    }

    private static long[] delays() {
        return Arrays.stream(System.getProperty("delays", "0,5,20,50").split(","))
                .mapToLong(s -> Long.parseLong(s.trim()))
                .toArray();
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(Long.getLong("pause.ms", 1_000));
    }

    private static String after(final UserEvents.Event event, final Request request) {
        return event == null
                ? "NOT SEEN in " + EVENT_WAIT_MILLIS + " ms"
                : String.format(
                        "%.1f ms after the send, %+.1f ms vs the answer",
                        (event.receivedNanos() - request.sentNanos) / 1e6,
                        (event.receivedNanos() - request.answeredNanos) / 1e6);
    }

    private static String summary(final List<Double> values) {
        if (values.isEmpty()) {
            return "none seen";
        }
        final double[] sorted =
                values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return String.format(
                "n=%d min %.1f median %.1f p90 %.1f max %.1f",
                sorted.length,
                sorted[0],
                sorted[sorted.length / 2],
                sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * 0.9))],
                sorted[sorted.length - 1]);
    }

    private static String text(final ByteBuffer body) {
        if (body == null || !body.hasRemaining()) {
            return "";
        }
        final ByteBuffer copy = body.duplicate();
        final byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String redact(final String body, final String hash) {
        return body.replace(hash, "<order>");
    }

    private static String short10(final String hash) {
        return hash.length() > 10 ? hash.substring(0, 10) : hash;
    }

    /** The account's user channel: every order and trade event naming an order, stamped when it arrived. */
    private static final class UserEvents implements WebSocket.Listener {

        record Event(long receivedNanos, String eventType, String type, String status, String sizeMatched) {
            boolean isPlacement() {
                return "order".equals(eventType) && "PLACEMENT".equalsIgnoreCase(type);
            }

            boolean isCancellation() {
                return "order".equals(eventType)
                        && ("CANCELLATION".equalsIgnoreCase(type) || "CANCELED".equalsIgnoreCase(status));
            }
        }

        private final Map<String, List<Event>> byOrder = new ConcurrentHashMap<>();
        private final StringBuilder partial = new StringBuilder();
        private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "pm-user-ping");
            thread.setDaemon(true);
            return thread;
        });
        private WebSocket socket;

        static UserEvents open(final PolymarketIntlCredentials creds, final String condition) {
            final UserEvents events = new UserEvents();
            events.socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(USER_WS), events)
                    .join();
            final ObjectNode subscribe = JSON.createObjectNode();
            subscribe
                    .putObject("auth")
                    .put("apiKey", creds.apiKey())
                    .put("secret", creds.secret())
                    .put("passphrase", creds.passphrase());
            subscribe.put("type", "user");
            subscribe.putArray("markets").add(condition);
            events.socket.sendText(subscribe.toString(), true).join();
            events.pinger.scheduleAtFixedRate(
                    () -> events.socket.sendText("PING", true), PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
            return events;
        }

        @Override
        public void onOpen(final WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(final WebSocket webSocket, final CharSequence data, final boolean last) {
            final long now = OrderTimingProbe.epochNanos();
            partial.append(data);
            if (last) {
                handle(partial.toString(), now);
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(final WebSocket webSocket, final Throwable error) {
            System.out.println("USER SOCKET ERROR: " + error);
        }

        @Override
        public CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode, final String reason) {
            System.out.println("USER SOCKET CLOSED: " + statusCode + " " + reason);
            return null;
        }

        private void handle(final String text, final long receivedNanos) {
            if (text.isBlank() || "PONG".equals(text.trim())) {
                return;
            }
            final JsonNode root;
            try {
                root = JSON.readTree(text);
            } catch (Exception e) {
                return;
            }
            if (root.isArray()) {
                root.forEach(node -> handle(node, receivedNanos));
            } else {
                handle(root, receivedNanos);
            }
        }

        private void handle(final JsonNode node, final long receivedNanos) {
            final String eventType = node.path("event_type").asText("");
            if (eventType.equals("order")) {
                add(
                        node.path("id").asText(),
                        new Event(
                                receivedNanos,
                                eventType,
                                node.path("type").asText(""),
                                node.path("status").asText(""),
                                node.path("size_matched").asText("")));
            } else if (eventType.equals("trade")) {
                final Event event = new Event(
                        receivedNanos,
                        eventType,
                        node.path("type").asText(""),
                        node.path("status").asText(""),
                        "");
                add(node.path("taker_order_id").asText(), event);
                node.path("maker_orders")
                        .forEach(maker -> add(maker.path("order_id").asText(), event));
            }
        }

        private void add(final String orderId, final Event event) {
            if (!orderId.isEmpty()) {
                byOrder.computeIfAbsent(orderId, id -> new CopyOnWriteArrayList<>())
                        .add(event);
            }
        }

        Event await(final String hash, final Predicate<Event> wanted) throws InterruptedException {
            final long giveUp = System.currentTimeMillis() + EVENT_WAIT_MILLIS;
            while (System.currentTimeMillis() < giveUp) {
                final List<Event> seen = byOrder.get(hash);
                if (seen != null) {
                    for (final Event event : seen) {
                        if (wanted.test(event)) {
                            return event;
                        }
                    }
                }
                Thread.sleep(1);
            }
            return null;
        }

        String describe(final String hash, final long sinceNanos) {
            final List<Event> seen = byOrder.getOrDefault(hash, List.of());
            if (seen.isEmpty()) {
                return "none";
            }
            final StringBuilder out = new StringBuilder();
            for (final Event event : seen) {
                out.append(String.format(
                        "%s/%s/%s matched=%s @%.1f ms; ",
                        event.eventType(),
                        event.type(),
                        event.status(),
                        event.sizeMatched(),
                        (event.receivedNanos() - sinceNanos) / 1e6));
            }
            return out.toString();
        }

        void close() {
            pinger.shutdownNow();
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }
}
