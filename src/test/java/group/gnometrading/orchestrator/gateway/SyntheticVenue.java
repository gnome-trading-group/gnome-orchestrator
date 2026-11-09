package group.gnometrading.orchestrator.gateway;

import group.gnometrading.gateways.account.lane.FakeVenueServer.Reply;
import group.gnometrading.gateways.account.lane.FakeVenueServer.Request;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.networking.http.HTTPMethod;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The fixture's fake venue held to a real venue's rate limits, for load tests: it charges each request what that venue
 * would, answers 429 when its bucket is short (as the real one would, without queueing), and otherwise answers after a
 * set latency and tells the account's event socket what happened: every order acknowledged, every tenth filled in full
 * at once, every cancel confirmed. It counts what it took from each session and every 429 it gave.
 *
 * <p>{@link #dropSocket(long)} breaks the event socket as a venue's outage would, holding the reconnected socket's
 * catch-up back for a while as a venue slow to answer it would: what it would have pushed meanwhile is instead what
 * that catch-up finds.
 */
public final class SyntheticVenue {

    /** How a venue counts what it is sent. */
    public enum Model {
        /**
         * Kalshi's advanced tier: write tokens at 300 a second, 900 at most; an order or amend costs 10, a cancel 2.
         */
        KALSHI(300, 900),
        /** Polymarket US: 25 requests a second whatever they carry, so a batch counts once. */
        POLYMARKET_US(25, 25);

        final double perSecond;
        final long burst;

        Model(final double perSecond, final long burst) {
            this.perSecond = perSecond;
            this.burst = burst;
        }

        public double perSecond() {
            return this.perSecond;
        }
    }

    private static final long ORDER_SIZE = 1_000_000L;
    private static final int FILL_EVERY = 10;

    public final GatewayFixture.Venue venue;
    private final Model model;
    private final long latencyMillis;
    // A share of answers far slower than the rest, as a real venue's are now and then.
    private final long tailMillis;
    private final double tailShare;
    // How long after its request the event socket tells of it, as a venue's order stream lags its REST answers. One
    // thread, so each order's events keep their order.
    private final long eventDelayMillis = Long.getLong("load.event.delay.ms", 0);
    private final ScheduledExecutorService events = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "synthetic-venue-events");
        thread.setDaemon(true);
        return thread;
    });
    private final Object bucketLock = new Object();
    private double tokens;
    private long refilledAtNanos = System.nanoTime();

    private final AtomicLong refusals = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final LongAdder fillsPushed = new LongAdder();
    private final Map<Long, LongAdder> ordersBySlot = new ConcurrentHashMap<>();
    private final Map<String, Boolean> filled = new ConcurrentHashMap<>();
    private final LongAdder requests = new LongAdder();

    // Whether the event socket is down; what is pushed meanwhile goes to the catch-up instead. Under its own lock with
    // each event, so an event is either in the catch-up the reconnect fetches or on the socket after, never lost
    // between.
    private final Object eventLock = new Object();
    private boolean dropped;
    private final List<String> missed = new CopyOnWriteArrayList<>();

    public SyntheticVenue(final Path stateDirectory, final Model model, final long latencyMillis) throws Exception {
        this(stateDirectory, model, latencyMillis, 0, 0);
    }

    /** @param tailShare the share of answers that take {@code tailMillis} instead of {@code latencyMillis} */
    public SyntheticVenue(
            final Path stateDirectory,
            final Model model,
            final long latencyMillis,
            final long tailMillis,
            final double tailShare)
            throws Exception {
        this.venue = GatewayFixture.Venue.start(stateDirectory);
        this.model = model;
        this.latencyMillis = latencyMillis;
        this.tailMillis = tailMillis;
        this.tailShare = tailShare;
        this.tokens = model.burst;
        this.venue.server().script(this::answer);
    }

    /**
     * The gateway's own model of these limits for the fixture's request paths, at {@code share} of the venue's rate:
     * what the account's budget holds its lane to.
     */
    public static VenueRateLimits limits(final Model model, final double share) {
        final List<VenueRateLimits.Rule> rules = model == Model.KALSHI
                ? List.of(
                        new VenueRateLimits.Rule(
                                HTTPMethod.POST, "/orders/", "/amend", "write", 10, RequestClass.AMEND),
                        new VenueRateLimits.Rule(HTTPMethod.POST, "/orders", null, "write", 10, RequestClass.NEW),
                        new VenueRateLimits.Rule(HTTPMethod.DELETE, "/orders/", null, "write", 2, RequestClass.CANCEL),
                        new VenueRateLimits.Rule(null, "", null, "write", 10, RequestClass.NEW))
                : List.of(
                        new VenueRateLimits.Rule(
                                HTTPMethod.POST, "/batch/cancel", null, "write", 1, RequestClass.CANCEL),
                        new VenueRateLimits.Rule(HTTPMethod.DELETE, "/orders/", null, "write", 1, RequestClass.CANCEL),
                        new VenueRateLimits.Rule(HTTPMethod.POST, "/orders/", "/amend", "write", 1, RequestClass.AMEND),
                        new VenueRateLimits.Rule(null, "", null, "write", 1, RequestClass.NEW));
        return VenueRateLimits.of(
                        "Synthetic " + model,
                        List.of(new VenueRateLimits.Bucket("write", model.perSecond, model.burst, 0.2)),
                        rules,
                        List.of())
                .withShare(share);
    }

    public long refusals() {
        return this.refusals.get();
    }

    /** Requests the venue answered, 429s included. */
    public long requests() {
        return this.requests.sum();
    }

    /** New orders it took, each in a batch counted. */
    public long accepted() {
        return this.accepted.get();
    }

    public long fillsPushed() {
        return this.fillsPushed.sum();
    }

    /** New orders it took from the session in {@code slot}. */
    public long ordersFrom(final long slot) {
        final LongAdder count = this.ordersBySlot.get(slot);
        return count == null ? 0 : count.sum();
    }

    /** What it would have pushed while the socket was down, which the catch-up after has to find. */
    public List<String> missed() {
        return this.missed;
    }

    public void dropSocket(final long outageMillis) {
        final CountDownLatch gate = new CountDownLatch(1);
        synchronized (this.eventLock) {
            this.missed.clear();
            this.venue.protocol().fetchGate = gate;
            this.venue.protocol().catchUp = this.missed;
            this.dropped = true;
            this.venue.transport().broken = true;
        }
        final Thread release = new Thread(() -> {
            try {
                Thread.sleep(outageMillis);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Over before the catch-up is let through, so what it fetches is complete.
            synchronized (this.eventLock) {
                this.dropped = false;
            }
            gate.countDown();
        });
        release.setDaemon(true);
        release.start();
    }

    private Reply answer(final Request request) {
        this.requests.increment();
        final boolean batch = request.path().startsWith("/batch");
        final boolean create = request.method().equals("POST") && request.path().startsWith("/batch")
                ? !request.path().endsWith("/cancel")
                : request.method().equals("POST") && request.path().equals("/orders");
        final boolean cancel =
                request.method().equals("DELETE") || request.path().equals("/batch/cancel");
        if (!take(cost(create, cancel))) {
            this.refusals.incrementAndGet();
            return Reply.status(429);
        }
        if (create) {
            final String[] ids = request.body().split(",");
            final StringBuilder answer = new StringBuilder();
            for (final String id : ids) {
                created(id);
                answer.append(answer.length() == 0 ? "" : ",").append("v-").append(id);
            }
            return Reply.ok(answer.toString()).after(latency());
        }
        if (cancel) {
            final String[] venueIds = batch
                    ? request.body().split(",")
                    : new String[] {request.path().substring(8)};
            for (final String venueId : venueIds) {
                final String id = venueId.substring(2);
                if (this.filled.remove(id) == null) {
                    emit("CANCEL " + id);
                }
            }
        }
        return Reply.ok("").after(latency());
    }

    private long latency() {
        return ThreadLocalRandom.current().nextDouble() < this.tailShare ? this.tailMillis : this.latencyMillis;
    }

    private long cost(final boolean create, final boolean cancel) {
        if (this.model == Model.POLYMARKET_US) {
            return 1;
        }
        return cancel && !create ? 2 : 10;
    }

    private void created(final String id) {
        final long n = this.accepted.incrementAndGet();
        final long slot = Long.parseLong(id.substring(1, id.indexOf('-')));
        this.ordersBySlot.computeIfAbsent(slot, s -> new LongAdder()).increment();
        emit("ACK " + id);
        if (n % FILL_EVERY == 0) {
            // Filled in full: a cancel that follows finds nothing, as the real venue's would.
            this.filled.put(id, Boolean.TRUE);
            this.fillsPushed.increment();
            emit("FILL " + id + " " + ORDER_SIZE);
        }
    }

    private boolean take(final long cost) {
        synchronized (this.bucketLock) {
            final long now = System.nanoTime();
            this.tokens =
                    Math.min(this.model.burst, this.tokens + (now - this.refilledAtNanos) * this.model.perSecond / 1e9);
            this.refilledAtNanos = now;
            if (this.tokens < cost) {
                return false;
            }
            this.tokens -= cost;
            return true;
        }
    }

    private void emit(final String event) {
        if (this.eventDelayMillis > 0) {
            this.events.schedule(() -> emitNow(event), this.eventDelayMillis, TimeUnit.MILLISECONDS);
        } else {
            emitNow(event);
        }
    }

    private void emitNow(final String event) {
        synchronized (this.eventLock) {
            if (this.dropped) {
                this.missed.add(event);
            } else {
                this.venue.transport().push(event);
            }
        }
    }
}
