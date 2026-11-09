package group.gnometrading.orchestrator.gateway.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.orchestrator.gateway.core.OrderTotals;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionsFileTest {

    private static final long NOW = 1_800_000_000_000L;

    @TempDir
    Path directory;

    private static SessionsFile.Entry entry(final long slot, final String id, final long acceptedAt) {
        return new SessionsFile.Entry(
                slot, id, 3, 5, 2_000, acceptedAt, "RUNNING", "live", Set.of(70), Map.of(1, 5), 2);
    }

    @Test
    void theNewestEntryPerSlotCountsATornLineIsSkippedAndOldOnesAreDropped() throws Exception {
        final Path path = this.directory.resolve("sessions.jsonl");
        final SessionsFile file = SessionsFile.open(path, NOW, new ArrayList<>());
        file.append(entry(1, "a", NOW - 1_000));
        file.append(entry(1, "a-again", NOW - 500));
        file.append(entry(2, "old", NOW - SessionsFile.KEEP_MILLIS - 1));
        file.append(entry(3, "c", NOW - 100));
        Files.writeString(path, "{\"slot\":4,\"sessionId\":\"to", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        final List<SessionsFile.Entry> entries = new ArrayList<>();
        SessionsFile.open(path, NOW, entries);
        assertEquals(
                List.of("a-again", "c"),
                entries.stream().map(SessionsFile.Entry::sessionId).toList());
        assertEquals(Set.of(70), entries.get(0).record().listingIds());

        final List<SessionsFile.Entry> reread = new ArrayList<>();
        SessionsFile.open(path, NOW, reread);
        assertEquals(entries, reread, "the rewritten file holds just those");
    }

    @Test
    void notionalDoesntOverflowAtAnyRealSize() {
        assertEquals(800_000_000L, OrderTotals.notional(2_000_000L, 400_000_000L));
        assertEquals(1_500_000L * 999_000_000L, OrderTotals.notional(1_500_000_000_000L, 999_000_000L));
        assertEquals(500_000L, OrderTotals.notional(500_000L, 1_000_000L), "half a unit at a tenth of a cent");
    }
}
