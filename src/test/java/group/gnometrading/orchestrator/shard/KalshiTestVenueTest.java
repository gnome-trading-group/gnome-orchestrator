package group.gnometrading.orchestrator.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class KalshiTestVenueTest {

    private static final Pattern SID_SEQ = Pattern.compile("\"sid\":(\\d+),\"seq\":(\\d+)");

    @Test
    void eachSidNumbersEveryFrameFromOneAsKalshiDoes() throws Exception {
        final List<String> frames = run(
                1000,
                "{\"id\":1,\"cmd\":\"subscribe\",\"params\":{\"channels\":[\"orderbook_delta\",\"trade\"],"
                        + "\"market_tickers\":[\"A\",\"B\"],\"use_yes_price\":true}}",
                "{\"id\":2,\"cmd\":\"update_subscription\",\"params\":{\"sids\":[1],\"market_tickers\":[\"A\"],"
                        + "\"action\":\"get_snapshot\"}}");

        assertTrue(frames.get(0).contains("\"type\":\"subscribed\"")
                && frames.get(0).contains("orderbook_delta"));
        assertTrue(frames.get(1).contains("\"type\":\"subscribed\"")
                && frames.get(1).contains("\"trade\""));
        assertTrue(frames.get(2).startsWith("{\"type\":\"orderbook_snapshot\",\"sid\":1,\"seq\":1,"));
        final Map<Integer, Long> next = new HashMap<>(Map.of(1, 1L, 2, 1L));
        int snapshots = 0;
        for (String frame : frames) {
            final Matcher m = SID_SEQ.matcher(frame);
            if (!m.find()) {
                continue;
            }
            final int sid = Integer.parseInt(m.group(1));
            assertEquals(next.get(sid), Long.parseLong(m.group(2)), frame);
            next.put(sid, next.get(sid) + 1);
            snapshots += frame.contains("orderbook_snapshot") ? 1 : 0;
        }
        assertEquals(3, snapshots, "two on subscribe, one asked for");
        assertTrue(next.get(1) > 20, "deltas follow at the rate set");
    }

    /** Subscribes, sends {@code commands}, and returns the text frames heard in the next half second. */
    private static List<String> run(final double rate, final String... commands) throws Exception {
        final KalshiTestVenue venue = new KalshiTestVenue(rate, 0.1);
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            final Thread serving = new Thread(() -> {
                try {
                    venue.serve(server);
                } catch (IOException ignored) {
                    // the test closed the socket
                }
            });
            serving.setDaemon(true);
            serving.start();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()));
                final OutputStream out = socket.getOutputStream();
                out.write(("GET /trade-api/ws/v2 HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                final DataInputStream in = new DataInputStream(socket.getInputStream());
                final StringBuilder response = new StringBuilder();
                while (response.indexOf("\r\n\r\n") < 0) {
                    response.append((char) in.read());
                }
                assertTrue(response.toString().contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
                for (String command : commands) {
                    sendMasked(out, command);
                    Thread.sleep(100);
                }
                socket.setSoTimeout(500);
                final List<String> frames = new ArrayList<>();
                final long until = System.currentTimeMillis() + 500;
                while (System.currentTimeMillis() < until) {
                    frames.add(readText(in));
                }
                return frames;
            }
        }
    }

    private static void sendMasked(final OutputStream out, final String text) throws IOException {
        final byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        final byte[] mask = {1, 2, 3, 4};
        out.write(0x81);
        out.write(0x80 | 126);
        out.write(payload.length >>> 8);
        out.write(payload.length & 0xFF);
        out.write(mask);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ mask[i & 3]);
        }
        out.flush();
    }

    private static String readText(final DataInputStream in) throws IOException {
        in.readUnsignedByte();
        int length = in.readUnsignedByte();
        if (length == 126) {
            length = in.readUnsignedShort();
        } else if (length == 127) {
            length = (int) in.readLong();
        }
        final byte[] payload = in.readNBytes(length);
        return new String(payload, StandardCharsets.UTF_8);
    }
}
