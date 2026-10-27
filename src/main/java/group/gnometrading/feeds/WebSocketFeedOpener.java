package group.gnometrading.feeds;

import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.ConnectLimiter;
import group.gnometrading.gateways.GatewayErrorHandler;
import group.gnometrading.gateways.ProcessExit;
import group.gnometrading.gateways.feed.ConnectionGroup;
import group.gnometrading.gateways.feed.FeedConnection;
import group.gnometrading.gateways.feed.FeedListener;
import group.gnometrading.gateways.feed.RateBudget;
import group.gnometrading.gateways.feed.RawDataSink;
import group.gnometrading.gateways.feed.SubjectTable;
import group.gnometrading.gateways.feed.VenueProtocol;
import group.gnometrading.gateways.feed.transport.WebSocketChannel;
import group.gnometrading.gateways.feed.transport.WebSocketTransport;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.networking.websockets.WebSocketClient;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.SystemEpochClock;

/**
 * Opens a source's websocket connections: a TLS client, the source's protocol on it, the connection's reader, writer
 * and supervisor threads, and where its raw frames are kept.
 */
public final class WebSocketFeedOpener implements ConnectionGroup.Opener<WebSocketChannel> {

    // One command: Kalshi and Polymarket send up to 100 markets in one, Polymarket's token ids being ~80 characters.
    private static final int WRITE_SLOT_BYTES = 1 << 14;

    /** Where a connection's raw frames go; flushed once its reader has stopped. */
    public interface RawCapture extends RawDataSink {
        RawCapture NONE = new RawCapture() {
            @Override
            public void capture(final long recvTimestamp, final ByteBuffer buffer) {}

            @Override
            public void flush() {}
        };

        void flush();
    }

    /** A raw capture for each new connection, by its name. */
    @FunctionalInterface
    public interface RawCaptures {
        RawCaptures NONE = connection -> RawCapture.NONE;

        RawCapture open(String connection);
    }

    /** Makes a connection's websocket client. */
    @FunctionalInterface
    public interface Clients {
        Clients TLS = (uri, readBufferBytes) -> new WebSocketClientBuilder()
                .withURI(uri)
                .withSocketFactory(new NativeSSLSocketFactory())
                .withReadBufferSize(readBufferBytes)
                .build();

        WebSocketClient create(URI uri, int readBufferBytes) throws Exception;
    }

    private final FeedVenues.Venue venue;
    private final FeedVenues.Context context;
    private final ConnectLimiter connects;
    private final RateBudget commands;
    private final boolean spinReads;
    private final ProcessExit exit;
    private final RawCaptures raw;
    private final Clients clients;

    /**
     * @param spinReads whether readers spin rather than block, for readers on their own isolated cores
     * @param exit what a connection that can't connect or keeps failing does
     * @param clients {@link Clients#TLS}, or one that misbehaves on purpose to test gap handling
     */
    public WebSocketFeedOpener(
            final FeedVenues.Venue venue,
            final FeedVenues.Context context,
            final ConnectLimiter connects,
            final RateBudget commands,
            final boolean spinReads,
            final ProcessExit exit,
            final RawCaptures raw,
            final Clients clients) {
        this.venue = venue;
        this.context = context;
        this.connects = connects;
        this.commands = commands;
        this.spinReads = spinReads;
        this.exit = exit;
        this.raw = raw;
        this.clients = clients;
    }

    @Override
    public ConnectionGroup.Open<WebSocketChannel> open(
            final String name, final SubjectTable table, final FeedListener listener) throws Exception {
        final WebSocketClient client = clients.create(
                new URI(context.properties().getStringProperty(venue.feed().urlProperty())),
                venue.feed().readBufferBytes());
        try {
            return start(name, table, listener, client);
        } catch (Exception | Error e) {
            // Nothing owns the client yet: building the protocol loads credentials, which can fail.
            try {
                client.close();
            } catch (Exception closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    /**
     * {@code exit}, but only for a connection streams depend on. A roll's replacement that fails before it holds any
     * market only fails the roll, which keeps the old connection and tries again later; it mustn't take a session
     * down with it.
     */
    static ProcessExit onceCarrying(
            final BooleanSupplier carriesMarkets, final ProcessExit exit, final Logger logger, final String name) {
        return (status, cause) -> {
            if (carriesMarkets.getAsBoolean()) {
                exit.exit(status, cause);
            } else {
                logger.logf(LogMessage.UNKNOWN_ERROR, "%s gave up before carrying any market", name);
            }
        };
    }

    private ConnectionGroup.Open<WebSocketChannel> start(
            final String name, final SubjectTable table, final FeedListener listener, final WebSocketClient client)
            throws Exception {
        final WebSocketTransport transport = new WebSocketTransport(client, WRITE_SLOT_BYTES);
        final VenueProtocol<WebSocketChannel> protocol = venue.protocols().create(context, table, transport.channel());
        final FeedConnection<WebSocketChannel> connection = new FeedConnection<>(
                name,
                context.logger(),
                transport,
                protocol,
                table,
                venue.feed().gatewayConfig().withSpinReads(spinReads),
                new SystemEpochClock(),
                context.clock(),
                connects,
                commands,
                listener);
        final RawCapture capture = raw.open(name);
        connection.setRawSink(capture);

        final GatewayErrorHandler errors = new GatewayErrorHandler(
                context.logger(),
                name,
                connection.supervisor()::forceReconnect,
                onceCarrying(connection::carriesMarkets, exit, context.logger(), name));
        final GnomeAgentRunner reader = new GnomeAgentRunner(connection.reader(), errors);
        final GnomeAgentRunner writer = new GnomeAgentRunner(connection.writer(), errors);
        final GnomeAgentRunner supervisor = new GnomeAgentRunner(connection.supervisor(), errors);
        GnomeAgentRunner.startOnThread(supervisor);
        GnomeAgentRunner.startOnThread(reader);
        GnomeAgentRunner.startOnThread(writer);
        // The reader stops first, even paused mid-reconnect; the supervisor's disconnect then closes the socket at
        // once. The raw minutes are flushed only after, as the reader writes them.
        return new ConnectionGroup.Open<>(connection, () -> {
            connection.pauseControl().stop();
            reader.close();
            writer.close();
            supervisor.close();
            capture.flush();
        });
    }
}
