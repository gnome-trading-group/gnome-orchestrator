package group.gnometrading.orchestrator.session.orders;

import group.gnometrading.latency.LatencyHistogram;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.agrona.concurrent.SystemEpochNanoClock;

/**
 * Many sessions quoting through one order gateway, each a real {@link GatewayClient} over Aeron, all driven from one
 * thread: each keeps {@code quotes} post-only bids working, cancels each once it has rested {@code restNanos}, and puts
 * a new one in the place of any that ends, is refused or is cancelled, so every session always has new orders waiting
 * on the account's budget. Counts what each session was told: acknowledged, refused (by reason), cancelled, filled.
 */
final class LoadSessions implements AutoCloseable {

    /**
     * What every session trades and how.
     *
     * @param slotBase the first session's slot; the rest follow
     * @param maxQueueMs how long each session's new orders may wait for the budget
     */
    record Spec(
            String sessionIdPrefix,
            long slotBase,
            int strategyId,
            String gatewayKey,
            int exchangeId,
            long securityId,
            int accountId,
            long price,
            long size,
            int quotes,
            long restNanos,
            int maxQueueMs,
            String gatewayHost,
            int gatewayPort) {}

    /** One session and what it was told. */
    static final class Session {
        final String sessionId;
        final long slot;
        final GatewayLink link;
        final GatewayClient client;
        final GatewayLinks links;
        final SequencedRingBuffer<Intent> omsOrders;
        final SequencedPoller omsReader;
        // By counter: when it was acknowledged (0 until then), or -1 once its cancel was sent.
        final Map<Long, Long> working = new HashMap<>();
        // By counter, for each order still working: every report it was told, for a stuck order's post-mortem.
        final Map<Long, List<String>> heard = new HashMap<>();
        // By counter: when its order, then its cancel, was sent, for how long the session waited to hear of each.
        final Map<Long, Long> orderSentAt = new HashMap<>();
        final Map<Long, Long> cancelSentAt = new HashMap<>();
        long nextCounter = 1;
        long backoffUntilNanos;
        volatile long acks;
        volatile long cancels;
        volatile long fills;
        final Map<RejectReason, Long> rejects = new EnumMap<>(RejectReason.class);

        private Session(
                final String sessionId,
                final long slot,
                final GatewayLink link,
                final GatewayClient client,
                final GatewayLinks links,
                final SequencedRingBuffer<Intent> omsOrders,
                final SequencedPoller omsReader) {
            this.sessionId = sessionId;
            this.slot = slot;
            this.link = link;
            this.client = client;
            this.links = links;
            this.omsOrders = omsOrders;
            this.omsReader = omsReader;
        }

        boolean ready() {
            return this.link.state == GatewayLink.READY;
        }

        synchronized long rejects(final RejectReason reason) {
            return this.rejects.getOrDefault(reason, 0L);
        }
    }

    // After a refusal a session waits this long before trying again, as the OMS cools down after one.
    private static final long REJECT_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    private final Spec spec;
    private final List<Session> sessions = new ArrayList<>();
    private final ScheduledExecutorService linkThread = Executors.newSingleThreadScheduledExecutor();
    private final Thread driver;
    private volatile boolean quoting;
    private volatile boolean running = true;
    private volatile Throwable failure;
    private final Consumer<OrderExecutionReport> listener;
    // Across every session: from an order's send to its acknowledgement, and from a cancel's send to its confirmation.
    private final LatencyHistogram ackWaits = new LatencyHistogram();
    private final LatencyHistogram cancelWaits = new LatencyHistogram();

    LoadSessions(final AeronRuntime aeron, final Spec spec, final int count) {
        this(aeron, spec, count, report -> {});
    }

