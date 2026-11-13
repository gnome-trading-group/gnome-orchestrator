package group.gnometrading.orchestrator.gateway.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.gateway.accounts.LaneAdmissions;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.gateway.AdmissionAction;
import group.gnometrading.schemas.gateway.AdmissionRecordDecoder;
import group.gnometrading.schemas.gateway.MessageHeaderDecoder;
import group.gnometrading.sequencer.JournalReader;
import group.gnometrading.sequencer.JournalRecordHandler;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayJournalTest {

    @TempDir
    Path directory;

    private record Record(long sequence, int source, int templateId, long slot) {}

    @Test
    void ingressAndEgressAreJournaledInOneOrderWithEachSessionsSlot() throws Exception {
        final GatewayJournal journal =
                new GatewayJournal(new NullLogger(), this.directory, 1 << 20, Long.MAX_VALUE, System::nanoTime, 0);
        final JournalRecorder ingress = journal.ingress();
        final JournalRecorder egress = journal.egress();
        final OrderExecutionReport report = report();
        ingress.record(7, report.buffer, 0, report.totalMessageSize());
        egress.record(9, report.buffer, 0, report.totalMessageSize());
        ingress.record(0, report.buffer, 0, report.totalMessageSize());
        journal.doWork();
        journal.close();

        final List<Record> records = read(new ArrayList<>());
        assertEquals(
                List.of(
                        new Record(1, GatewayJournal.INGRESS, OrderExecutionReportDecoder.TEMPLATE_ID, 7),
                        new Record(2, GatewayJournal.EGRESS, OrderExecutionReportDecoder.TEMPLATE_ID, 9),
                        new Record(3, GatewayJournal.INGRESS, OrderExecutionReportDecoder.TEMPLATE_ID, 0)),
                records);
    }

    @Test
    void recordsLappedBeforeTheJournalReadsThemAreAGapAndRecordingGoesOn() throws Exception {
        final GatewayJournal journal =
                new GatewayJournal(new NullLogger(), this.directory, 1 << 22, Long.MAX_VALUE, System::nanoTime, 0);
        final JournalRecorder egress = journal.egress();
        final JournalRecorder ingress = journal.ingress();
        final OrderExecutionReport report = report();
        ingress.record(1, report.buffer, 0, report.totalMessageSize());
        for (int i = 0; i < SequencedRingBuffer.DEFAULT_SIZE + 10; i++) {
            egress.record(2, report.buffer, 0, report.totalMessageSize());
        }
        journal.doWork();
        assertEquals(10, journal.lapped());
        ingress.record(1, report.buffer, 0, report.totalMessageSize());
        journal.doWork();
        journal.close();

        final List<long[]> gaps = new ArrayList<>();
        final List<Record> records = read(gaps);
        // Written as the reorder window overflows, so oldest first and a sequence at a time: the rest might still come.
        long next = 2;
        for (final long[] gap : gaps) {
            assertEquals(next, gap[0]);
            next = gap[1] + 1;
        }
        assertEquals(12, next, "the gaps cover exactly the ten the ring lapped");
        assertEquals(SequencedRingBuffer.DEFAULT_SIZE + 2, records.size());
        assertEquals(
                SequencedRingBuffer.DEFAULT_SIZE + 12L,
                records.get(records.size() - 1).sequence());
    }

    @Test
    void aLanesDecisionsAreJournaledUnderItsOwnSourceAndCountedByTheMinute() throws Exception {
        final GatewayJournal journal =
                new GatewayJournal(new NullLogger(), this.directory, 1 << 20, Long.MAX_VALUE, System::nanoTime, 2);
        final long[] now = {1_000};
        final LaneAdmissions admissions = new LaneAdmissions(5, journal.lane(1), () -> now[0]);
        admissions.decided(7, 42, RequestClass.CANCEL, AdmissionAction.SENT, 3_000_000, 17);
        admissions.decided(7, 43, RequestClass.NEW, AdmissionAction.HELD, 0, -1);
        journal.doWork();
        journal.close();

        final List<Record> records = read(new ArrayList<>());
        assertEquals(
                new Record(1, GatewayJournal.LANE_FIRST + 1, AdmissionRecordDecoder.TEMPLATE_ID, 7), records.get(0));
        final AdmissionRecordDecoder first = admission(0);
        assertEquals(5, first.venueAccountId());
        assertEquals(42, first.clientOidCounter());
        assertEquals(group.gnometrading.schemas.gateway.RequestClass.CANCEL, first.requestClass());
        assertEquals(AdmissionAction.SENT, first.action());
        assertEquals(3_000_000, first.queuedNanos());
        assertEquals(17, first.tokensLeft());

        assertNull(admissions.lastMinute(now[0]), "no minute has ended yet");
        now[0] += LaneAdmissions.MINUTE_NANOS;
        admissions.decided(7, 44, RequestClass.NEW, AdmissionAction.SENT, 0, 3);
        final String minute = admissions.lastMinute(now[0]);
        assertTrue(minute.startsWith("1 cancels sent (waited p99 3"), minute);
        assertTrue(minute.contains("1 held for the budget"), minute);
    }

    private AdmissionRecordDecoder admission(final int index) throws Exception {
        final List<AdmissionRecordDecoder> found = new ArrayList<>();
        final List<Path> segments;
        try (Stream<Path> files = Files.list(this.directory)) {
            segments = files.sorted().toList();
        }
        new JournalReader(segments.get(0), 0).readAll(new JournalRecordHandler() {
            @Override
            public void onRecord(
                    final long sequence,
                    final long timestampNanos,
                    final int source,
                    final int templateId,
                    final UnsafeBuffer buffer,
                    final int length) {
                if (templateId == AdmissionRecordDecoder.TEMPLATE_ID) {
                    final UnsafeBuffer copy = new UnsafeBuffer(new byte[length]);
                    copy.putBytes(0, buffer, 0, length);
                    found.add(new AdmissionRecordDecoder()
                            .wrap(
                                    copy,
                                    JournalRecorder.SLOT_LENGTH + MessageHeaderDecoder.ENCODED_LENGTH,
                                    AdmissionRecordDecoder.BLOCK_LENGTH,
                                    AdmissionRecordDecoder.SCHEMA_VERSION));
                }
            }
        });
        return found.get(index);
    }

    private static OrderExecutionReport report() {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.encoder.exchangeId(1).securityId(2).execType(ExecType.NEW);
        return report;
    }

    private List<Record> read(final List<long[]> gaps) throws Exception {
        final List<Record> records = new ArrayList<>();
        final List<Path> segments;
        try (Stream<Path> files = Files.list(this.directory)) {
            segments = files.sorted().toList();
        }
        assertTrue(!segments.isEmpty());
        long previous = 0;
        for (final Path segment : segments) {
            final JournalReader reader = new JournalReader(segment, previous);
            reader.readAll(new JournalRecordHandler() {
                @Override
                public void onRecord(
                        final long sequence,
                        final long timestampNanos,
                        final int source,
                        final int templateId,
                        final UnsafeBuffer buffer,
                        final int length) {
                    records.add(new Record(sequence, source, templateId, buffer.getLong(0, ByteOrder.LITTLE_ENDIAN)));
                }

                @Override
                public void onGap(final long firstLost, final long lastLost) {
                    gaps.add(new long[] {firstLost, lastLost});
                }
            });
            previous = reader.lastReadSequence();
        }
        return records;
    }
}
