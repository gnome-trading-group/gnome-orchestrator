package group.gnometrading.gateways;

import group.gnometrading.concurrent.GnomeAgentRunner;
import java.util.List;

/**
 * The threads one gateway runs on, closed in the order that lets each finish: the reader stops first, even if it is
 * paused mid-reconnect, then a market data gateway's socket writer, then the supervisor, whose disconnect then closes
 * the socket at once instead of waiting on a reader that is gone.
 */
public final class GatewayRunners implements AutoCloseable {

    private final ReaderPauseControl readerPause;
    private final GnomeAgentRunner reader;
    // Null for a venue gateway, whose order writer the trading orchestrator owns.
    private final GnomeAgentRunner socketWriter;
    private final GnomeAgentRunner supervisor;

    private GatewayRunners(
            final ReaderPauseControl readerPause,
            final GnomeAgentRunner reader,
            final GnomeAgentRunner socketWriter,
            final GnomeAgentRunner supervisor) {
        this.readerPause = readerPause;
        this.reader = reader;
        this.socketWriter = socketWriter;
        this.supervisor = supervisor;
    }

    /**
     * A market data gateway: its socket writer sends subscriptions and keep-alives on the reader's socket, so it is
     * the gateway's own and closes with it.
     */
    public static GatewayRunners marketData(
            final ReaderPauseControl readerPause,
            final GnomeAgentRunner reader,
            final GnomeAgentRunner socketWriter,
            final GnomeAgentRunner supervisor) {
        return new GatewayRunners(readerPause, reader, socketWriter, supervisor);
    }

    /**
     * A venue's order gateway: its order writer is run by the trading orchestrator with the OMS and closed there, so
     * the cancels the OMS sends on shutdown go out first; only the reader and supervisor belong here.
     */
    public static GatewayRunners venue(
            final ReaderPauseControl readerPause, final GnomeAgentRunner reader, final GnomeAgentRunner supervisor) {
        return new GatewayRunners(readerPause, reader, null, supervisor);
    }

    /** Starts each runner on its own thread. */
    public GatewayRunners start() {
        GnomeAgentRunner.startOnThread(supervisor);
        GnomeAgentRunner.startOnThread(reader);
        if (socketWriter != null) {
            GnomeAgentRunner.startOnThread(socketWriter);
        }
        return this;
    }

    /** The gateway's name, after its reader. */
    public String name() {
        return reader.getAgent().roleName();
    }

    /** Whether the reader is held off its socket while the supervisor connects or reconnects it. */
    public boolean isReconnecting() {
        return readerPause.isPauseRequested();
    }

    /** Every thread the gateway runs, for watching whether each is still making progress. */
    public List<GnomeAgentRunner> runners() {
        return socketWriter == null ? List.of(reader, supervisor) : List.of(reader, socketWriter, supervisor);
    }

    @Override
    public void close() throws Exception {
        readerPause.stop();
        reader.close();
        if (socketWriter != null) {
            socketWriter.close();
        }
        supervisor.close();
    }
}