    /** @param listener hears every report any session gets, on the sessions' thread */
    LoadSessions(
            final AeronRuntime aeron, final Spec spec, final int count, final Consumer<OrderExecutionReport> listener) {
        this.spec = spec;
        this.listener = listener;
        for (int i = 0; i < count; i++) {
            this.sessions.add(session(aeron, spec.sessionIdPrefix() + i, spec.slotBase() + i));
        }
        for (final Session session : this.sessions) {
            this.linkThread.scheduleWithFixedDelay(session.links, 0, 50, TimeUnit.MILLISECONDS);
        }
        this.driver = new Thread(this::drive, "load-sessions");
        this.driver.setDaemon(true);
        this.driver.start();
    }

    List<Session> sessions() {
        return this.sessions;
    }

    boolean allReady() {
        return this.sessions.stream().allMatch(Session::ready);
    }

    void startQuoting() {
        this.quoting = true;
    }

    /** Stops new orders; what is working is cancelled. */
    void stopQuoting() {
        this.quoting = false;
    }

    /** Orders still working across every session, as their sessions know them. */
    int working() {
        int working = 0;
        for (final Session session : this.sessions) {
            synchronized (session) {
                working += session.working.size();
            }
        }
        return working;
    }

    /** Each session's orders still working, by counter: 0 not yet acknowledged, -1 cancel sent, else acked at. */
    String describeWorking() {
        final StringBuilder text = new StringBuilder();
        for (final Session session : this.sessions) {
            synchronized (session) {
                if (!session.working.isEmpty()) {
                    text.append(session.slot)
                            .append('=')
                            .append(session.working)
                            .append(" heard ")
                            .append(session.heard)
                            .append(' ');
                }
            }
        }
        return text.toString();
    }

    /** How long sessions waited to hear their orders acknowledged and their cancels confirmed, p50 and p99. */
    synchronized String describeHearing() {
        return String.format(
                "acknowledged in p50 %d us, p99 %d us (%d); cancels confirmed in p50 %d us, p99 %d us (%d)",
                this.ackWaits.percentile(0.5),
                this.ackWaits.percentile(0.99),
                this.ackWaits.count(),
                this.cancelWaits.percentile(0.5),
                this.cancelWaits.percentile(0.99),
                this.cancelWaits.count());
    }

