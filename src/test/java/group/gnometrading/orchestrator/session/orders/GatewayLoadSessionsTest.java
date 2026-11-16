package group.gnometrading.orchestrator.session.orders;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.orchestrator.gateway.control.SessionRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayLoadSessionsTest {

    @Test
    void sessionsTakeTheirListingsAndAccountsInTurnWrapping() {
        final LoadSessions.Spec spec = new LoadSessions.Spec(
                "s", 1, 1, "paper:kalshi:load", 2, new long[] {10, 20, 30}, 2, 5, 2, 1, 1, 1, 1, 1, "h", 1, "r");

        assertArrayEquals(new long[] {10, 20}, spec.securitiesOf(0));
        assertArrayEquals(new long[] {30, 10}, spec.securitiesOf(1));
        assertEquals(5, spec.accountOf(0));
        assertEquals(6, spec.accountOf(1));
        assertEquals(5, spec.accountOf(2));
    }

    @Test
    void theGatewaysRecordsNameEachSessionsListings(@TempDir final Path dir) throws Exception {
        final Path listingsFile = dir.resolve("listings.txt");
        Files.writeString(listingsFile, "# listing security\n101 10\n102 20\n\n103 30\n");
        final List<long[]> listings = GatewayLoadSessions.listings(listingsFile);
        final Path sessions = dir.resolve("sessions.json");

        GatewayLoadSessions.writeSessions(sessions, listings, 2, 2, "load-", 1_000, 7, "2026-11-01T00:00:00Z");

        final SessionRegistry.Source source = SessionRegistry.local("@" + sessions);
        assertEquals(1_000, source.fetch("load-0").sessionSeq());
        assertEquals(Set.of(101, 102), source.fetch("load-0").listingIds());
        assertEquals(Set.of(103, 101), source.fetch("load-1").listingIds());
        assertEquals(7, source.fetch("load-1").strategyId());
    }
}
