package group.gnometrading.orchestrator.gateway.bench;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * A venue's HTTPS and WSS endpoints on one port, for the bench: each connection on its own thread, HTTP/1.1 requests
 * answered in order (a reply's delay holds the connection, as a venue's does), and a connection that asks to upgrade
 * becomes a websocket. What each request means is the {@link Model}'s; the server only speaks the protocols. Allocates
 * freely: it stands in for the venue, off the gateway's process on the bench host.
 */
public final class TlsVenueServer implements AutoCloseable {

    /** A request as the venue saw it. */
    public record Request(String method, String path, Map<String, String> headers, String body, long receivedNanos) {}

    /**
     * An answer: {@code status} and {@code body} after {@code delayNanos}; {@code afterwards} runs once it is written
     * (to push the events the request causes after its answer, as a venue does).
     */
    public record Reply(int status, String body, long delayNanos, Runnable afterwards) {
        public static Reply ok(final String body, final long delayNanos) {
            return new Reply(200, body, delayNanos, null);
        }

        public Reply then(final Runnable next) {
            return new Reply(this.status, this.body, this.delayNanos, next);
        }
    }

    /** What the venue does with what it is sent. Called on connection threads, so implementations synchronize. */
    public interface Model {
        Reply onRequest(Request request);

        void onSocketOpen(Socket socket);

        void onSocketText(Socket socket, String text);

        void onSocketClosed(Socket socket);
    }

    /** One websocket, as the model writes to it. */
    public static final class Socket {
        private final OutputStream out;
        private final String path;
        private volatile boolean open = true;

        Socket(final OutputStream out, final String path) {
            this.out = out;
            this.path = path;
        }

        public String path() {
            return this.path;
        }

        public boolean isOpen() {
            return this.open;
        }

        /** Sends a text frame; false once the socket has gone. */
        public boolean sendText(final String text) {
            return send(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        /** A ping frame, as venues send to keep a quiet socket alive. */
        public boolean ping() {
            return send(0x9, new byte[0]);
        }

        synchronized boolean send(final int opcode, final byte[] payload) {
            if (!this.open) {
                return false;
            }
            try {
                final ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 10);
                frame.write(0x80 | opcode);
                if (payload.length < 126) {
                    frame.write(payload.length);
                } else if (payload.length < 65_536) {
                    frame.write(126);
                    frame.write(payload.length >>> 8);
                    frame.write(payload.length);
                } else {
                    frame.write(127);
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        frame.write((int) ((long) payload.length >>> shift));
                    }
                }
                frame.write(payload);
                this.out.write(frame.toByteArray());
                this.out.flush();
                return true;
            } catch (IOException e) {
                this.open = false;
                return false;
            }
        }

        void closed() {
            this.open = false;
        }
    }

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    // "\r\n\r\n" as the last four bytes read.
    private static final int END_OF_HEAD = 0x0D0A0D0A;

    private final SSLServerSocket server;
    private final Model model;
    private final Thread acceptor;
    private final List<java.net.Socket> connections = new CopyOnWriteArrayList<>();
    private final AtomicLong requests = new AtomicLong();
    private volatile boolean running = true;

