package group.gnometrading.gateways;

import group.gnometrading.concurrent.GnomeAgentRunner;
import java.util.List;

/**
 * The threads a venue's order gateway runs on, closed in the order that lets each finish: the reader stops first, even
 * if it is paused mid-reconnect, then the supervisor, whose disconnect then closes the socket at once instead of
 * waiting on a reader that is gone. Its order writer is run by the trading orchestrator with the OMS and closed there,
 * so the cancels the OMS sends on shutdown go out first.
 */
public final class GatewayRunners implements GatewayHealth, AutoCloseable {

    // A session can run several gateways on one venue, so the reader's name alone doesn't say which.
    private final int listingId;
    private final ReaderPauseControl readerPause;
    private final GnomeAgentRunner reader;
    private final GnomeAgentRunner supervisor;

    private GatewayRunners(
            final int listingId,
            final ReaderPauseControl readerPause,
            final GnomeAgentRunner reader,
            final GnomeAgentRunner supervisor) {
        this.listingId = listingId;
        this.readerPause = readerPause;
        this.reader = reader;
        this.supervisor = supervisor;
    }

    public static GatewayRunners venue(
            final int listingId,
            final ReaderPauseControl readerPause,
            final GnomeAgentRunner reader,
            final GnomeAgentRunner supervisor) {
        return new GatewayRunners(listingId, readerPause, reader, supervisor);
    }

    /** Starts each runner on its own thread. */
    public GatewayRunners start() {
        GnomeAgentRunner.startOnThread(supervisor);
        GnomeAgentRunner.startOnThread(reader);
        return this;
    }

    /** The gateway's name, after its reader and the listing it serves. */
    @Override
    public String name() {
        return reader.getAgent().roleName() + " " + listingId;
    }

    /** Whether the reader is held off its socket while the supervisor connects or reconnects it. */
    @Override
    public boolean isReconnecting() {
        return readerPause.isPauseRequested();
    }

    /** An order gateway has no book. */
    @Override
    public long bookResyncs() {
        return 0;
    }

    /** Every thread the gateway runs, for watching whether each is still making progress. */
    public List<GnomeAgentRunner> runners() {
        return List.of(reader, supervisor);
    }

    @Override
    public void close() throws Exception {
        readerPause.stop();
        reader.close();
        supervisor.close();
    }
}
