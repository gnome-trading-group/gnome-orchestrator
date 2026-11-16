package group.gnometrading.orchestrator.gateway.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionRegistryTest {

    @Test
    void oneLocalRecordAnswersEveryHello() throws Exception {
        final SessionRegistry.Source source =
                SessionRegistry.local("{\"session_seq\": 7, \"strategy_id\": 3, \"listings\": [11, 12]}");

        final SessionRegistry.SessionRecord record = source.fetch("anything");

        assertEquals(7, record.sessionSeq());
        assertEquals(3, record.strategyId());
        assertEquals(Set.of(11, 12), record.listingIds());
        assertEquals("paper", record.mode());
        assertEquals(record, source.fetch("something else"));
    }

    @Test
    void severalLocalRecordsAnswerEachByItsId() throws Exception {
        final SessionRegistry.Source source = SessionRegistry.local("["
                + "{\"session_id\": \"a\", \"session_seq\": 1, \"strategy_id\": 1, \"listings\": [11]},"
                + "{\"session_id\": \"b\", \"session_seq\": 2, \"strategy_id\": 1, \"listings\": [12, 13]}]");

        assertEquals(1, source.fetch("a").sessionSeq());
        assertEquals(Set.of(12, 13), source.fetch("b").listingIds());
        assertNull(source.fetch("c"));
    }

    @Test
    void localRecordsAreReadFromAFileNamedWithAt(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("sessions.json");
        Files.writeString(
                file, "[{\"session_id\": \"a\", \"session_seq\": 4, \"strategy_id\": 1, \"listings\": [11]}]");

        assertEquals(4, SessionRegistry.local("@" + file).fetch("a").sessionSeq());
    }

    @Test
    void severalLocalSessionsMayNotShareASlot() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SessionRegistry.local("["
                        + "{\"session_id\": \"a\", \"session_seq\": 1, \"listings\": []},"
                        + "{\"session_id\": \"b\", \"session_seq\": 1, \"listings\": []}]"));
    }

    @Test
    void severalLocalSessionsEachNeedAnId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SessionRegistry.local("[{\"session_seq\": 1, \"listings\": []}]"));
    }
}
