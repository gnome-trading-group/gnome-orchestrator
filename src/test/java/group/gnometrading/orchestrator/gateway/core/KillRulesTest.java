package group.gnometrading.orchestrator.gateway.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.account.reader.PublishedMap;
import org.junit.jupiter.api.Test;

/** Which orders a kill covers: everything it names must match, and what it doesn't name is anything. */
class KillRulesTest {

    private static final int ACCOUNT = 4;
    private static final int EXCHANGE = 7;
    private static final long LISTING = PublishedMap.listingKey(EXCHANGE, 11);
    private static final long OTHER_LISTING = PublishedMap.listingKey(EXCHANGE, 12);
    private static final long ELSEWHERE = PublishedMap.listingKey(EXCHANGE + 1, 11);
    private static final int STRATEGY = 3;
    private static final long SLOT = 42;

    @Test
    void noKillsCoverNothing() {
        assertFalse(KillRules.NONE.covers(ACCOUNT, LISTING, STRATEGY, SLOT));
    }

    @Test
    void aKillNamingNothingCoversEverything() {
        final KillRules rules = rule(0, 0, 0, 0, 0);

        assertTrue(rules.covers(ACCOUNT, LISTING, STRATEGY, SLOT));
        assertEquals("everything", rules.describe(0));
    }

    @Test
    void aKillCoversOnlyWhatMatchesEveryTargetItNames() {
        final KillRules strategyOnListing = rule(0, 0, LISTING, STRATEGY, 0);

        assertTrue(strategyOnListing.covers(ACCOUNT, LISTING, STRATEGY, SLOT));
        assertFalse(strategyOnListing.covers(ACCOUNT, OTHER_LISTING, STRATEGY, SLOT), "another listing");
        assertFalse(strategyOnListing.covers(ACCOUNT, LISTING, STRATEGY + 1, SLOT), "another strategy");
    }

    @Test
    void anAccountKillCoversEveryOrderOnTheAccountAndNoOther() {
        final KillRules rules = rule(ACCOUNT, 0, 0, 0, 0);

        assertTrue(rules.covers(ACCOUNT, LISTING, STRATEGY, SLOT));
        assertTrue(rules.covers(ACCOUNT, OTHER_LISTING, STRATEGY + 1, SLOT + 1));
        assertFalse(rules.covers(ACCOUNT + 1, LISTING, STRATEGY, SLOT));
    }

    @Test
    void anExchangeKillCoversItsListingsByTheExchangeTheirKeyCarries() {
        final KillRules rules = rule(0, EXCHANGE, 0, 0, 0);

        assertTrue(rules.covers(ACCOUNT, LISTING, STRATEGY, SLOT));
        assertFalse(rules.covers(ACCOUNT, ELSEWHERE, STRATEGY, SLOT));
    }

    @Test
    void aSessionKillCoversOnlyThatSession() {
        final KillRules rules = rule(0, 0, 0, STRATEGY, SLOT);

        assertTrue(rules.covers(ACCOUNT, LISTING, STRATEGY, SLOT));
        assertFalse(rules.covers(ACCOUNT, LISTING, STRATEGY, SLOT + 1), "another session of the strategy");
    }

    private static KillRules rule(
            final int accountId, final int exchangeId, final long listingKey, final int strategyId, final long slot) {
        return new KillRules(
                new int[] {accountId},
                new int[] {exchangeId},
                new long[] {listingKey},
                new int[] {strategyId},
                new long[] {slot});
    }
}
