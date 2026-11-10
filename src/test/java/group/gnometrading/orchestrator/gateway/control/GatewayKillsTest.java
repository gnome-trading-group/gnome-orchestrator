package group.gnometrading.orchestrator.gateway.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.AccountState;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.gateway.RecordingAlerts;
import group.gnometrading.orchestrator.gateway.core.AccountLink;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.Kills;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The kill poller over a kills file: what it publishes for ingress, and which orders it asks the lanes to cancel. */
class GatewayKillsTest {

    private static final int ACCOUNT_ID = 4;
    private static final int EXCHANGE_ID = 7;
    private static final int LISTING_ID = 500;
    private static final long LISTING_KEY = PublishedMap.listingKey(EXCHANGE_ID, 11);

    @TempDir
    Path directory;

    private final long[] nowNanos = {TimeUnit.SECONDS.toNanos(1)};
    private final Account account = new Account();
    private final SessionTable sessions = new SessionTable();
    private final Kills kills = new Kills();
    private final RecordingAlerts alerts = new RecordingAlerts();
    private GatewayKills gatewayKills;
    private Path file;

    private void start(final KillSource source) {
        this.sessions.add(session(42, "session-a", 3));
        this.sessions.add(session(43, "session-b", 4));
        this.gatewayKills = new GatewayKills(
                new NullLogger(),
                this.alerts,
                source,
                this.kills,
                this.sessions,
                listingId -> listingId == LISTING_ID ? LISTING_KEY : 0,
                () -> this.nowNanos[0]);
    }

    private void startOnFile() {
        this.file = this.directory.resolve("kills.json");
        start(KillSource.file(this.file));
    }

    @Test
    void killsUnreadForThirtySecondsRaiseAnAlertOnceThatTheNextGoodReadResolves() {
        final boolean[] down = {false};
        start(() -> {
            if (down[0]) {
                throw new IOException("registry down");
            }
            return List.of();
        });
        this.gatewayKills.run();
        down[0] = true;
        this.nowNanos[0] += GatewayKills.STALE_NANOS / 2;
        this.gatewayKills.run();
        assertTrue(this.alerts.said.isEmpty(), "not yet stale");

        this.nowNanos[0] += GatewayKills.STALE_NANOS;
        this.gatewayKills.run();
        this.nowNanos[0] += TimeUnit.SECONDS.toNanos(1);
        this.gatewayKills.run();
        assertEquals(List.of("FIRING GATEWAY_KILLS_STALE"), this.alerts.said, "raised once");

        down[0] = false;
        this.gatewayKills.run();
        assertEquals(List.of("FIRING GATEWAY_KILLS_STALE", "RESOLVED GATEWAY_KILLS_STALE"), this.alerts.said);
    }

    @Test
    void noKillsCancelNothing() {
        startOnFile();

        this.gatewayKills.run();

        assertEquals(0, this.kills.rules().size());
        assertTrue(commands().isEmpty());
    }

    @Test
    void aStrategyKillOnAListingCancelsOnlyThatStrategysOrdersThere() throws IOException {
        startOnFile();
        write("[{\"policy_type\":\"KILL_SWITCH\",\"strategy_id\":3,\"listing_id\":" + LISTING_ID + "}]");

        this.gatewayKills.run();

        assertTrue(this.kills.rules().covers(ACCOUNT_ID, LISTING_KEY, 3, 42));
        assertFalse(this.kills.rules().covers(ACCOUNT_ID, LISTING_KEY, 4, 43), "the other strategy");
        assertEquals(List.of("CANCEL_ORDERS 42 7/11"), commands());
    }

    @Test
    void anAccountKillCancelsEverySessionsOrdersOnIt() throws IOException {
        startOnFile();
        write("[{\"policy_type\":\"KILL_SWITCH\",\"account_id\":" + ACCOUNT_ID + "}]");

        this.gatewayKills.run();

        assertEquals(List.of("CANCEL_ORDERS 42 0/0", "CANCEL_ORDERS 43 0/0"), commands());
    }

    @Test
    void aKillOfAnotherAccountOrExchangeCancelsNothingHere() throws IOException {
        startOnFile();
        write("[{\"account_id\":" + (ACCOUNT_ID + 1) + "},{\"exchange_id\":" + (EXCHANGE_ID + 1) + "}]");

        this.gatewayKills.run();

        assertTrue(commands().isEmpty());
    }

