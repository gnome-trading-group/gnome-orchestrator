package group.gnometrading.orchestrator.session;

import group.gnometrading.latency.LatencyGrid;
import group.gnometrading.latency.MinuteHistograms;
import group.gnometrading.oms.OmsAgent;
import group.gnometrading.orchestrator.session.orders.GatewayClient;
import group.gnometrading.schemas.TriggerKind;
import group.gnometrading.sm.Listing;
import group.gnometrading.strategies.StrategyAgent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The session's latency histograms: one {@link MinuteHistograms} per thread that records (strategy, OMS, order
 * entry), each over the exchanges the session trades. Values are nanoseconds on the session host's wall clock, so
 * every metric here compares stamps from one clock.
 *
 * <p>Metric codes name the metric in numeric records (the journal's {@code _latency} rows); never reuse one.
 */
public final class SessionLatency {

    public static final int STRAT_QUEUE = 1;
    public static final int STRAT_DECISION = 2;
    public static final int OMS_PROCESS = 3;
    public static final int TICK_TO_ORDER = 4;
    public static final int ORDER_TO_ACK = 5;
    public static final int CANCEL_TO_ACK = 6;
    public static final int STRAT_TRANSIT = 7;

    private static final String[] NO_LABEL = {""};

    private final MinuteHistograms strategy;
    private final MinuteHistograms oms;
    private final MinuteHistograms orders;
    private final LatencyGrid strategyTransit;
    private final LatencyGrid strategyQueue;
    private final LatencyGrid strategyDecision;
    private final LatencyGrid omsProcess;
    private final LatencyGrid tickToOrder;
    private final LatencyGrid orderToAck;
    private final LatencyGrid cancelToAck;

    private SessionLatency(final int[] exchangeIds, final String[] exchangeNames) {
        final String[] kinds = kindLabels();
        final int exchanges = exchangeIds.length;
        final int actions = OmsAgent.LATENCY_ACTIONS.length;
        this.strategy = new MinuteHistograms(3 * LatencyGrid.size(exchanges, 1, kinds.length));
        this.strategyTransit =
                new LatencyGrid(strategy, STRAT_TRANSIT, "strat.transit", exchangeIds, exchangeNames, NO_LABEL, kinds);
        this.strategyQueue =
                new LatencyGrid(strategy, STRAT_QUEUE, "strat.queue", exchangeIds, exchangeNames, NO_LABEL, kinds);
        this.strategyDecision = new LatencyGrid(
                strategy, STRAT_DECISION, "strat.decision", exchangeIds, exchangeNames, NO_LABEL, kinds);
        this.oms = new MinuteHistograms(LatencyGrid.size(exchanges, actions, kinds.length));
        this.omsProcess = new LatencyGrid(
                oms, OMS_PROCESS, "oms.process", exchangeIds, exchangeNames, OmsAgent.LATENCY_ACTIONS, kinds);
        this.orders =
                new MinuteHistograms(LatencyGrid.size(exchanges, GatewayClient.LATENCY_ACTIONS.length, kinds.length)
                        + 2 * LatencyGrid.size(exchanges, 1, 1));
        this.tickToOrder = new LatencyGrid(
                orders,
                TICK_TO_ORDER,
                "session.tick_to_order",
                exchangeIds,
                exchangeNames,
                GatewayClient.LATENCY_ACTIONS,
                kinds);
        this.orderToAck =
                new LatencyGrid(orders, ORDER_TO_ACK, "order_to_ack", exchangeIds, exchangeNames, NO_LABEL, NO_LABEL);
        this.cancelToAck =
                new LatencyGrid(orders, CANCEL_TO_ACK, "cancel_to_ack", exchangeIds, exchangeNames, NO_LABEL, NO_LABEL);
    }

    /** Over the exchanges these listings trade on, by exchange code. */
    static SessionLatency over(final List<Listing> listings) {
        final Map<Integer, String> exchanges = new LinkedHashMap<>();
        for (Listing listing : listings) {
            exchanges.putIfAbsent(
                    listing.exchange().exchangeId(), listing.exchange().exchangeCode());
        }
        final int[] ids = new int[exchanges.size()];
        final String[] names = new String[exchanges.size()];
        int index = 0;
        for (Map.Entry<Integer, String> exchange : exchanges.entrySet()) {
            ids[index] = exchange.getKey();
            names[index] = exchange.getValue();
            index++;
        }
        return new SessionLatency(ids, names);
    }

    /** {@link TriggerKind} names by value, so a kind's value is its code. */
    static String[] kindLabels() {
        int size = 0;
        for (TriggerKind kind : TriggerKind.values()) {
            if (kind != TriggerKind.NULL_VAL) {
                size = Math.max(size, kind.value() + 1);
            }
        }
        final String[] labels = new String[size];
        Arrays.fill(labels, "");
        for (TriggerKind kind : TriggerKind.values()) {
            if (kind != TriggerKind.NULL_VAL) {
                labels[kind.value()] = kind.name();
            }
        }
        return labels;
    }

    void measure(final GatewayClient client) {
        client.measureLatency(tickToOrder, orderToAck, cancelToAck);
    }

    void measure(final OmsAgent agent) {
        agent.measureProcessing(omsProcess);
    }

    void measure(final StrategyAgent agent) {
        agent.measureLatency(strategyTransit, strategyQueue, strategyDecision);
    }

    /** Each recording thread's histograms, for the heartbeat to read. */
    public List<MinuteHistograms> histograms() {
        return List.of(strategy, oms, orders);
    }

    /** The sample kind of each of {@link #histograms}, in the same order. */
    public List<String> kinds() {
        return List.of("latency.strategy", "latency.oms", "latency.orders");
    }
}
