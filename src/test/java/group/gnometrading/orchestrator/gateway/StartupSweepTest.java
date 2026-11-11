package group.gnometrading.orchestrator.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.orders.recovery.OrderOwner;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.gateway.core.GatewayAlerts;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StartupSweepTest {

    private static final long STARTED = 1_000_000L;
    private static final long WAIT = 30_000L;

    private final FakeOrderQuery venue = new FakeOrderQuery();
    private final List<String> firing = new ArrayList<>();
    private final List<String> resolved = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private final List<Map<String, String>> labels = new ArrayList<>();
    // The registry's sessions by session_seq.
    private final Map<Long, String> sessions = new HashMap<>();
    private final GatewayAlerts alerts = new GatewayAlerts() {
        @Override
        public void firing(
                final String type, final String about, final String title, final Map<String, String> labels) {
            StartupSweepTest.this.firing.add(type + " " + about);
            StartupSweepTest.this.titles.add(title);
            StartupSweepTest.this.labels.add(labels);
        }

        @Override
        public void resolved(final String type, final String about) {
            StartupSweepTest.this.resolved.add(type + " " + about);
        }
    };
    private long now = STARTED;
    private final List<Long> slept = new ArrayList<>();

    private void resting(final String id, final long createdMillis) {
        this.venue.orders.put(id, new VenueOrder(id, "venue-" + id, 0, 0, 0, false));
        this.venue.created.put(id, createdMillis);
    }

    private void sweep(final Set<String> held) {
        new StartupSweep(
                        new NullLogger(),
                        this.alerts,
                        List.of(new StartupSweep.Account("account-1", this.venue, held)),
                        STARTED,
                        WAIT,
                        () -> this.now,
                        millis -> {
                            this.slept.add(millis);
                            this.now += millis;
                        },
                        this.sessions::get)
                .run();
    }

    @Test
    void cancelsWhatAnEarlierProcessLeftWithNoRecordOnceSessionsHadTheirChance() {
        resting("lost", STARTED - 60_000);
        sweep(Set.of());

        assertEquals(List.of(WAIT), this.slept, "waits out the sessions' window first");
        assertEquals(List.of("lost"), this.venue.cancelled);
        assertEquals(List.of("GATEWAY_UNATTRIBUTED_ORDERS account-1:lost"), this.firing);
    }

    @Test
    void namesAnOrderWhoseIdNamesASlotByItsSession_OrByTheSlotWhenTheRegistryCantSay() {
        resting("2100-03-04T12:00:00.001Z", STARTED - 60_000);
        this.venue.owners.put("2100-03-04T12:00:00.001Z", new OrderOwner(540, 1));
        resting("2100-03-04T12:00:00.002Z", STARTED - 60_000);
        this.venue.owners.put("2100-03-04T12:00:00.002Z", new OrderOwner(541, 2));
        resting("abc-7", STARTED - 60_000);
        this.sessions.put(540L, "44f613b6");
        sweep(Set.of());

        assertEquals(3, this.titles.size());
        final Map<String, String> bySession = labelsOf("2100-03-04T12:00:00.001Z");
        assertTrue(titleOf("2100-03-04T12:00:00.001Z").contains("order 44f613b6-1 on MARKET"));
        assertEquals("44f613b6", bySession.get("session"));
        assertEquals("1", bySession.get("counter"));
        assertTrue(titleOf("2100-03-04T12:00:00.002Z").contains("order slot 541 order 2 (2100-03-04T12:00:00.002Z)"));
        assertEquals(null, labelsOf("2100-03-04T12:00:00.002Z").get("session"));
        assertTrue(
                titleOf("abc-7").contains("order abc-7 on MARKET"), "a venue id that names no slot is used as it is");
        assertTrue(
                this.firing.contains("GATEWAY_UNATTRIBUTED_ORDERS account-1:2100-03-04T12:00:00.001Z"),
                "keyed by the venue's id whatever the name");
    }

    private String titleOf(final String order) {
        return this.titles.get(indexOf(order));
    }

    private Map<String, String> labelsOf(final String order) {
        return this.labels.get(indexOf(order));
    }

    private int indexOf(final String order) {
        return this.firing.indexOf("GATEWAY_UNATTRIBUTED_ORDERS account-1:" + order);
    }

    @Test
    void leavesOrdersTheRestartHoldsAndOrdersThisProcessPlaced() {
        resting("held", STARTED - 60_000);
        resting("mine", STARTED + 5_000);
        sweep(Set.of("held"));

        assertTrue(this.venue.cancelled.isEmpty());
        assertTrue(this.firing.isEmpty());
    }

    @Test
    void triesAgainUntilTheVenueAnswersAndAlertsWhileItCannot() {
        resting("lost", STARTED - 60_000);
        this.venue.listAllFails = new IOException("venue down");
        new StartupSweep(
                        new NullLogger(),
                        this.alerts,
                        List.of(new StartupSweep.Account("account-1", this.venue, Set.of())),
                        STARTED,
                        WAIT,
                        () -> this.now,
                        millis -> {
                            this.now += millis;
                            if (this.now - STARTED - WAIT
                                    > StartupSweep.ALERT_AFTER_MILLIS + StartupSweep.RETRY_MILLIS) {
                                this.venue.listAllFails = null;
                            }
                        },
                        this.sessions::get)
                .run();

        assertEquals(
                List.of("GATEWAY_SWEEP_FAILED account-1", "GATEWAY_UNATTRIBUTED_ORDERS account-1:lost"), this.firing);
        assertEquals(List.of("GATEWAY_SWEEP_FAILED account-1"), this.resolved);
        assertEquals(List.of("lost"), this.venue.cancelled);
    }
}