    /** Listens on {@code bindAddress:port} (0.0.0.0 to take every interface). */
    public TlsVenueServer(final SSLContext context, final String bindAddress, final int port, final Model model)
            throws IOException {
        this.model = model;
        this.server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
        this.server.setReuseAddress(true);
        this.server.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port), 256);
        this.acceptor = new Thread(this::accept, "bench-venue-accept-" + port);
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    public int port() {
        return this.server.getLocalPort();
    }

    /** Requests answered so far, websocket frames not counted. */
    public long requests() {
        return this.requests.get();
    }

    private void accept() {
        while (this.running) {
            try {
                final java.net.Socket socket = this.server.accept();
                socket.setTcpNoDelay(true);
                this.connections.add(socket);
                final Thread thread = new Thread(() -> serve(socket), "bench-venue-conn");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                if (this.running) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void serve(final java.net.Socket socket) {
        try (socket) {
            final InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            final OutputStream out = socket.getOutputStream();
            while (this.running) {
                final Request request = readRequest(in);
                if (request == null) {
                    return;
                }
                if ("websocket".equalsIgnoreCase(request.headers().get("upgrade"))) {
                    upgrade(request, in, out);
                    return;
                }
                this.requests.incrementAndGet();
                final Reply reply = this.model.onRequest(request);
                final long due = request.receivedNanos() + reply.delayNanos();
                for (long wait = due - System.nanoTime(); wait > 0; wait = due - System.nanoTime()) {
                    LockSupport.parkNanos(wait);
                }
                writeReply(out, reply);
                if (reply.afterwards() != null) {
                    reply.afterwards().run();
                }
            }
        } catch (SocketException e) {
            // The gateway closed or retired the connection.
        } catch (IOException e) {
            if (this.running) {
                System.err.println("bench venue connection failed: " + e);
            }
        } finally {
            this.connections.remove(socket);
        }
    }

    private static Request readRequest(final InputStream in) throws IOException {
        final ByteArrayOutputStream head = new ByteArrayOutputStream(1024);
        int lastFour = 0;
        while (lastFour != END_OF_HEAD) {
            final int b = in.read();
            if (b < 0) {
                return null;
            }
            head.write(b);
            lastFour = (lastFour << 8) | b;
            if (head.size() > MAX_HEADER_BYTES) {
                throw new IOException("Request head too long");
            }
        }
        final long receivedNanos = System.nanoTime();
        final String[] lines = head.toString(StandardCharsets.US_ASCII).split("\r\n");
        final String[] requestLine = lines[0].split(" ");
        final Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            final int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(
                        lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }
        final String length = headers.get("content-length");
        final byte[] body = length == null ? new byte[0] : in.readNBytes(Integer.parseInt(length));
        return new Request(
                requestLine[0], requestLine[1], headers, new String(body, StandardCharsets.UTF_8), receivedNanos);
    }

    private static void writeReply(final OutputStream out, final Reply reply) throws IOException {
        final byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        final String head = "HTTP/1.1 " + reply.status() + " " + (reply.status() < 400 ? "OK" : "Error") + "\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
        final byte[] headBytes = head.getBytes(StandardCharsets.US_ASCII);
        final byte[] whole = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, whole, 0, headBytes.length);
        System.arraycopy(body, 0, whole, headBytes.length, body.length);
        out.write(whole);
        out.flush();
    }

    private void upgrade(final Request request, final InputStream in, final OutputStream out) throws IOException {
        final String accept;
        try {
            accept = Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((request.headers().get("sec-websocket-key") + WEBSOCKET_GUID)
                                    .getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IOException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        final Socket socket = new Socket(out, request.path());
        this.model.onSocketOpen(socket);
        try {
            readFrames(in, socket);
        } finally {
            socket.closed();
            this.model.onSocketClosed(socket);
        }
    }

    private void readFrames(final InputStream in, final Socket socket) throws IOException {
        while (this.running) {
            final int first = in.read();
            final int second = in.read();
            if (first < 0 || second < 0) {
                return;
            }
            final int opcode = first & 0x0F;
            long length = second & 0x7F;
            if (length == 126) {
                length = (in.read() << 8) | in.read();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | in.read();
                }
            }
            final byte[] mask = (second & 0x80) != 0 ? in.readNBytes(4) : null;
            final byte[] payload = in.readNBytes((int) length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }
            switch (opcode) {
                case 0x1 -> this.model.onSocketText(socket, new String(payload, StandardCharsets.UTF_8));
                case 0x8 -> {
                    socket.send(0x8, payload);
                    return;
                }
                case 0x9 -> socket.send(0xA, payload);
                default -> {}
            }
        }
    }

    @Override
    public void close() throws IOException {
        this.running = false;
        this.server.close();
        for (final java.net.Socket socket : this.connections) {
            socket.close();
        }
    }
}
