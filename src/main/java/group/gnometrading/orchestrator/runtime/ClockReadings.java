package group.gnometrading.orchestrator.runtime;

import group.gnometrading.utils.AsciiParsing;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.agrona.concurrent.EpochClock;

/**
 * How well this host's clock is synchronized, from the status file the host's clock service rewrites every 10 s
 * ({@code /run/gnome/clock.status}, gnome-shared-cdk's host clock): where the time comes from and how far from UTC it
 * can be. Timestamps compared across hosts are only as good as the two hosts' bounds added together.
 *
 * <p>The file is rewritten in place, so it is opened once and re-read from the start, like a {@code /proc} file, and
 * parsed without allocating. A read that isn't whole (the instant the file is being rewritten) keeps the previous
 * reading. Until the file has been read once, or if it never appears, the source is {@link Source#UNKNOWN}. Not
 * thread-safe: sample and read on one thread.
 */
public final class ClockReadings implements AutoCloseable {

    public static final Path HOST_STATUS_FILE = Path.of("/run/gnome/clock.status");
    public static final long UNAVAILABLE = -1;

    /** Where the host's time comes from. */
    public enum Source {
        /** The Nitro hardware clock: about 20 µs from UTC. */
        PHC,
        /** Amazon Time Sync over NTP: about 100 µs, or 500 µs on a burstable instance. */
        NTP,
        /** chrony has no source it trusts: the clock is drifting freely. */
        UNSYNCED,
        /** No status read yet: the host runs no clock service, or it hasn't written. */
        UNKNOWN
    }

    private static final int BUFFER_BYTES = 512;
    private static final byte NEWLINE = '\n';
    private static final byte MINUS = '-';
    private static final byte[] WRITTEN_MS = ascii("written_ms");
    private static final byte[] SOURCE = ascii("source");
    private static final byte[] STRATUM = ascii("stratum");
    private static final byte[] OFFSET_NS = ascii("offset_ns");
    private static final byte[] ERROR_BOUND_NS = ascii("error_bound_ns");
    private static final byte[] PHC_ERROR_BOUND_NS = ascii("phc_error_bound_ns");
    private static final byte[] PHC = ascii("PHC");
    private static final byte[] NTP = ascii("NTP");
    private static final byte[] UNSYNCED = ascii("UNSYNCED");
    private static final int FIELDS = 6;
    private static final long INVALID = Long.MIN_VALUE;

