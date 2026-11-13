package group.gnometrading.orchestrator.gateway.journal;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.JournalSegment;
import group.gnometrading.sequencer.JournalWriter;
import group.gnometrading.sequencer.LappingPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.utils.CauseChain;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The gateway's journal: what every session sent in (ingress), everything sent out to it (egress), and what each
 * account's lane decided about each request (its {@code AdmissionRecord}s), each record the session's slot and the
 * message (see {@link JournalRecorder}), in one total order. Each recording thread writes its
 * own ring, stamped from one global sequence; this agent reads them through lapping pollers, so the journal never holds
 * up an order: records a ring laps before they are read are lost, written into the journal as a gap and logged.
 * Segments roll on an interval and are sealed on a thread of their own, for whatever ships them.
 */
public final class GatewayJournal implements GnomeAgent, AutoCloseable {

    /** The journal's sources: which thread recorded each record. */
    static final int INGRESS = 1;

    static final int EGRESS = 2;

    /** Account lanes are sources from here, one each, in the order the gateway holds its accounts. */
    static final int LANE_FIRST = 16;

    private static final long SEAL_WAIT_SECONDS = 10;

    private final Logger logger;
    private final JournalWriter writer;
    private final SequencedRingBuffer<OrderExecutionReport> ingress;
    private final SequencedRingBuffer<OrderExecutionReport> egress;
    private final List<SequencedRingBuffer<OrderExecutionReport>> lanes;
    private final List<LappingPoller> pollers;
    private final ExecutorService sealer;
    private final LongSupplier nanoClock;
    private final long rollIntervalNanos;
    private long nextRollNanos;
    private long lapped;

    public GatewayJournal(
            final Logger logger,
            final Path directory,
            final long segmentBytes,
            final long rollIntervalNanos,
            final LongSupplier nanoClock,
            final int lanes)
            throws IOException {
        this(logger, directory, segmentBytes, rollIntervalNanos, nanoClock, lanes, SequencedRingBuffer.DEFAULT_SIZE);
    }

    /** @param ringSize each recording thread's ring, a power of two; records it laps before they are read are lost */
    public GatewayJournal(
            final Logger logger,
            final Path directory,
            final long segmentBytes,
            final long rollIntervalNanos,
            final LongSupplier nanoClock,
            final int lanes,
            final int ringSize)
            throws IOException {
        this.logger = logger;
        this.sealer = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "gateway-journal-sealer");
            thread.setDaemon(true);
            return thread;
        });
        this.writer = new JournalWriter(directory, segmentBytes, this::seal, true);
        final GlobalSequence sequence = new GlobalSequence();
        this.ingress = new SequencedRingBuffer<>(OrderExecutionReport::new, sequence, ringSize);
        this.egress = new SequencedRingBuffer<>(OrderExecutionReport::new, sequence, ringSize);
        final List<SequencedRingBuffer<OrderExecutionReport>> laneRings = new ArrayList<>();
        // Made before any ring is written, so nothing is missed.
        final List<LappingPoller> pollers = new ArrayList<>();
        pollers.add(this.writer.journalLapping(this.ingress, INGRESS));
        pollers.add(this.writer.journalLapping(this.egress, EGRESS));
        for (int i = 0; i < lanes; i++) {
            final SequencedRingBuffer<OrderExecutionReport> ring =
                    new SequencedRingBuffer<>(OrderExecutionReport::new, sequence, ringSize);
            laneRings.add(ring);
            pollers.add(this.writer.journalLapping(ring, LANE_FIRST + i));
        }
        this.lanes = List.copyOf(laneRings);
        this.pollers = List.copyOf(pollers);
        this.nanoClock = nanoClock;
        this.rollIntervalNanos = rollIntervalNanos;
        this.nextRollNanos = nanoClock.getAsLong() + rollIntervalNanos;
    }

    /** For the ingress thread. */
    public JournalRecorder ingress() {
        return JournalRecorder.into(this.ingress);
    }

    /** For the egress thread. */
    public JournalRecorder egress() {
        return JournalRecorder.into(this.egress);
    }

    /** For the lane of the {@code index}th account the gateway holds. */
    public JournalRecorder lane(final int index) {
        return JournalRecorder.into(this.lanes.get(index));
    }

    @Override
    public int doWork() throws Exception {
        int work = 0;
        for (int i = 0; i < this.pollers.size(); i++) {
            work += this.pollers.get(i).poll();
        }
        long lapped = 0;
        for (int i = 0; i < this.pollers.size(); i++) {
            lapped += this.pollers.get(i).lapped();
        }
        if (lapped != this.lapped) {
            // Every ring was just read, so what is still missing is what the laps took.
            final long lost = this.writer.skipMissing();
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "GATEWAY_JOURNAL_GAP: the journal fell a ring behind and lost %d records (%d sequences)",
                    lapped - this.lapped,
                    lost);
            this.lapped = lapped;
        }
        final long now = this.nanoClock.getAsLong();
        if (now - this.nextRollNanos >= 0) {
            this.nextRollNanos = now + this.rollIntervalNanos;
            this.writer.roll();
        }
        return work;
    }

    /** Records lost to laps. */
    public long lapped() {
        return this.lapped;
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.BACKGROUND;
    }

    @Override
    public String roleName() {
        return "gateway-journal";
    }

    /** With the recording threads stopped: reads what is left, writes it, and seals the last segment. */
    @Override
    public void close() throws Exception {
        doWork();
        this.writer.close();
        this.sealer.shutdown();
        // Sealed before the process goes, so whatever ships the journal finds every segment complete.
        if (!this.sealer.awaitTermination(SEAL_WAIT_SECONDS, TimeUnit.SECONDS)) {
            this.logger.logf(LogMessage.UNKNOWN_ERROR, "Journal segments still sealing as the gateway stops");
        }
    }

    private void seal(final JournalSegment segment) {
        this.sealer.execute(() -> {
            try {
                segment.seal();
            } catch (RuntimeException e) {
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Failed to seal journal segment %d: %s",
                        segment.index(),
                        CauseChain.of(e));
            }
        });
    }
}
