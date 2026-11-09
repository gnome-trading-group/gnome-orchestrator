package group.gnometrading.orchestrator.gateway.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The sessions the gateway has accepted, kept next to the ownership logs: after a restart, whose slot each recovered
 * order is, how long that session may stay silent before its orders are cancelled, and what the registry said of it, so
 * a session the gateway knew can come back while the registry is down. One JSON line per accepted hello; the newest per
 * slot counts. The control thread's; allocates freely.
 */
public final class SessionsFile {

    /** Entries this old are dropped when the file is opened: no session runs this long without a hello. */
    static final long KEEP_MILLIS = TimeUnit.DAYS.toMillis(7);

    /** One accepted session. */
    public record Entry(
            long slot,
            String sessionId,
            int strategyId,
            int accountId,
            long codTimeoutMs,
            long acceptedAtMillis,
            String status,
            String mode,
            Set<Integer> listingIds,
            Map<Integer, Integer> accountByExchange,
            int rateWeight) {

        public SessionRegistry.SessionRecord record() {
            return new SessionRegistry.SessionRecord(
                    this.slot,
                    this.status,
                    this.strategyId,
                    this.mode,
                    this.listingIds,
                    this.accountByExchange,
                    Math.max(1, this.rateWeight));
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path path;

    private SessionsFile(final Path path) {
        this.path = path;
    }

    /** Reads the newest entry per slot, then rewrites the file with just those still recent. */
    public static SessionsFile open(final Path path, final long nowMillis, final List<Entry> entries)
            throws IOException {
        entries.addAll(read(path).values().stream()
                .filter(entry -> nowMillis - entry.acceptedAtMillis() < KEEP_MILLIS)
                .toList());
        Files.createDirectories(path.getParent());
        final Path next = path.resolveSibling(path.getFileName() + ".next");
        final List<String> lines = new ArrayList<>();
        for (final Entry entry : entries) {
            lines.add(MAPPER.writeValueAsString(entry));
        }
        Files.write(next, lines, StandardCharsets.UTF_8);
        Files.move(next, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return new SessionsFile(path);
    }

    void append(final Entry entry) {
        try {
            Files.writeString(
                    this.path,
                    MAPPER.writeValueAsString(entry) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't record session " + entry.sessionId() + " in " + this.path, e);
        }
    }

    /** The newest entry per slot; a line that can't be read (one torn by a crash) is skipped. */
    private static Map<Long, Entry> read(final Path path) throws IOException {
        final Map<Long, Entry> entries = new LinkedHashMap<>();
        if (!Files.exists(path)) {
            return entries;
        }
        for (final String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            try {
                final Entry entry = MAPPER.readValue(line, new TypeReference<Entry>() {});
                entries.put(entry.slot(), entry);
            } catch (IOException e) {
                // A torn last line from a crash mid-write: the entry before it stands.
            }
        }
        return entries;
    }
}
