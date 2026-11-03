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
import java.util.Map;

/**
 * Every ring buffer the session's agents talk over, all on one session sequence: one total order over everything the
 * strategy saw and did, so a journal replays the session exactly. Every buffer on it is journaled, under its source.
 *
 * @param marketData every listing's market data, which the strategy, the OMS and each simulated exchange read
 * @param intents the strategy's intents, to the OMS
 * @param strategyExecReports the OMS's reports, to the strategy
 * @param metrics the strategy's metric rows, ordered against what each explains
 * @param orders the OMS's orders, to order entry (the router with several listings)
 * @param omsExecReports order entry's reports, to the OMS
 * @param routed the router's per-listing pairs; null with one listing
 */
record SessionBuffers(
        GlobalSequence sequence,
        Map<SequencedRingBuffer<?>, JournalSource> journalSources,
        SequencedRingBuffer<?> marketData,
        SequencedRingBuffer<Intent> intents,
        SequencedRingBuffer<OrderExecutionReport> strategyExecReports,
        SequencedRingBuffer<MetricRow> metrics,
        SequencedRingBuffer<Intent> orders,
        SequencedRingBuffer<OrderExecutionReport> omsExecReports,
        SessionOrders.RoutedBuffers routed) {

    static SessionBuffers create(int listingCount) {
        GlobalSequence sequence = new GlobalSequence();
        Map<SequencedRingBuffer<?>, JournalSource> sources = new IdentityHashMap<>();
        SequencedRingBuffer<?> marketData = new SequencedRingBuffer<>(Mbp10Schema::new, sequence);
        sources.put(marketData, JournalSource.STRATEGY_MARKET_DATA);
        SequencedRingBuffer<Intent> intents = new SequencedRingBuffer<>(Intent::new, sequence);
        sources.put(intents, JournalSource.STRATEGY_INTENT);
        SequencedRingBuffer<OrderExecutionReport> strategyExecReports =
                new SequencedRingBuffer<>(OrderExecutionReport::new, sequence, SessionOrders.OUTBOUND_BUFFER_SIZE);
        sources.put(strategyExecReports, JournalSource.STRATEGY_EXEC_REPORT);
        SequencedRingBuffer<MetricRow> metrics = new SequencedRingBuffer<>(MetricRow::new, sequence);
        sources.put(metrics, JournalSource.STRATEGY_METRIC);
        SequencedRingBuffer<Intent> orders =
                new SequencedRingBuffer<>(Intent::new, sequence, SessionOrders.OUTBOUND_BUFFER_SIZE);
        sources.put(orders, JournalSource.OMS_ORDER);
        SequencedRingBuffer<OrderExecutionReport> omsExecReports =
                new SequencedRingBuffer<>(OrderExecutionReport::new, sequence, SessionOrders.OUTBOUND_BUFFER_SIZE);
        sources.put(omsExecReports, JournalSource.OMS_EXEC_REPORT);
        SessionOrders.RoutedBuffers routed = SessionOrders.routedBuffers(listingCount, sequence, sources);
        return new SessionBuffers(
                sequence, sources, marketData, intents, strategyExecReports, metrics, orders, omsExecReports, routed);
    }
}
