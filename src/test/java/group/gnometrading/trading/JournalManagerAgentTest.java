package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lmax.disruptor.EventFactory;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.JournalReader;
import group.gnometrading.sequencer.JournalRecordHandler;
import group.gnometrading.sequencer.JournalWriter;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.agrona.concurrent.CachedEpochClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalManagerAgentTest {

    private static final long SEGMENT_SIZE = 1024L * 1024L;
    private static final Duration SEGMENT_INTERVAL = Duration.ofMinutes(5);

    @TempDir
    Path tempDir;

    private final CachedEpochClock clock = new CachedEpochClock();

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
        JournalManagerAgent agent = agent(sessionSequence, sources);
        agent.onStart();

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
        long last = readSealed((seq, sourceId, templateId, buffer, length) -> recordedSources.add(sourceId));

        assertEquals(
                List.of(
                        JournalSource.STRATEGY_MARKET_DATA.id(),
                        JournalSource.OMS_ORDER.id(),
                        JournalSource.ROUTED_ORDER.id(),
                        JournalSource.ROUTED_EXEC_REPORT.id(),
                        JournalSource.OMS_EXEC_REPORT.id()),
                recordedSources);
        assertEquals(5, last);
    }

    @Test
    void closeJournalsWhatWasPublishedAfterTheLastPass() throws Exception {
        GlobalSequence sessionSequence = new GlobalSequence();
        Map<SequencedRingBuffer<?>, JournalSource> sources = new IdentityHashMap<>();
        SequencedRingBuffer<Intent> intents =
                buffer(Intent::new, sessionSequence, sources, JournalSource.STRATEGY_INTENT);
        JournalManagerAgent agent = agent(sessionSequence, sources);
        agent.onStart();

        publish(intents);
        publish(intents);
        agent.onClose();

        assertEquals(2, readSealed((seq, sourceId, templateId, buffer, length) -> {}));
    }

    @Test
    void aSegmentIsSealedForShippingEveryIntervalWhileTheSessionRuns() throws Exception {
        GlobalSequence sessionSequence = new GlobalSequence();
        Map<SequencedRingBuffer<?>, JournalSource> sources = new IdentityHashMap<>();
        SequencedRingBuffer<Intent> intents =
                buffer(Intent::new, sessionSequence, sources, JournalSource.STRATEGY_INTENT);
        JournalManagerAgent agent = agent(sessionSequence, sources);
        agent.onStart();

        publish(intents);
        agent.doWork();
        clock.advance(SEGMENT_INTERVAL.toMillis() - 1);
        agent.doWork();
        assertTrue(sealedSegments().isEmpty(), "nothing is sealed before the interval is up");

        clock.advance(1);
        agent.doWork();
        assertTrue(awaitSealed(1), "the first segment is sealed once the interval is up");
        assertFalse(Files.exists(tempDir.resolve(JournalWriter.segmentFileName(0))));

        // An idle interval seals nothing; the next segment holds only what came after the first.
        clock.advance(SEGMENT_INTERVAL.toMillis());
        agent.doWork();
        publish(intents);
        agent.onClose();
        assertEquals(
                List.of(
                        tempDir.resolve(JournalWriter.sealedFileName(0)),
                        tempDir.resolve(JournalWriter.sealedFileName(1))),
                sealedSegments());
        assertEquals(2, readSealed((seq, sourceId, templateId, buffer, length) -> {}));
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

    private JournalManagerAgent agent(
            GlobalSequence sessionSequence, Map<SequencedRingBuffer<?>, JournalSource> sources) throws IOException {
        JournalSegmentSealer sealer = new JournalSegmentSealer(new NullLogger());
        JournalWriter writer = new JournalWriter(tempDir, SEGMENT_SIZE, sealer::submit);
        List<SequencedPoller> pollers = new ArrayList<>();
        for (SequencedRingBuffer<?> buffer : sessionSequence.buffers()) {
            pollers.add(buffer.createPoller(writer.source(sources.get(buffer).id())));
        }
        return new JournalManagerAgent(
                writer, pollers, sealer, SEGMENT_INTERVAL, Duration.ofSeconds(10), clock, new NullLogger());
    }

    /** Reads every sealed segment in order, as the session's journal is read once shipped; returns the last sequence. */
    private long readSealed(JournalRecordHandler handler) throws IOException {
        long previous = 0;
        for (Path segment : sealedSegments()) {
            JournalReader reader = new JournalReader(segment, previous);
            reader.readAll(handler);
            previous = reader.lastReadSequence();
        }
        return previous;
    }

    private List<Path> sealedSegments() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".done"))
                    .sorted()
                    .toList();
        }
    }

    private boolean awaitSealed(int count) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (sealedSegments().size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return sealedSegments().size() == count;
    }

    private static void publish(SequencedRingBuffer<?> buffer) {
        buffer.claim();
        buffer.publish();
    }
}
