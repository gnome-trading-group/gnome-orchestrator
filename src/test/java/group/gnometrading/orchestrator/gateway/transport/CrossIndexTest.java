package group.gnometrading.orchestrator.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.orders.CrossMarket;
import group.gnometrading.orchestrator.gateway.core.CrossKey;
import group.gnometrading.orchestrator.gateway.core.CrossKeys;
import group.gnometrading.schemas.Side;
import org.junit.jupiter.api.Test;

/** The self-trade index on its own: which orders meet another session's, and when an order stops counting. */
class CrossIndexTest {

    private static final int EXCHANGE = 7;
    private static final long YES = PublishedMap.listingKey(EXCHANGE, 1);
    private static final long NO = PublishedMap.listingKey(EXCHANGE, 2);
    private static final long OTHER_MARKET = PublishedMap.listingKey(EXCHANGE, 3);
    private static final long A = 1;
    private static final long B = 2;
    private static final long CENTS = CrossIndex.FULL / 100;

    private final CrossKeys keys = new CrossKeys();
    private CrossIndex index = new CrossIndex(this.keys, CrossIndex.Mode.ENFORCE, 16, 4);

    CrossIndexTest() {
        this.keys.put(YES, CrossKey.of(EXCHANGE, new CrossMarket("TICKER", false)));
        this.keys.put(NO, CrossKey.of(EXCHANGE, new CrossMarket("TICKER", true)));
        this.keys.put(OTHER_MARKET, CrossKey.of(EXCHANGE, new CrossMarket("OTHER", false)));
    }

    @Test
    void offChecksNothingAndHoldsNothing() {
        this.index = new CrossIndex(this.keys, CrossIndex.Mode.OFF, 16, 4);
        send(A, 1, YES, Side.Bid, 40 * CENTS);

        assertEquals(CrossIndex.CLEAR, this.index.checkNew(B, YES, Side.Ask, 30 * CENTS));
        assertEquals(CrossIndex.CLEAR, this.index.checkAmend(A, 1, 20 * CENTS));
        assertEquals(0, this.index.size(), "a paper gateway's sessions trade on books of their own");
        assertEquals(0, this.index.refused());
    }

    @Test
    void aSellAtOrBelowAnotherSessionsBidCrossesIt() {
        send(A, 1, YES, Side.Bid, 40 * CENTS);

        assertEquals(CrossIndex.CROSSES, this.index.checkNew(B, YES, Side.Ask, 40 * CENTS));
        assertEquals(CrossIndex.CROSSES, this.index.checkNew(B, YES, Side.Ask, 30 * CENTS));
        assertEquals(CrossIndex.CLEAR, this.index.checkNew(B, YES, Side.Ask, 41 * CENTS));
        assertEquals(CrossIndex.CLEAR, this.index.checkNew(B, YES, Side.Bid, 50 * CENTS), "a bid meets no bid");
        assertEquals(2, this.index.refused());
    }

    @Test
    void anOrderOnTheComplementListingCrossesAtOneMinusItsPrice() {
        send(A, 1, YES, Side.Bid, 40 * CENTS);

        // Buying NO at 60 is selling YES at 40.
        assertEquals(CrossIndex.CROSSES, this.index.checkNew(B, NO, Side.Bid, 60 * CENTS));
        assertEquals(CrossIndex.CLEAR, this.index.checkNew(B, NO, Side.Bid, 59 * CENTS));
    }

    @Test
    void aSessionsOwnOrdersAndOtherMarketsNeverCount() {
        send(A, 1, YES, Side.Bid, 40 * CENTS);

        assertEquals(CrossIndex.CLEAR, this.index.checkNew(A, YES, Side.Ask, 30 * CENTS));
        assertEquals(CrossIndex.CLEAR, this.index.checkNew(B, OTHER_MARKET, Side.Ask, 30 * CENTS));
    }

    @Test
    void anOrderCountsFromItsAdmissionNotItsAcknowledgement() {
        send(A, 1, YES, Side.Bid, 40 * CENTS);

        // Nothing from the venue yet, and a crossing order from another session in the same pass.
        assertEquals(CrossIndex.CROSSES, this.index.checkNew(B, YES, Side.Ask, 40 * CENTS));
    }

    @Test
    void anEndedOrderStopsCounting() {
        send(A, 1, YES, Side.Bid, 40 * CENTS);

        this.index.ended(A, 1);
        this.index.flush();
        this.index.drain();

        assertEquals(CrossIndex.CLEAR, this.index.checkNew(B, YES, Side.Ask, 40 * CENTS));
        assertEquals(0, this.index.size());
    }

