package group.gnometrading.orchestrator.session;

import group.gnometrading.orchestrator.session.journal.JournalSource;
import group.gnometrading.orchestrator.session.orders.SessionOrders;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.MetricRow;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Every ring buffer the session's agents talk over, all on one session sequence: one total order over everything the
 * strategy saw and did, so a journal replays the session exactly. Every buffer on it is journaled, under its source.
 *
 * @param marketData every listing's market data, which the strategy and the OMS read
 * @param intents the strategy's intents, to the OMS
 * @param strategyExecReports the OMS's reports, to the strategy
 * @param metrics the strategy's metric rows, ordered against what each explains
 * @param latency the heartbeat's latency rows ({@code _latency}), a minute at a time
 * @param orders the OMS's orders, to order entry
 * @param omsExecReports order entry's reports, to the OMS
 */
record SessionBuffers(
        GlobalSequence sequence,
        Map<SequencedRingBuffer<?>, JournalSource> journalSources,
        SequencedRingBuffer<?> marketData,
        SequencedRingBuffer<Intent> intents,
        SequencedRingBuffer<OrderExecutionReport> strategyExecReports,
        SequencedRingBuffer<MetricRow> metrics,
        SequencedRingBuffer<MetricRow> latency,
        SequencedRingBuffer<Intent> orders,
        SequencedRingBuffer<OrderExecutionReport> omsExecReports) {

    /** Each ring's name in the session's samples, in the order of {@link #rings}. */
    List<String> ringNames() {
        return List.of(
                "marketData", "intents", "strategyExecReports", "metrics", "latency", "orders", "omsExecReports");
    }

    List<SequencedRingBuffer<?>> rings() {
        return List.of(marketData, intents, strategyExecReports, metrics, latency, orders, omsExecReports);
    }

    static SessionBuffers create(EpochNanoClock clock) {
        GlobalSequence sequence = new GlobalSequence();
        Map<SequencedRingBuffer<?>, JournalSource> sources = new IdentityHashMap<>();
        SequencedRingBuffer<?> marketData =
                new SequencedRingBuffer<>(Mbp10Schema::new, sequence, SequencedRingBuffer.DEFAULT_SIZE, clock);
        sources.put(marketData, JournalSource.STRATEGY_MARKET_DATA);
        SequencedRingBuffer<Intent> intents =
                new SequencedRingBuffer<>(Intent::new, sequence, SequencedRingBuffer.DEFAULT_SIZE, clock);
        sources.put(intents, JournalSource.STRATEGY_INTENT);
        SequencedRingBuffer<OrderExecutionReport> strategyExecReports = new SequencedRingBuffer<>(
                OrderExecutionReport::new, sequence, SessionOrders.OUTBOUND_BUFFER_SIZE, clock);
        sources.put(strategyExecReports, JournalSource.STRATEGY_EXEC_REPORT);
        SequencedRingBuffer<MetricRow> metrics =
                new SequencedRingBuffer<>(MetricRow::new, sequence, SequencedRingBuffer.DEFAULT_SIZE, clock);
        sources.put(metrics, JournalSource.STRATEGY_METRIC);
        SequencedRingBuffer<MetricRow> latency =
                new SequencedRingBuffer<>(MetricRow::new, sequence, SequencedRingBuffer.DEFAULT_SIZE, clock);
        sources.put(latency, JournalSource.LATENCY_METRIC);
        SequencedRingBuffer<Intent> orders =
                new SequencedRingBuffer<>(Intent::new, sequence, SessionOrders.OUTBOUND_BUFFER_SIZE, clock);
        sources.put(orders, JournalSource.OMS_ORDER);
        SequencedRingBuffer<OrderExecutionReport> omsExecReports = new SequencedRingBuffer<>(
                OrderExecutionReport::new, sequence, SessionOrders.OUTBOUND_BUFFER_SIZE, clock);
        sources.put(omsExecReports, JournalSource.OMS_EXEC_REPORT);
        return new SessionBuffers(
                sequence, sources, marketData, intents, strategyExecReports, metrics, latency, orders, omsExecReports);
    }
}
