package group.gnometrading.orchestrator.session.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.gateway.Mode;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** How a session's order entry is wired: one gateway link per venue, named for the session's mode. */
class SessionOrdersTest {

    private final AeronRuntime aeron =
            AeronRuntime.launch("session-orders-test", AeronProfile.GATEWAY, Throwable::printStackTrace);
    private final GlobalSequence sequence = new GlobalSequence();
    private final SequencedRingBuffer<Intent> orders = new SequencedRingBuffer<>(Intent::new, this.sequence);
    private final SequencedRingBuffer<OrderExecutionReport> reports =
            new SequencedRingBuffer<>(OrderExecutionReport::new, this.sequence);
    private SessionOrders sessionOrders;

    @AfterEach
    void tearDown() throws Exception {
        if (this.sessionOrders != null) {
            this.sessionOrders.close();
        }
        this.aeron.close();
    }

    @Test
    void aPaperSessionLinksToEachVenuesPaperGatewayOnTheSimulatedAccountAndAsksNoVenueAtRecovery() throws Exception {
        this.sessionOrders = orders("--mode=paper", "--session.seq=7");
        final Map<Integer, VenueOrderQuery> venueQueries = new HashMap<>();

        this.sessionOrders.wire(
                List.of(listing(1, 5, "KALSHI"), listing(2, 5, "KALSHI"), listing(3, 4, "POLYMARKET_INTL")),
                this.orders,
                this.reports,
                venueQueries);

        final List<GatewayLink> links = links();
        assertEquals(List.of("paper:kalshi:main", "paper:polymarket_intl:main"), keys(links));
        for (final GatewayLink link : links) {
            assertEquals(Mode.PAPER, link.mode);
            assertEquals(0, link.venueAccountId);
        }
        assertEquals(2, links.get(0).listings.size(), "both Kalshi listings on the one link");
        assertTrue(venueQueries.isEmpty(), "the simulated venue holds nothing a restart could ask about");
    }

    @Test
    void aLocalRunWithoutARegistrySessionMustNameItsSlot() throws Exception {
        this.sessionOrders = orders("--mode=paper", "--session.seq=0");

        final IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> this.sessionOrders.wire(
                        List.of(listing(1, 5, "KALSHI")), this.orders, this.reports, new HashMap<>()));
        assertTrue(refused.getMessage().contains("session.seq"), refused.getMessage());
    }

    private SessionOrders orders(final String... overrides) throws Exception {
        final String[] args = new String[overrides.length + 2];
        System.arraycopy(overrides, 0, args, 0, overrides.length);
        args[overrides.length] = "--strategy.id=3";
        // A local gateway address: nothing here asks the registry for one.
        args[overrides.length + 1] = "--order.gateway.local=127.0.0.1:40999";
        final Properties properties = new Properties("orchestrator.dev.properties", args);
        return new SessionOrders(
                new NullLogger(),
                properties,
                new SystemEpochNanoClock(),
                () -> {
                    throw new AssertionError("a local paper session asks the registry nothing");
                },
                this.aeron);
    }

    @SuppressWarnings("unchecked")
    private List<GatewayLink> links() {
        return (List<GatewayLink>) this.sessionOrders.gatewayLinks();
    }

    private static List<String> keys(final List<GatewayLink> links) {
        return links.stream().map(link -> link.gatewayKey).toList();
    }

    private static Listing listing(final int listingId, final int exchangeId, final String exchangeCode) {
        return new Listing(
                listingId,
                new Exchange(exchangeId, exchangeCode, exchangeCode, "us-east-1"),
                new Security(100 + listingId, "S", null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                "L" + listingId,
                "L" + listingId);
    }
}