    @Test
    void aKillIsSweptOnItsFirstTwoPassesToCatchAnOrderThatSlippedPastThenNoMore() throws IOException {
        startOnFile();
        write("[{\"session_id\":\"session-a\",\"strategy_id\":3}]");

        this.gatewayKills.run();
        this.gatewayKills.run();
        this.gatewayKills.run();

        assertEquals(List.of("CANCEL_ORDERS 42 0/0", "CANCEL_ORDERS 42 0/0"), commands());
    }

    @Test
    void aKillLiftedAndPutBackIsSweptAgain() throws IOException {
        startOnFile();
        write("[{\"strategy_id\":3}]");
        this.gatewayKills.run();
        this.gatewayKills.run();
        write("[]");
        this.gatewayKills.run();
        commands();

        write("[{\"strategy_id\":3}]");
        this.gatewayKills.run();

        assertEquals(List.of("CANCEL_ORDERS 42 0/0"), commands());
    }

    @Test
    void untilTheFirstReadTheKillsAreUnknownNotNone() {
        startOnFile();

        assertFalse(this.kills.read(), "unread");
        this.gatewayKills.run();
        assertTrue(this.kills.read(), "read, and found none");
    }

    @Test
    void aKillTheGatewayCantPlaceYetCoversNothingUntilItCan() throws IOException {
        startOnFile();
        write("[{\"session_id\":\"session-z\",\"strategy_id\":9},{\"listing_id\":999}]");

        this.gatewayKills.run();

        assertEquals(0, this.kills.rules().size());
    }

    @Test
    void onlyEnabledKillSwitchesAreKills() throws IOException {
        startOnFile();
        write("[{\"policy_type\":\"MAX_POSITION\",\"strategy_id\":3},"
                + "{\"policy_type\":\"KILL_SWITCH\",\"strategy_id\":3,\"enabled\":false}]");

        this.gatewayKills.run();

        assertEquals(0, this.kills.rules().size());
    }

    @Test
    void aKillLiftedFromTheSourceIsLiftedHere() throws IOException {
        startOnFile();
        write("[{\"strategy_id\":3}]");
        this.gatewayKills.run();
        assertEquals(1, this.kills.rules().size());

        write("[]");
        this.gatewayKills.run();

        assertEquals(0, this.kills.rules().size());
    }

    @Test
    void aFailedReadKeepsTheKillsInForce() throws IOException {
        startOnFile();
        write("[{\"strategy_id\":3}]");
        this.gatewayKills.run();

        write("not json");
        this.nowNanos[0] += TimeUnit.SECONDS.toNanos(60);
        this.gatewayKills.run();

        assertTrue(this.kills.rules().covers(ACCOUNT_ID, LISTING_KEY, 3, 42));
    }

    private void write(final String json) throws IOException {
        Files.writeString(this.file, json);
    }

    private GatewaySession session(final long slot, final String sessionId, final int strategyId) {
        return new GatewaySession(slot, sessionId, strategyId, this.account, 64);
    }

    private List<String> commands() {
        final List<String> seen = new ArrayList<>();
        this.account.commands.read(
                command ->
                        seen.add((command.kind == LaneCommand.CANCEL_ORDERS ? "CANCEL_ORDERS" : "kind " + command.kind)
                                + " " + command.sessionSlot + " " + command.exchangeId + "/" + command.securityId),
                Integer.MAX_VALUE);
        return seen;
    }

    private static final class Account implements AccountLink {
        final ManyToOneRingBuffer<LaneCommand> commands =
                new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, 64);

        @Override
        public int accountId() {
            return ACCOUNT_ID;
        }

        @Override
        public int exchangeId() {
            return EXCHANGE_ID;
        }

        @Override
        public ManyToOneRingBuffer<LaneCommand> commands() {
            return this.commands;
        }

        @Override
        public AccountState state() {
            return AccountState.READY;
        }

        @Override
        public int workingOrders() {
            return 0;
        }

        @Override
        public Markets markets() {
            throw new UnsupportedOperationException("the kill poller loads no listings");
        }

        @Override
        public VenueOrderQuery orderQuery() {
            throw new UnsupportedOperationException("the kill poller asks the venue nothing");
        }
    }
}
