package group.gnometrading.orchestrator.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.gateway.accounts.LaneAdmissions;
import group.gnometrading.orchestrator.gateway.simulated.SimulatedAccount;
import group.gnometrading.orchestrator.runtime.ClockReadings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The gateway's minutes as the controller reads them. */
class GatewayMinutesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    static Path dir;

    private final List<String> written = new ArrayList<>();
    private final GatewayMinutes minutes =
            new GatewayMinutes(new NullLogger(), "live:kalshi:main", "live", this.written::add, noClock());

    private static ClockReadings noClock() {
        return new ClockReadings(Path.of("/nonexistent/clock.status"), () -> 0L);
    }

    @Test
    void eachDocumentSaysHowFarTheHostsClockCanBeFromUtc() throws Exception {
        final Path file = dir.resolve("clock.status");
        Files.writeString(
                file,
                "written_ms 1000\nsource PHC\nstratum 1\noffset_ns 12\nerror_bound_ns 23617\nphc_error_bound_ns 18372\n");
        final GatewayMinutes timed = new GatewayMinutes(
                new NullLogger(), "live:kalshi:main", "live", this.written::add, new ClockReadings(file, () -> 3_500L));
        timed.publish(3_500);

        final JsonNode clock = MAPPER.readTree(this.written.get(0)).get("clock");
        assertEquals("PHC", clock.get("source").asText());
        assertEquals(23_617, clock.get("errorBoundNanos").asLong());
        assertEquals(12, clock.get("offsetNanos").asLong());
        assertEquals(2_500, clock.get("ageMs").asLong());
    }

    @Test
    void aHostWithNoClockStatusSaysSo() throws Exception {
        this.minutes.publish(1);
        final JsonNode clock = MAPPER.readTree(this.written.get(0)).get("clock");
        assertEquals("UNKNOWN", clock.get("source").asText());
        assertTrue(clock.get("errorBoundNanos").isNull());
    }

    private static LaneAdmissions.Minute minute(final long orders, final Map<Long, Long> sentBySlot) {
        return new LaneAdmissions.Minute(
                0, 4, 120, 900, orders, 300, 2_000, 2, 1, 0, 0, 30_000, 90_000, 25_000, 60_000, 0, 3, sentBySlot);
    }

    @Test
    void anAccountsMinuteCarriesItsNumbersAndEachSessionsSendsBusiestFirst() throws Exception {
        this.minutes.account(
                5, "steady-heron-01", minute(10, Map.of(11L, 3L, 12L, 7L)), 1_000, 2, slot -> "session-" + slot);
        this.minutes.publish(2_000);

        final JsonNode document = MAPPER.readTree(this.written.get(0));
        assertEquals("live:kalshi:main", document.get("gatewayKey").asText());
        assertEquals("live", document.get("mode").asText());
        assertEquals(2_000, document.get("writtenAt").asLong());
        final JsonNode account = document.get("accounts").get(0);
        assertEquals(5, account.get("accountId").asInt());
        assertEquals("steady-heron-01", account.get("name").asText());
        final JsonNode first = account.get("minutes").get(0);
        assertEquals(1_000, first.get("endedAt").asLong());
        assertEquals(10, first.get("orders").asLong());
        assertEquals(120, first.get("cancelWaitP99Us").asLong());
        assertEquals(90_000, first.get("orderAnswerP99Us").asLong());
        assertEquals(2, first.get("venueRefusals").asLong());
        final JsonNode sessions = account.get("sessions");
        assertEquals("session-12", sessions.get(0).get("sessionId").asText());
        assertEquals(7, sessions.get(0).get("sent").asLong());
        assertEquals(11, sessions.get(1).get("slot").asLong());
        assertFalse(document.has("paper"));
    }

    @Test
    void onlyTheLastHourIsKeptAndOnlyTheLatestMinutesSessions() throws Exception {
        for (int i = 0; i < GatewayMinutes.KEPT + 5; i++) {
            this.minutes.account(5, "a", minute(i, Map.of((long) i, 1L)), i, 0, slot -> null);
        }
        this.minutes.publish(0);

        final JsonNode account =
                MAPPER.readTree(this.written.get(0)).get("accounts").get(0);
        assertEquals(GatewayMinutes.KEPT, account.get("minutes").size());
        assertEquals(5, account.get("minutes").get(0).get("orders").asLong());
        assertEquals(1, account.get("sessions").size());
        assertTrue(account.get("sessions").get(0).get("sessionId").isNull(), "a slot no session holds now");
    }

    @Test
    void aPaperGatewaysMinutesCarryItsBooksLag() throws Exception {
        final GatewayMinutes paper =
                new GatewayMinutes(new NullLogger(), "paper:kalshi:main", "paper", this.written::add, noClock());
        paper.paper(new SimulatedAccount.Minute(3, 30, 42, 26), 5_000);
        paper.publish(6_000);

        final JsonNode document = MAPPER.readTree(this.written.get(0));
        assertEquals(0, document.get("accounts").size());
        final JsonNode minute = document.get("paper").get("minutes").get(0);
        assertEquals(30, minute.get("sessions").asInt());
        assertEquals(3, minute.get("workingOrders").asInt());
        assertEquals(42, minute.get("lagMaxMs").asLong());
        assertEquals(26, minute.get("refusedNotReady").asLong());
    }

    @Test
    void aWriteThatFailsIsLoggedNotThrownAndTheNextCarriesEverything() throws Exception {
        final List<String> after = new ArrayList<>();
        final boolean[] failing = {true};
        final GatewayMinutes flaky = new GatewayMinutes(
                new NullLogger(),
                "live:kalshi:main",
                "live",
                json -> {
                    if (failing[0]) {
                        throw new IllegalStateException("throttled");
                    }
                    after.add(json);
                },
                noClock());
        flaky.account(5, "a", minute(1, Map.of()), 1, 0, slot -> null);
        flaky.publish(1);
        failing[0] = false;
        flaky.account(5, "a", minute(2, Map.of()), 2, 0, slot -> null);
        flaky.publish(2);

        assertEquals(
                2,
                MAPPER.readTree(after.get(0))
                        .get("accounts")
                        .get(0)
                        .get("minutes")
                        .size());
    }
}
