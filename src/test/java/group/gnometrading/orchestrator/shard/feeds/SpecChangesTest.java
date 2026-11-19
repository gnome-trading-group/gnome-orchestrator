package group.gnometrading.orchestrator.shard.feeds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.RegistryConnection;
import group.gnometrading.gateways.feed.SpecChangeNotices;
import group.gnometrading.logging.NullLogger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpecChangesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RegistryConnection registry = mock(RegistryConnection.class);
    private final List<JsonNode> posted = new ArrayList<>();
    private static final long VENUE_NANOS = 1_782_753_357_257_000_000L;
    private static final long HOUR_NANOS = 3_600_000_000_000L;

    private long now = VENUE_NANOS + 1_000_000_000L;
    private final SpecChanges changes = new SpecChanges(new NullLogger(), registry, 128, () -> now);

    private void answer(final int status) {
        when(registry.tryPost(any(), any(), anyInt())).thenAnswer(call -> {
            final byte[] body = call.getArgument(1);
            posted.add(MAPPER.readTree(new String(body, 0, (int) call.getArgument(2), StandardCharsets.UTF_8)));
            return status;
        });
    }

    private static void offer(final SpecChangeNotices notices, final String id, final long tick, final long nanos) {
        final byte[] bytes = id.getBytes(StandardCharsets.US_ASCII);
        notices.offer(bytes, bytes.length, tick, nanos);
    }

    @Test
    void aNoticeAsksTheRegistryToReadItsListingAtTheVenuesTime() throws Exception {
        answer(202);
        offer(changes.notices("POLYMARKET_INTL"), "condition-1:token-yes", 1_000_000, 1_782_753_357_257_000_000L);

        changes.post();

        assertEquals(1, posted.size());
        assertEquals(
                MAPPER.readTree(
                        "[{\"exchangeCode\":\"POLYMARKET_INTL\",\"exchangeSecurityId\":\"condition-1:token-yes\","
                                + "\"observedAt\":\"2026-06-29T17:15:57.257Z\",\"newTick\":1000000}]"),
                posted.get(0));
    }

    @Test
    void noticesGoAtMostFiftyAPostAndOnlyOnce() {
        answer(202);
        final SpecChangeNotices notices = changes.notices("POLYMARKET_INTL");
        for (int i = 0; i < 60; i++) {
            offer(notices, "c:" + i, 1_000_000, 1_782_753_357_257_000_000L);
        }

        changes.post();
        changes.post();

        assertEquals(2, posted.size());
        assertEquals(50, posted.get(0).size());
        assertEquals(10, posted.get(1).size());
    }

    @Test
    void aVenueClockAheadOfOursIsDatedByOursSoTheBatchIsNotRefused() {
        answer(202);
        now = VENUE_NANOS - 10 * 60_000_000_000L;
        offer(changes.notices("POLYMARKET_INTL"), "c:t", 1_000_000, VENUE_NANOS);

        changes.post();

        assertEquals(
                "2026-06-29T17:05:57.257Z",
                posted.get(0).get(0).get("observedAt").asText());
    }

    @Test
    void aNoticeOverADayOldStaysBehindAndTheRestGo() {
        answer(202);
        now = VENUE_NANOS + 25 * HOUR_NANOS;
        final SpecChangeNotices notices = changes.notices("POLYMARKET_INTL");
        offer(notices, "c:old", 1_000_000, VENUE_NANOS);
        offer(notices, "c:new", 1_000_000, now - HOUR_NANOS);

        changes.post();

        assertEquals(1, posted.size());
        assertEquals(1, posted.get(0).size());
        assertEquals("c:new", posted.get(0).get(0).get("exchangeSecurityId").asText());
    }

    @Test
    void aRefusedPostIsNotSentAgain() {
        answer(500);
        offer(changes.notices("POLYMARKET_INTL"), "c:t", 1_000_000, 1_782_753_357_257_000_000L);

        changes.post();
        changes.post();

        assertEquals(1, posted.size());
    }

    @Test
    void withNowhereToSendThemFeedsQueueNoNotices() {
        assertNull(SpecChanges.NONE.notices("POLYMARKET_INTL"));
        SpecChanges.NONE.post();
    }
}