    private final Path file;
    private final EpochClock clock;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_BYTES);
    private FileChannel channel;

    private Source source = Source.UNKNOWN;
    private long writtenAtMs = UNAVAILABLE;
    private long stratum = UNAVAILABLE;
    private long offsetNanos;
    private long errorBoundNanos = UNAVAILABLE;
    private long phcErrorBoundNanos = UNAVAILABLE;
    private long ageMs = UNAVAILABLE;

    private Source readSource;
    private long readWrittenAtMs;
    private long readStratum;
    private long readOffsetNanos;
    private long readErrorBoundNanos;
    private long readPhcErrorBoundNanos;

    public ClockReadings(final Path file, final EpochClock clock) {
        this.file = file;
        this.clock = clock;
    }

    public static ClockReadings ofThisHost(final EpochClock clock) {
        return new ClockReadings(HOST_STATUS_FILE, clock);
    }

    /** Re-reads the status; a read that isn't whole keeps the previous one, though its age still grows. */
    public void sample() {
        if (read() && parse()) {
            source = readSource;
            writtenAtMs = readWrittenAtMs;
            stratum = readStratum;
            offsetNanos = readOffsetNanos;
            errorBoundNanos = readErrorBoundNanos;
            phcErrorBoundNanos = readPhcErrorBoundNanos;
        }
        ageMs = writtenAtMs == UNAVAILABLE ? UNAVAILABLE : Math.max(0, clock.time() - writtenAtMs);
    }

    public Source source() {
        return source;
    }

    /** How far this host's clock can be from UTC, in nanoseconds; {@link #UNAVAILABLE} when unsynced or unknown. */
    public long errorBoundNanos() {
        return errorBoundNanos;
    }

    /** chrony's estimate of how far this clock is ahead of UTC (negative: behind), in nanoseconds. */
    public long offsetNanos() {
        return offsetNanos;
    }

    public long stratum() {
        return stratum;
    }

    /**
     * The hardware clock's own error bound as the Nitro card publishes it, already in {@link #errorBoundNanos()};
     * {@link #UNAVAILABLE} off the PHC or where the driver doesn't publish it (Ubuntu's).
     */
    public long phcErrorBoundNanos() {
        return phcErrorBoundNanos;
    }

    /** How old the status was at the last sample, in milliseconds: a stale one means the clock service stopped. */
    public long ageMs() {
        return ageMs;
    }

    private boolean read() {
        if (channel == null) {
            channel = open(file);
            if (channel == null) {
                return false;
            }
        }
        buffer.clear();
        try {
            long position = 0;
            int read;
            while (buffer.hasRemaining() && (read = channel.read(buffer, position)) > 0) {
                position += read;
            }
        } catch (IOException e) {
            return false;
        }
        buffer.flip();
        return buffer.hasRemaining() && buffer.get(buffer.limit() - 1) == NEWLINE;
    }

    private boolean parse() {
        readSource = null;
        readWrittenAtMs = INVALID;
        readStratum = INVALID;
        readOffsetNanos = INVALID;
        readErrorBoundNanos = INVALID;
        readPhcErrorBoundNanos = INVALID;
        final int end = buffer.limit();
        int found = 0;
        int lineStart = 0;
        while (lineStart < end) {
            int lineEnd = lineStart;
            while (lineEnd < end && buffer.get(lineEnd) != NEWLINE) {
                lineEnd++;
            }
            int keyEnd = lineStart;
            while (keyEnd < lineEnd && !AsciiParsing.isSpaceOrTab(buffer.get(keyEnd))) {
                keyEnd++;
            }
            final int valueStart = AsciiParsing.skipSpacesAndTabs(buffer, keyEnd, lineEnd);
            if (field(lineStart, keyEnd, valueStart, lineEnd)) {
                found++;
            }
            lineStart = lineEnd + 1;
        }
        return found == FIELDS && readSource != null;
    }

    private boolean field(final int keyStart, final int keyEnd, final int valueStart, final int valueEnd) {
        if (is(keyStart, keyEnd, SOURCE)) {
            readSource = sourceOf(valueStart, valueEnd);
            return readSource != null;
        }
        final long value = parseLong(valueStart, valueEnd);
        if (value == INVALID) {
            return false;
        }
        if (is(keyStart, keyEnd, WRITTEN_MS)) {
            readWrittenAtMs = value;
        } else if (is(keyStart, keyEnd, STRATUM)) {
            readStratum = value;
        } else if (is(keyStart, keyEnd, OFFSET_NS)) {
            readOffsetNanos = value;
        } else if (is(keyStart, keyEnd, ERROR_BOUND_NS)) {
            readErrorBoundNanos = value;
        } else if (is(keyStart, keyEnd, PHC_ERROR_BOUND_NS)) {
            readPhcErrorBoundNanos = value;
        } else {
            return false;
        }
        return true;
    }

    private Source sourceOf(final int start, final int end) {
        if (is(start, end, PHC)) {
            return Source.PHC;
        }
        if (is(start, end, NTP)) {
            return Source.NTP;
        }
        if (is(start, end, UNSYNCED)) {
            return Source.UNSYNCED;
        }
        return null;
    }

    private long parseLong(final int start, final int end) {
        if (start < end && buffer.get(start) == MINUS) {
            final long magnitude = AsciiParsing.parseNaturalLong(buffer, start + 1, end, INVALID);
            return magnitude == INVALID ? INVALID : -magnitude;
        }
        return AsciiParsing.parseNaturalLong(buffer, start, end, INVALID);
    }

    private boolean is(final int start, final int end, final byte[] expected) {
        if (end - start != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (buffer.get(start + i) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static FileChannel open(final Path path) {
        try {
            return FileChannel.open(path, StandardOpenOption.READ);
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    private static byte[] ascii(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public void close() {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            // Closing a read-only file can't lose anything.
        }
    }
}
