package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lmax.disruptor.EventFactory;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.JournalReader;
import group.gnometrading.sequencer.JournalWriter;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalManagerAgentTest {

    private static final long FILE_SIZE = 1024L * 1024L;

    @TempDir
    Path tempDir;

    @Test
    void journalsMarketDataAndEveryTradingBufferInOneOrderWithoutGaps() throws Exception {
        GlobalSequence sessionSequence = new GlobalSequence();
        Map<SequencedRingBuffer<?>, JournalSource> sources = new IdentityHashMap<>();
        SequencedRingBuffer<Mbp10Schema> marketData =
                buffer(Mbp10Schema::new, sessionSequence, sources, JournalSource.STRATEGY_MARKET_DATA);
        SequencedRingBuffer<Intent> omsOrders = buffer(Intent::new, sessionSequence, sources, JournalSource.OMS_ORDER);
        SequencedRingBuffer<Intent> routedOrders =
                buffer(Intent::new, sessionSequence, sources, JournalSource.ROUTED_ORDER);
        SequencedRingBuffer<OrderExecutionReport> routedReports =
                buffer(OrderExecutionReport::new, sessionSequence, sources, JournalSource.ROUTED_EXEC_REPORT);
        SequencedRingBuffer<OrderExecutionReport> omsReports =
                buffer(OrderExecutionReport::new, sessionSequence, sources, JournalSource.OMS_EXEC_REPORT);
        TradingOrchestrator.verifyJournalCoverage(sessionSequence, sources);

        Path path = tempDir.resolve("session.journal");
        JournalWriter writer = new JournalWriter(path, FILE_SIZE);
        JournalManagerAgent agent = agent(writer, path, sessionSequence, sources);

        // A book update, then one order's round trip through a multi-listing session: OMS order, the router's copy
        // to the venue, the venue's report, and the router's copy of that back to the OMS.
        publish(marketData);
        publish(omsOrders);
        publish(routedOrders);
        publish(routedReports);
        publish(omsReports);
        assertEquals(5, agent.doWork());
        agent.onClose();

        List<Integer> recordedSources = new ArrayList<>();
        JournalReader reader = new JournalReader(path);
        reader.readAll((seq, sourceId, templateId, buffer, length) -> recordedSources.add(sourceId));

        assertEquals(
                List.of(
                        JournalSource.STRATEGY_MARKET_DATA.id(),
                        JournalSource.OMS_ORDER.id(),
                        JournalSource.ROUTED_ORDER.id(),
                        JournalSource.ROUTED_EXEC_REPORT.id(),
                        JournalSource.OMS_EXEC_REPORT.id()),
                recordedSources);
        assertEquals(5, reader.lastReadSequence());
    }

    @Test
    void closeJournalsWhatWasPublishedAfterTheLastPass() throws Exception {
        GlobalSequence sessionSequence = new GlobalSequence();
        Map<SequencedRingBuffer<?>, JournalSource> sources = new IdentityHashMap<>();
        SequencedRingBuffer<Intent> intents =
                buffer(Intent::new, sessionSequence, sources, JournalSource.STRATEGY_INTENT);
        Path path = tempDir.resolve("closing.journal");
        JournalManagerAgent agent = agent(new JournalWriter(path, FILE_SIZE), path, sessionSequence, sources);

        publish(intents);
        publish(intents);
        agent.onClose();

        JournalReader reader = new JournalReader(path);
        reader.readAll((seq, sourceId, templateId, buffer, length) -> {});
        assertEquals(2, reader.lastReadSequence());
    }

    @Test
    void aTradingBufferWithoutAJournalSourceIsRejectedAtWiring() {
        GlobalSequence sessionSequence = new GlobalSequence();
        Map<SequencedRingBuffer<?>, JournalSource> sources = new IdentityHashMap<>();
        buffer(Intent::new, sessionSequence, sources, JournalSource.OMS_ORDER);
        new SequencedRingBuffer<>(OrderExecutionReport::new, sessionSequence);

        assertThrows(
                IllegalStateException.class, () -> TradingOrchestrator.verifyJournalCoverage(sessionSequence, sources));
    }

    private static <T extends SbeMessage> SequencedRingBuffer<T> buffer(
            EventFactory<T> factory,
            GlobalSequence sequence,
            Map<SequencedRingBuffer<?>, JournalSource> sources,
            JournalSource source) {
        SequencedRingBuffer<T> buffer = new SequencedRingBuffer<>(factory, sequence);
        sources.put(buffer, source);
        return buffer;
    }

    private static JournalManagerAgent agent(
            JournalWriter writer,
            Path path,
            GlobalSequence sessionSequence,
            Map<SequencedRingBuffer<?>, JournalSource> sources) {
        List<SequencedPoller> pollers = new ArrayList<>();
        for (SequencedRingBuffer<?> buffer : sessionSequence.buffers()) {
            pollers.add(buffer.createPoller(writer.source(sources.get(buffer).id())));
        }
        return new JournalManagerAgent(
                writer, pollers, path, null, "bucket", "key", Duration.ofSeconds(60), new NullLogger());
    }

    private static void publish(SequencedRingBuffer<?> buffer) {
        buffer.claim();
        buffer.publish();
    }
}
