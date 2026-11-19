package group.gnometrading.orchestrator.session.orders;

import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.utils.SampledEpochNanoClock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Sessions for the gateway bench: each a real {@link GatewayClient} over Aeron, as a session's order entry is, driven
 * from one spinning thread (as a low-latency session's OMS is) so the session side adds as little as it can. Each keeps
 * {@code quotes} bids cycling: send, wait for its NEW, rest {@code restNanos}, cancel, wait for its end, pause
 * {@code gapNanos}, again. One order in {@code raceOneIn} is cancelled in the same pass it is sent, so its cancel races
 * it to the gateway and the venue (ending it dropped unsent, refused as already cancelled, or cancelled after its NEW).
 *
 * <p>Measures, at the session, from send to the report that answers it: order to NEW, cancel to the order's end, and a
 * raced cancel to its order's end. Samples go into preallocated arrays, exact, so the bench can tell tens of
 * microseconds apart on a round trip of tens of milliseconds.
 */
public final class BenchSessions implements AutoCloseable {

    /** How each session trades. */
    public record Shape(int quotes, long restNanos, long gapNanos, int raceOneIn) {
        /** One quote resting half a second, then half a second idle: what a quiet stream does. */
        public static Shape trickle() {
            return new Shape(1, TimeUnit.MILLISECONDS.toNanos(500), TimeUnit.MILLISECONDS.toNanos(500), 4);
        }

        /** Ten sessions, about 18 orders a second in all: a busy account well inside its budget. */
        public static Shape typical() {
            return new Shape(1, TimeUnit.MILLISECONDS.toNanos(300), TimeUnit.MILLISECONDS.toNanos(200), 10);
        }

        /** Thirty sessions, about 42 orders a second in all: just past Polymarket International's 36 a second. */
        public static Shape saturated() {
            return new Shape(1, TimeUnit.MILLISECONDS.toNanos(500), TimeUnit.MILLISECONDS.toNanos(150), 10);
        }
    }

    /** Who the sessions are and where their gateway is. */
    public record Spec(
            String sessionIdPrefix,
            long slotBase,
            int strategyId,
            String gatewayKey,
            int exchangeId,
            int accountId,
            long[] securityIds,
            long price,
            long size,
            Shape shape,
            String gatewayHost,
            int gatewayPort,
            String replyHost) {}

    /** Exact samples, nanoseconds. */
    public static final class Samples {
        private final long[] values;
        private int count;

        Samples(final int capacity) {
            this.values = new long[capacity];
        }

        synchronized void add(final long nanos) {
            if (this.count < this.values.length) {
                this.values[this.count++] = nanos;
            }
        }

        public synchronized long[] sorted() {
            final long[] copy = Arrays.copyOf(this.values, this.count);
            Arrays.sort(copy);
            return copy;
        }

        public synchronized void clear() {
            this.count = 0;
        }
    }

    private static final int NONE = 0;
    private static final int SENT = 1;
    private static final int RESTING = 2;
    private static final int CANCELLING = 3;
    private static final int PAUSED = 4;
    private static final int SAMPLES = 1 << 20;
    // After a refusal a quote waits this long before trying again, as the OMS cools down after one.
    private static final long REJECT_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    /** One quote's place in its cycle. */
    private static final class Quote {
        int state = NONE;
        long counter;
        long sentAt;
        long ackedAt;
        long cancelSentAt;
        boolean raced;
        long pausedUntil;
    }

    private static final class Session {
        final long slot;
        final long[] securities;
        final GatewayLink link;
        final GatewayClient client;
        final GatewayLinks links;
        final SequencedRingBuffer<Intent> omsOrders;
        final SequencedPoller omsReader;
        final Quote[] quotes;
        long nextCounter = 1;

        Session(
                final long slot,
                final long[] securities,
                final GatewayLink link,
                final GatewayClient client,
                final GatewayLinks links,
                final SequencedRingBuffer<Intent> omsOrders,
                final SequencedPoller omsReader,
                final int quotes) {
            this.slot = slot;
            this.securities = securities;
            this.link = link;
            this.client = client;
            this.links = links;
            this.omsOrders = omsOrders;
            this.omsReader = omsReader;
            this.quotes = new Quote[quotes];
            for (int i = 0; i < quotes; i++) {
                this.quotes[i] = new Quote();
            }
        }

        Quote quote(final long counter) {
            for (final Quote quote : this.quotes) {
                if (quote.counter == counter && quote.state != NONE && quote.state != PAUSED) {
                    return quote;
                }
            }
            return null;
        }
    }

    private final Spec spec;
    private final List<Session> sessions = new ArrayList<>();
    private final ScheduledExecutorService linkThread = Executors.newSingleThreadScheduledExecutor();
    private final Thread driver;
    private final Order order = new Order();
    private final CancelOrder cancel = new CancelOrder();
    private volatile boolean quoting;
    private volatile boolean running = true;
    private volatile Throwable failure;

    public final Samples orderToNew = new Samples(SAMPLES);
    public final Samples cancelToEnd = new Samples(SAMPLES);
    public final Samples racedCancelToEnd = new Samples(SAMPLES);
    private final Map<String, Long> outcomes = new LinkedHashMap<>();
    // The first raced orders' reports, with when each came after the order was sent, to see how a race ended.
    private final List<String> racedTraces = new ArrayList<>();
    private static final int TRACED = 8;
    private final Map<RejectReason, Long> rejects = new EnumMap<>(RejectReason.class);

    public BenchSessions(final AeronRuntime aeron, final Spec spec, final int count) {
        this.spec = spec;
        for (int i = 0; i < count; i++) {
            this.sessions.add(session(aeron, spec.sessionIdPrefix() + i, spec.slotBase() + i));
        }
        for (final Session session : this.sessions) {
            this.linkThread.scheduleWithFixedDelay(session.links, 0, 50, TimeUnit.MILLISECONDS);
        }
        this.driver = new Thread(this::drive, "bench-sessions");
        this.driver.setDaemon(true);
        this.driver.start();
    }

    public boolean allReady() {
        for (final Session session : this.sessions) {
            if (session.link.state != GatewayLink.READY) {
                return false;
            }
        }
        return true;
    }

    public void startQuoting() {
        this.quoting = true;
    }

    /** Stops new orders; what rests is cancelled. */
    public void stopQuoting() {
        this.quoting = false;
    }

    public Throwable failure() {
        return this.failure;
    }

    /** Orders still working, as the sessions know them. */
    public synchronized int working() {
        int working = 0;
        for (final Session session : this.sessions) {
            for (final Quote quote : session.quotes) {
                if (quote.state == SENT || quote.state == RESTING || quote.state == CANCELLING) {
                    working++;
                }
            }
        }
        return working;
    }

    public synchronized List<String> racedTraces() {
        return new ArrayList<>(this.racedTraces);
    }

    public synchronized Map<String, Long> outcomes() {
        final Map<String, Long> copy = new LinkedHashMap<>(this.outcomes);
        this.rejects.forEach((reason, n) -> copy.put("reject " + reason, n));
        return copy;
    }

    /** Empties the samples and counts, after a warm-up. */
    public synchronized void reset() {
        this.orderToNew.clear();
        this.cancelToEnd.clear();
        this.racedCancelToEnd.clear();
        this.outcomes.clear();
        this.rejects.clear();
    }

    private Session session(final AeronRuntime aeron, final String sessionId, final long slot) {
        final GlobalSequence sequence = new GlobalSequence();
        final SequencedRingBuffer<Intent> omsOrders = new SequencedRingBuffer<>(Intent::new, sequence);
        final SequencedRingBuffer<OrderExecutionReport> toOms =
                new SequencedRingBuffer<>(OrderExecutionReport::new, sequence);
        final Session[] holder = new Session[1];
        final OrderExecutionReport report = new OrderExecutionReport();
        final SequencedPoller reader = toOms.createPoller((seq, templateId, buffer, length) -> {
            report.buffer.putBytes(0, buffer, 0, length);
            onReport(holder[0], report);
        });
        final List<long[]> listings = new ArrayList<>();
        for (final long securityId : this.spec.securityIds()) {
            listings.add(new long[] {this.spec.exchangeId(), securityId});
        }
        final GatewayLink link =
                new GatewayLink(this.spec.gatewayKey(), this.spec.exchangeId(), this.spec.accountId(), listings, 1024);
        final GatewayClient client = new GatewayClient(
                List.of(link),
                omsOrders,
                toOms,
                new GatewayClient.Identity(slot, sessionId, this.spec.strategyId(), 5_000, 50),
                1024,
                System::nanoTime,
                SampledEpochNanoClock.INSTANCE);
        final GatewayLinks links = new GatewayLinks(
                new NullLogger(),
                aeron.aeron(),
                this.spec.replyHost(),
                List.of(link),
                key -> new GatewayLinks.Endpoint(this.spec.gatewayHost(), this.spec.gatewayPort(), 0),
                System::nanoTime);
        holder[0] = new Session(
                slot,
                this.spec.securityIds(),
                link,
                client,
                links,
                omsOrders,
                reader,
                this.spec.shape().quotes());
        return holder[0];
    }

    private void drive() {
        try {
            while (this.running) {
                for (final Session session : this.sessions) {
                    session.client.doWork();
                    session.omsReader.poll();
                    if (session.link.state == GatewayLink.READY) {
                        cycle(session, System.nanoTime());
                    }
                }
                Thread.onSpinWait();
            }
        } catch (final Throwable t) {
            this.failure = t;
        }
    }

    private synchronized void cycle(final Session session, final long now) {
        final Shape shape = this.spec.shape();
        for (final Quote quote : session.quotes) {
            if (quote.state == PAUSED && now - quote.pausedUntil >= 0) {
                quote.state = NONE;
            }
            if (quote.state == NONE && this.quoting) {
                quote.counter = session.nextCounter++;
                quote.state = SENT;
                quote.raced =
                        shape.raceOneIn() > 0 && ThreadLocalRandom.current().nextInt(shape.raceOneIn()) == 0;
                quote.sentAt = System.nanoTime();
                sendOrder(session, quote.counter);
                if (quote.raced) {
                    quote.cancelSentAt = System.nanoTime();
                    sendCancel(session, quote.counter);
                    quote.state = CANCELLING;
                }
            } else if (quote.state == RESTING && (!this.quoting || now - quote.ackedAt >= shape.restNanos())) {
                quote.state = CANCELLING;
                quote.cancelSentAt = System.nanoTime();
                sendCancel(session, quote.counter);
            }
        }
    }

    private synchronized void onReport(final Session session, final OrderExecutionReport report) {
        final long now = System.nanoTime();
        final Quote quote = session.quote(report.getClientOidCounter());
        if (quote == null) {
            return;
        }
        final ExecType type = report.decoder.execType();
        if (quote.raced && this.racedTraces.size() < TRACED * 4) {
            this.racedTraces.add(quote.counter + ":" + type
                    + (type == ExecType.CANCEL_REJECT || type == ExecType.REJECT
                            ? "/" + report.decoder.rejectReason()
                            : "")
                    + "@" + (now - quote.sentAt) / 1_000 + "us");
        }
        switch (type) {
            case NEW -> {
                if (!quote.raced) {
                    this.orderToNew.add(now - quote.sentAt);
                }
                quote.ackedAt = now;
                if (quote.state == SENT) {
                    quote.state = RESTING;
                }
            }
            case CANCEL, EXPIRE, FILL, REJECT -> {
                if (type == ExecType.REJECT) {
                    this.rejects.merge(report.decoder.rejectReason(), 1L, Long::sum);
                }
                if (quote.state == CANCELLING && (type == ExecType.CANCEL || type == ExecType.EXPIRE)) {
                    (quote.raced ? this.racedCancelToEnd : this.cancelToEnd).add(now - quote.cancelSentAt);
                }
                count((quote.raced ? "raced " : "") + type);
                quote.state = PAUSED;
                quote.pausedUntil = now
                        + Math.max(this.spec.shape().gapNanos(), type == ExecType.REJECT ? REJECT_BACKOFF_NANOS : 0);
            }
            case CANCEL_REJECT -> {
                count("cancel reject " + report.decoder.rejectReason());
                // Tried again once it has rested once more.
                quote.state = RESTING;
                quote.ackedAt = now;
            }
            default -> {}
        }
    }

    private void count(final String outcome) {
        this.outcomes.merge(outcome, 1L, Long::sum);
    }

    private void sendOrder(final Session session, final long counter) {
        this.order
                .encoder
                .exchangeId(this.spec.exchangeId())
                .securityId(securityOf(session, counter))
                .price(this.spec.price())
                .size(this.spec.size())
                .side(Side.Bid)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED)
                .timestampSend(SampledEpochNanoClock.INSTANCE.nanoTime());
        this.order.encoder.flags().clear().postOnly(true);
        this.order.encodeClientOid(counter, this.spec.strategyId());
        session.omsOrders.publishRaw(this.order.buffer, OrderDecoder.TEMPLATE_ID, this.order.totalMessageSize());
    }

    private void sendCancel(final Session session, final long counter) {
        this.cancel
                .encoder
                .exchangeId(this.spec.exchangeId())
                .securityId(securityOf(session, counter))
                .timestampSend(SampledEpochNanoClock.INSTANCE.nanoTime());
        this.cancel.encodeClientOid(counter, this.spec.strategyId());
        session.omsOrders.publishRaw(
                this.cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, this.cancel.totalMessageSize());
    }

    private static long securityOf(final Session session, final long counter) {
        return session.securities[(int) (counter % session.securities.length)];
    }

    @Override
    public void close() throws Exception {
        this.running = false;
        this.driver.join(5_000);
        this.linkThread.shutdownNow();
        for (final Session session : this.sessions) {
            session.links.close();
        }
    }
}