    @Test
    void anAmendCountsAtBothPricesUntilTheVenueTakesIt() {
        send(A, 1, YES, Side.Bid, 40 * CENTS);
        assertEquals(CrossIndex.CLEAR, this.index.checkAmend(A, 1, 30 * CENTS));
        this.index.amend(A, 1, 30 * CENTS);

        assertEquals(CrossIndex.CROSSES, this.index.checkNew(B, YES, Side.Ask, 35 * CENTS), "the old price still");

        this.index.amendAccepted(A, 1);
        this.index.flush();
        this.index.drain();

        assertEquals(CrossIndex.CLEAR, this.index.checkNew(B, YES, Side.Ask, 35 * CENTS));
        assertEquals(CrossIndex.CROSSES, this.index.checkNew(B, YES, Side.Ask, 30 * CENTS));
    }

    @Test
    void aRefusedAmendNoLongerCountsAtItsPrice() {
        send(A, 1, YES, Side.Bid, 40 * CENTS);
        this.index.amend(A, 1, 50 * CENTS);
        assertEquals(CrossIndex.CROSSES, this.index.checkNew(B, YES, Side.Ask, 45 * CENTS), "while it is out");

        this.index.requestRefused(A, 1);
        this.index.flush();
        this.index.drain();

        assertEquals(CrossIndex.CLEAR, this.index.checkNew(B, YES, Side.Ask, 45 * CENTS), "the refused price is gone");
        assertEquals(CrossIndex.CROSSES, this.index.checkNew(B, YES, Side.Ask, 40 * CENTS), "the real one stays");
    }

    @Test
    void aRefusalWhileACancelIsOutIsTakenAsTheCancelsKeepingBothPrices() {
        send(A, 1, YES, Side.Bid, 40 * CENTS);
        this.index.amend(A, 1, 50 * CENTS);
        this.index.cancelSent(A, 1);

        this.index.requestRefused(A, 1);
        this.index.flush();
        this.index.drain();

        assertEquals(CrossIndex.CROSSES, this.index.checkNew(B, YES, Side.Ask, 45 * CENTS), "either may still stand");

        // The next refusal is the amend's.
        this.index.requestRefused(A, 1);
        this.index.flush();
        this.index.drain();
        assertEquals(CrossIndex.CLEAR, this.index.checkNew(B, YES, Side.Ask, 45 * CENTS));
    }

    @Test
    void anAmendIntoAnotherSessionsOrderIsRefused() {
        send(A, 1, YES, Side.Bid, 40 * CENTS);
        send(B, 1, YES, Side.Ask, 60 * CENTS);

        assertEquals(CrossIndex.CROSSES, this.index.checkAmend(B, 1, 40 * CENTS));
        assertEquals(CrossIndex.CLEAR, this.index.checkAmend(B, 1, 41 * CENTS));
    }

    @Test
    void anEndTheRingHasNoRoomForWaitsAndArrivesLater() {
        for (int counter = 1; counter <= 8; counter++) {
            send(A, counter, YES, Side.Bid, counter * CENTS);
        }
        // The ring holds 4; the other 4 wait on egress's side.
        for (int counter = 1; counter <= 8; counter++) {
            this.index.ended(A, counter);
        }
        assertEquals(0, this.index.lost());

        this.index.drain();
        assertEquals(4, this.index.size());
        this.index.flush();
        this.index.drain();

        assertEquals(0, this.index.size());
    }

    @Test
    void aFullIndexRefusesNewOrdersRatherThanSendingThemUnchecked() {
        this.index = new CrossIndex(this.keys, CrossIndex.Mode.ENFORCE, 1, 4);
        send(A, 1, YES, Side.Bid, 40 * CENTS);

        assertEquals(CrossIndex.FULL_INDEX, this.index.checkNew(B, YES, Side.Bid, 10 * CENTS));
        assertEquals(1, this.index.noRoom());
    }

    @Test
    void shadowModeCountsACrossingOrderAndLetsItGo() {
        this.index = new CrossIndex(this.keys, CrossIndex.Mode.SHADOW, 16, 4);
        send(A, 1, YES, Side.Bid, 40 * CENTS);

        assertEquals(CrossIndex.CLEAR, this.index.checkNew(B, YES, Side.Ask, 40 * CENTS));
        assertEquals(1, this.index.shadowed());
        assertEquals(0, this.index.refused());
    }

    @Test
    void aListingNeverMadeTradeableIsNotChecked() {
        final long unknown = PublishedMap.listingKey(EXCHANGE, 99);
        send(A, 1, unknown, Side.Bid, 40 * CENTS);

        assertEquals(CrossIndex.CLEAR, this.index.checkNew(B, unknown, Side.Ask, 40 * CENTS));
        assertEquals(0, this.index.size());
    }

    private void send(final long slot, final long counter, final long listing, final Side side, final long price) {
        assertEquals(CrossIndex.CLEAR, this.index.checkNew(slot, listing, side, price));
        this.index.add(slot, counter, listing, side, price);
    }
}