    private synchronized void heardAfter(final LatencyHistogram waits, final Long sentAt) {
        if (sentAt != null) {
            waits.record(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - sentAt));
        }
    }

    Throwable failure() {
        return this.failure;
    }

    private Session session(final AeronRuntime aeron, final String sessionId, final long slot) {
        final GlobalSequence sequence = new GlobalSequence();
        final SequencedRingBuffer<Intent> omsOrders = new SequencedRingBuffer<>(Intent::new, sequence);
        final SequencedRingBuffer<OrderExecutionReport> toOms =
                new SequencedRingBuffer<>(OrderExecutionReport::new, sequence);
        final Session[] holder = new Session[1];
        final SequencedPoller reader = toOms.createPoller((seq, templateId, buffer, length) -> {
            final OrderExecutionReport report = new OrderExecutionReport();
            report.buffer.putBytes(0, buffer, 0, length);
            onReport(holder[0], report);
        });
        final GatewayLink link = new GatewayLink(
                this.spec.gatewayKey(),
                this.spec.exchangeId(),
                this.spec.accountId(),
                List.<long[]>of(new long[] {this.spec.exchangeId(), this.spec.securityId()}),
                1024);
        final GatewayClient client = new GatewayClient(
                List.of(link),
                omsOrders,
                toOms,
                new GatewayClient.Identity(slot, sessionId, this.spec.strategyId(), 5_000, this.spec.maxQueueMs()),
                1024,
                System::nanoTime,
                new SystemEpochNanoClock());
        final GatewayLinks links = new GatewayLinks(
                new NullLogger(),
                aeron.aeron(),
                "127.0.0.1",
                List.of(link),
                key -> new GatewayLinks.Endpoint(this.spec.gatewayHost(), this.spec.gatewayPort()),
                System::nanoTime);
        holder[0] = new Session(sessionId, slot, link, client, links, omsOrders, reader);
        return holder[0];
    }

    private void drive() {
        try {
            while (this.running) {
                final long now = System.nanoTime();
                for (final Session session : this.sessions) {
                    session.client.doWork();
                    session.omsReader.poll();
                    if (session.ready()) {
                        quote(session, now);
                    }
                }
                Thread.onSpinWait();
            }
        } catch (final Throwable t) {
            this.failure = t;
        }
    }

    private void quote(final Session session, final long now) {
        synchronized (session) {
            if (this.quoting && now - session.backoffUntilNanos >= 0) {
                while (session.working.size() < this.spec.quotes()) {
                    final long counter = session.nextCounter++;
                    session.working.put(counter, 0L);
                    session.orderSentAt.put(counter, now);
                    order(session, counter);
                }
            }
            for (final Map.Entry<Long, Long> entry : session.working.entrySet()) {
                final long ackedAt = entry.getValue();
                if (ackedAt > 0 && (!this.quoting || now - ackedAt >= this.spec.restNanos())) {
                    entry.setValue(-1L);
                    session.cancelSentAt.put(entry.getKey(), now);
                    cancel(session, entry.getKey());
                }
            }
        }
    }

    private void onReport(final Session session, final OrderExecutionReport report) {
        this.listener.accept(report);
        final long counter = report.getClientOidCounter();
        final ExecType type = report.decoder.execType();
        synchronized (session) {
            if (session.working.containsKey(counter)) {
                session.heard
                        .computeIfAbsent(counter, c -> new ArrayList<>())
                        .add(
                                type == ExecType.REJECT || type == ExecType.CANCEL_REJECT
                                        ? type + ":" + report.decoder.rejectReason()
                                        : type.toString());
            }
            switch (type) {
                case NEW -> {
                    session.acks++;
                    heardAfter(this.ackWaits, session.orderSentAt.remove(counter));
                    session.working.computeIfPresent(counter, (c, at) -> at == 0 ? System.nanoTime() : at);
                }
                case REJECT -> {
                    forget(session, counter);
                    session.rejects.merge(report.decoder.rejectReason(), 1L, Long::sum);
                    session.backoffUntilNanos = System.nanoTime() + REJECT_BACKOFF_NANOS;
                }
                case CANCEL, EXPIRE -> {
                    session.cancels++;
                    heardAfter(this.cancelWaits, session.cancelSentAt.remove(counter));
                    forget(session, counter);
                }
                case FILL -> {
                    session.fills++;
                    forget(session, counter);
                }
                case PARTIAL_FILL -> session.fills++;
                case CANCEL_REJECT -> {
                    if (report.decoder.rejectReason() == RejectReason.UNKNOWN_ORDER) {
                        forget(session, counter);
                    } else {
                        // Tried again once it has rested its time once more.
                        session.working.computeIfPresent(counter, (c, at) -> System.nanoTime());
                    }
                }
                default -> {}
            }
        }
    }

    private static void forget(final Session session, final long counter) {
        session.working.remove(counter);
        session.heard.remove(counter);
        session.orderSentAt.remove(counter);
        session.cancelSentAt.remove(counter);
    }

    private void order(final Session session, final long counter) {
        final Order order = new Order();
        order.encoder
                .exchangeId(this.spec.exchangeId())
                .securityId(this.spec.securityId())
                .price(this.spec.price())
                .size(this.spec.size())
                .side(Side.Bid)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        order.encoder.flags().postOnly(true);
        order.encodeClientOid(counter, this.spec.strategyId());
        session.omsOrders.publishRaw(order.buffer, OrderDecoder.TEMPLATE_ID, order.totalMessageSize());
    }

    private void cancel(final Session session, final long counter) {
        final CancelOrder cancel = new CancelOrder();
        cancel.encoder.exchangeId(this.spec.exchangeId()).securityId(this.spec.securityId());
        cancel.encodeClientOid(counter, this.spec.strategyId());
        session.omsOrders.publishRaw(cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, cancel.totalMessageSize());
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
