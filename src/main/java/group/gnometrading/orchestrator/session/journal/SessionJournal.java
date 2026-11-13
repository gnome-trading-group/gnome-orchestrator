package group.gnometrading.orchestrator.session.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.logging.Logger;
import group.gnometrading.resources.Properties;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.JournalWriter;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.SystemEpochClock;

/**
 * A session's journal: every buffer on the session sequence, written in sequence order into segments a shipper sends
 * on. Off for a local run and when {@code journal.enabled} is false, which leaves every field null.
 *
 * @param runner the journal's agent, running
 * @param sealer what seals each full segment, which the heartbeat reports on
 * @param directory where the segments go
 */
public record SessionJournal(GnomeAgentRunner runner, JournalSegmentSealer sealer, Path directory) {

    public static final SessionJournal OFF = new SessionJournal(null, null, null);

    // Sealing is a local unmap, trim and rename; well inside the time systemd gives the process to stop.
    private static final Duration SEAL_TIMEOUT = Duration.ofSeconds(5);
    // Read by the journal shipper (gnome-orchestrator ami/files/journal-shipper.py) to learn where segments go.
    private static final String MANIFEST = "journal.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Starts the journal over every buffer on {@code sessionSequence}. Before the outbound gateways start, which can
     * publish execution reports as soon as they connect: a journal poller only sees what is published after it exists,
     * and a missed number stalls the journal.
     */
    public static SessionJournal start(
            String sessionId,
            GlobalSequence sessionSequence,
            Map<SequencedRingBuffer<?>, JournalSource> journalSources,
            ErrorHandler errorHandler,
            Logger logger,
            Properties properties) {
        // Checked with the journal off too, so a buffer added without a source fails in every run, not only when
        // someone turns the journal on.
        verifyCoverage(sessionSequence, journalSources);
        // A local run has no session to file the journal under, nor a shipper to send it, as with the ledger.
        if (sessionId == null || !properties.getBooleanProperty("journal.enabled")) {
            return OFF;
        }
        Path directory = Path.of(properties.getStringProperty("journal.directory"), sessionId);
        long segmentSizeBytes = (long) properties.getIntProperty("journal.segment.size.mb") * 1024L * 1024L;
        try {
            JournalSegmentSealer sealer = new JournalSegmentSealer(segmentSizeBytes, SystemEpochClock.INSTANCE, logger);
            JournalWriter journalWriter = new JournalWriter(directory, segmentSizeBytes, sealer::submit);
            MAPPER.writeValue(
                    directory.resolve(MANIFEST).toFile(),
                    Map.of(
                            "bucket",
                            properties.getStringProperty("journal.bucket"),
                            "keyPrefix",
                            sessionId,
                            "region",
                            properties.getStringProperty("journal.bucket.region"),
                            "format",
                            JournalWriter.FORMAT));
            List<SequencedPoller> pollers = new ArrayList<>(journalSources.size());
            for (SequencedRingBuffer<?> buffer : sessionSequence.buffers()) {
                pollers.add(
                        journalWriter.journal(buffer, journalSources.get(buffer).id()));
            }
            JournalManagerAgent agent = new JournalManagerAgent(
                    journalWriter,
                    pollers,
                    sealer,
                    Duration.ofSeconds(properties.getIntProperty("journal.segment.interval.seconds")),
                    SEAL_TIMEOUT,
                    SystemEpochClock.INSTANCE,
                    logger);
            GnomeAgentRunner runner = new GnomeAgentRunner(agent, errorHandler);
            GnomeAgentRunner.startOnThread(runner);
            return new SessionJournal(runner, sealer, directory);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create journal writer", e);
        }
    }

    /**
     * Fails when a buffer takes numbers from the session sequence without a journal source. The journal writes in
     * strict sequence order, so the first number it never sees would stop it for the rest of the session.
     */
    public static void verifyCoverage(
            GlobalSequence sessionSequence, Map<SequencedRingBuffer<?>, JournalSource> journalSources) {
        for (SequencedRingBuffer<?> buffer : sessionSequence.buffers()) {
            if (!journalSources.containsKey(buffer)) {
                throw new IllegalStateException(
                        "A buffer on the session sequence has no journal source; every buffer on it is journaled");
            }
        }
    }
}
