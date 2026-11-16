package group.gnometrading.orchestrator.session.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.logging.NullLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GatewayLinksTest {

    private long now;
    private final List<String> wanted = new ArrayList<>();
    private final GatewayLink link = new GatewayLink("live:kalshi:main", 2, 5, List.of(), 8);
    // No gateway found: the link stays down, so nothing touches the media driver.
    private final GatewayLinks links = new GatewayLinks(
            new NullLogger(), null, "127.0.0.1", List.of(this.link), key -> null, this.wanted::add, () -> this.now);

    @Test
    void aLinkWaitingForItsGatewayAsksForItEveryTenSecondsAndALinkedOneEveryMinute() {
        this.links.run();
        at(TimeUnit.SECONDS.toNanos(5));
        assertEquals(1, this.wanted.size(), "asked at once, then not again within ten seconds");

        at(GatewayLinks.WANT_WAITING_NANOS);
        assertEquals(2, this.wanted.size());

        this.link.state = GatewayLink.READY;
        at(2 * GatewayLinks.WANT_WAITING_NANOS);
        assertEquals(3, this.wanted.size(), "the ask that was due");
        at(2 * GatewayLinks.WANT_WAITING_NANOS + TimeUnit.SECONDS.toNanos(30));
        assertEquals(3, this.wanted.size(), "linked: not again within the minute");
        at(2 * GatewayLinks.WANT_WAITING_NANOS + GatewayLinks.WANT_READY_NANOS);
        assertEquals(4, this.wanted.size());
        assertEquals(
                List.of("live:kalshi:main"), this.wanted.stream().distinct().toList());
    }

    @Test
    void anAskThatFailsIsLoggedAndAskedAgainWhenNextDue() {
        final int[] tries = {0};
        final GatewayLinks failing = new GatewayLinks(
                new NullLogger(),
                null,
                "127.0.0.1",
                List.of(this.link),
                key -> null,
                key -> {
                    tries[0]++;
                    throw new IllegalStateException("registry down");
                },
                () -> this.now);
        failing.run();
        this.now = GatewayLinks.WANT_WAITING_NANOS;
        failing.run();

        assertEquals(2, tries[0]);
    }

    @Test
    void aPaperLinkMovesToTheGatewayChosenForItAndIsWantedThere() {
        final GatewayLink paper = new GatewayLink("paper:kalshi:main", 2, 0, List.of(new long[] {2, 11}), 8);
        final List<String> asked = new ArrayList<>();
        final List<String> resolved = new ArrayList<>();
        final GatewayLinks choosing = new GatewayLinks(
                new NullLogger(),
                null,
                "127.0.0.1",
                List.of(paper),
                key -> {
                    resolved.add(key);
                    return null;
                },
                this.wanted::add,
                (link, linkedKey, linkedStartedAtMs) -> {
                    asked.add(linkedKey + "@" + linkedStartedAtMs);
                    return "paper:kalshi:2";
                },
                () -> this.now);

        choosing.run();
        this.now = TimeUnit.SECONDS.toNanos(1);
        choosing.run();

        assertEquals("paper:kalshi:2", paper.gatewayKey);
        assertEquals(List.of("null@0"), asked, "asked once: not again within ten seconds");
        assertEquals(List.of("paper:kalshi:2", "paper:kalshi:2"), resolved);
        assertEquals(List.of("paper:kalshi:main", "paper:kalshi:2"), this.wanted, "wanted under its new key at once");
    }

    @Test
    void aPaperLinkThatGoesDownNamesTheGatewayAndProcessThatLastWelcomedIt() {
        final GatewayLink paper = new GatewayLink("paper:kalshi:2", 2, 0, List.of(new long[] {2, 11}), 8);
        paper.connectedStartedAtMs = 1_793_000_000_000L;
        paper.state = GatewayLink.READY;
        final List<String> asked = new ArrayList<>();
        final GatewayLinks choosing = new GatewayLinks(
                new NullLogger(),
                null,
                "127.0.0.1",
                List.of(paper),
                key -> null,
                GatewayLinks.Wanting.NONE,
                (link, linkedKey, linkedStartedAtMs) -> {
                    asked.add(linkedKey + "@" + linkedStartedAtMs);
                    return null;
                },
                () -> this.now);
        choosing.run();

        paper.state = GatewayLink.DOWN;
        this.now = GatewayLinks.CHOOSE_NANOS;
        choosing.run();

        assertEquals(List.of("paper:kalshi:2@1793000000000"), asked);
        assertEquals("paper:kalshi:2", paper.gatewayKey, "kept: the chooser named none");
    }

    @Test
    void aFailedChoiceKeepsTheKeyItHas() {
        final GatewayLink paper = new GatewayLink("paper:kalshi:main", 2, 0, List.of(), 8);
        final GatewayLinks choosing = new GatewayLinks(
                new NullLogger(),
                null,
                "127.0.0.1",
                List.of(paper),
                key -> null,
                GatewayLinks.Wanting.NONE,
                (link, linkedKey, linkedStartedAtMs) -> {
                    throw new IllegalStateException("registry down");
                },
                () -> this.now);

        choosing.run();

        assertEquals("paper:kalshi:main", paper.gatewayKey);
    }

    private void at(final long nanos) {
        this.now = nanos;
        this.links.run();
    }
}
