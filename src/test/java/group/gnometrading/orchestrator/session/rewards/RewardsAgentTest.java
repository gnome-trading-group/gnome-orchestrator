package group.gnometrading.orchestrator.session.rewards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import group.gnometrading.RegistryConnection;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.md.Stream;
import group.gnometrading.md.StreamDirectory;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.schemas.book.Mbp10Decoder;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.simulation.rewards.RewardAccrual;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.ListingSpec;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.incentive.IncentiveProgram;
import group.gnometrading.sm.incentive.IncentiveTimeline;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rewards agent on the session's real ring buffers: it rebuilds our resting orders from the OMS's orders and the
 * reports reaching it, scores them against the books, and writes what it would post.
 */
class RewardsAgentTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long CENT = 10_000_000L;
    private static final long DOLLAR = 100 * CENT;
    private static final long CONTRACT = 1_000_000L;
    static final long T0 = 1_790_000_000L * SECOND;
    private static final long PERIOD = 10_000 * SECOND;
    private static final int EXCHANGE_ID = 1;
    private static final int SECURITY_ID = 42;
    private static final int LISTING_ID = 500;
    private static final int STREAM_ID = 7;

    private final GlobalSequence sequence = new GlobalSequence();
    private final SequencedRingBuffer<Mbp10Schema> marketData = new SequencedRingBuffer<>(Mbp10Schema::new, sequence);
    private final SequencedRingBuffer<Intent> orders = new SequencedRingBuffer<>(Intent::new, sequence);
    private final SequencedRingBuffer<OrderExecutionReport> reports =
            new SequencedRingBuffer<>(OrderExecutionReport::new, sequence);
    private final long[] nowMs = {T0 / 1_000_000L};
    private final RewardsAgent agent;
    // Reused for every publish, so the allocation test measures only the agent.
    private final Mbp10Schema bookMessage = new Mbp10Schema();
    private final Order orderMessage = new Order();
    private final ModifyOrder modifyMessage = new ModifyOrder();
    private final OrderExecutionReport reportMessage = new OrderExecutionReport();

    RewardsAgentTest() {
        final Listing listing = new Listing(
                LISTING_ID,
                new Exchange(EXCHANGE_ID, "KALSHI", "Kalshi", "us-east-1"),
                new Security(
                        SECURITY_ID, "KXRT-VIO-60", null, null, null, null, null, null, false, false, 0, 0, true, 0),
                "KXRT-VIO-60",
                "KXRT-VIO-60");
        final StreamDirectory streams = new StreamDirectory();
        streams.add(
                new Stream(
                        STREAM_ID,
                        1,
                        "KALSHI",
                        "LISTING",
                        LISTING_ID,
                        "KXRT-VIO-60",
                        "book",
                        "us-east-1",
                        0,
                        0,
                        0,
                        true),
                listing);
        this.agent = RewardsAgent.create(
                "paper-1",
                List.of(listing),
                List.of(new ListingSpec(LISTING_ID, CENT, CONTRACT, 0, 1, 0)),
                streams,
                marketData,
                orders,
                reports,
                new RegistryConnection("registry.invalid", "unused"),
                () -> nowMs[0],
                900_000,
                300_000,
                new NullLogger());
        // $100 over 10,000 s from T0, 1,000 contracts a side, no discount.
        this.agent
                .scorer()
                .setPrograms(LISTING_ID, new IncentiveTimeline(List.<IncentiveProgram[]>of(new IncentiveProgram[] {
                    new IncentiveProgram(
                            "k1",
                            IncentiveProgram.IncentiveKind.LIQUIDITY,
                            "KXRT-VIO-60",
                            T0,
                            T0 + PERIOD,
                            100 * DOLLAR,
                            IncentiveProgram.RewardPer.PERIOD,
                            JsonNodeFactory.instance
                                    .objectNode()
                                    .put("targetSize", 1000)
                                    .put("discountFactor", 1.0),
                            false,
                            T0)
                })));
    }

    /** A book of 2,000 each side at 50c / 52c, received at {@code at}. */
    RewardsAgent agent() {
        return this.agent;
    }

    void publishBook(final long at) {
        book(at, false);
    }

    void publishOrder(final long counter, final Side side, final long cents, final long contracts) {
        sendOrder(counter, side, cents, contracts);
    }

    void publishModify(final long counter, final long cents, final long contracts) {
        sendModify(counter, cents, contracts);
    }

    void publishReport(final long counter, final ExecType type, final long leaves, final long at) {
        receive(counter, type, leaves, at);
    }

    private void book(final long at, final boolean bad) {
        final Mbp10Schema schema = this.bookMessage;
        final long nullPrice = Mbp10Decoder.priceNullValue();
        final long nullSize = Mbp10Decoder.sizeNullValue();
        schema.encoder.streamId(STREAM_ID).timestampEvent(at).timestampRecv(at);
        schema.encoder.flags().clear().badData(bad);
        schema.encoder
                .bidPrice0(50 * CENT)
                .bidSize0(2000 * CONTRACT)
                .askPrice0(52 * CENT)
                .askSize0(2000 * CONTRACT);
        schema.encoder
                .bidPrice1(nullPrice)
                .bidSize1(nullSize)
                .askPrice1(nullPrice)
                .askSize1(nullSize);
        schema.encoder
                .bidPrice2(nullPrice)
                .bidSize2(nullSize)
                .askPrice2(nullPrice)
                .askSize2(nullSize);
        schema.encoder
                .bidPrice3(nullPrice)
                .bidSize3(nullSize)
                .askPrice3(nullPrice)
                .askSize3(nullSize);
        schema.encoder
                .bidPrice4(nullPrice)
                .bidSize4(nullSize)
                .askPrice4(nullPrice)
                .askSize4(nullSize);
        schema.encoder
                .bidPrice5(nullPrice)
                .bidSize5(nullSize)
                .askPrice5(nullPrice)
                .askSize5(nullSize);
        schema.encoder
                .bidPrice6(nullPrice)
                .bidSize6(nullSize)
                .askPrice6(nullPrice)
                .askSize6(nullSize);
        schema.encoder
                .bidPrice7(nullPrice)
                .bidSize7(nullSize)
                .askPrice7(nullPrice)
                .askSize7(nullSize);
        schema.encoder
                .bidPrice8(nullPrice)
                .bidSize8(nullSize)
                .askPrice8(nullPrice)
                .askSize8(nullSize);
        schema.encoder
                .bidPrice9(nullPrice)
                .bidSize9(nullSize)
                .askPrice9(nullPrice)
                .askSize9(nullSize);
        marketData.publishRaw(schema.buffer, schema.templateId(), schema.totalMessageSize());
    }

    private void sendOrder(final long counter, final Side side, final long cents, final long contracts) {
        final Order order = this.orderMessage;
        order.encoder
                .exchangeId((short) EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(cents * CENT)
                .size(contracts * CONTRACT)
                .side(side)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        order.encodeClientOid(counter, 3);
        orders.publishRaw(order.buffer, order.templateId(), order.totalMessageSize());
    }

    private void sendModify(final long counter, final long cents, final long contracts) {
        final ModifyOrder modify = this.modifyMessage;
        modify.encoder
                .exchangeId((short) EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .price(cents * CENT)
                .size(contracts * CONTRACT);
        modify.encodeClientOid(counter, 3);
        orders.publishRaw(modify.buffer, modify.templateId(), modify.totalMessageSize());
    }

    private void receive(final long counter, final ExecType type, final long leaves, final long at) {
        final OrderExecutionReport report = this.reportMessage;
        report.encoder
                .exchangeId((short) EXCHANGE_ID)
                .securityId(SECURITY_ID)
                .execType(type)
                .orderStatus(OrderStatus.NEW)
                .leavesQty(leaves * CONTRACT)
                .timestampEvent(at)
                .timestampRecv(at);
        report.encodeClientOid(counter, 3);
        reports.publishRaw(report.buffer, report.templateId(), report.totalMessageSize());
    }

    private RewardAccrual accrual() throws Exception {
        agent.pollRings();
        agent.scorer().advanceTo(T0 + PERIOD);
        return agent.scorer().accrual(0);
    }

    @Test
    void anOrderEarnsFromItsAcknowledgementUntilItsCancel() throws Exception {
        book(T0, false);
        // 500 a tick better than everyone's 2,000 bid: 20% of the YES side, from the ack to the cancel at half time.
        sendOrder(1, Side.Bid, 51, 500);
        receive(1, ExecType.NEW, 500, T0);
        receive(1, ExecType.CANCEL, 0, T0 + PERIOD / 2);

        // $100 × 0.2 ÷ 2 for half the period.
        assertEquals(5 * DOLLAR, accrual().gross(RewardAccrual.PLAIN), 1);
    }

    @Test
    void aModifyMovesTheOrderAndFillsShrinkIt() throws Exception {
        book(T0, false);
        // Joins everyone's 2,000 at 50c (500 / 2,500 = 20%), then moves away to 40c, out of the target.
        sendOrder(1, Side.Bid, 50, 500);
        receive(1, ExecType.NEW, 500, T0);
        sendModify(1, 40, 500);
        receive(1, ExecType.NEW, 500, T0 + PERIOD / 2);
        assertEquals(5 * DOLLAR, accrual().gross(RewardAccrual.PLAIN), 1);
    }

    @Test
    void aPartialFillLeavesWhatRemains() throws Exception {
        book(T0, false);
        sendOrder(1, Side.Bid, 51, 1000);
        receive(1, ExecType.NEW, 1000, T0);
        // 500 of 1,000 fill straight away: 500 / 2,500 = 20% for the whole period.
        receive(1, ExecType.PARTIAL_FILL, 500, T0);
        assertEquals(10 * DOLLAR, accrual().gross(RewardAccrual.PLAIN), 1);
    }

    @Test
    void aRejectedOrderNeverRests() throws Exception {
        book(T0, false);
        sendOrder(1, Side.Bid, 51, 500);
        receive(1, ExecType.REJECT, 0, T0);
        assertEquals(0, accrual().gross(RewardAccrual.PLAIN));
    }

    @Test
    void aBookFlaggedBadScoresNothingUntilAGoodOne() throws Exception {
        book(T0, false);
        sendOrder(1, Side.Bid, 51, 500);
        receive(1, ExecType.NEW, 500, T0);
        book(T0 + 2000 * SECOND, true);
        book(T0 + 6000 * SECOND, false);
        final RewardAccrual accrual = accrual();
        assertEquals(4000 * SECOND, accrual.noDataNanos());
        assertEquals(6 * DOLLAR, accrual.gross(RewardAccrual.PLAIN), 1);
    }

    @Test
    void fallingAWholeRingBehindMarksAGapAndIsCountedInThePost() throws Exception {
        book(T0, false);
        sendOrder(1, Side.Bid, 51, 500);
        receive(1, ExecType.NEW, 500, T0);
        agent.pollRings();
        for (int i = 0; i < 1100; i++) {
            book(T0 + SECOND, false);
        }
        nowMs[0] = (T0 + 2 * SECOND) / 1_000_000L;
        agent.pollRings();

        agent.encode(nowMs[0], T0 + 2 * SECOND);
        final JsonNode posted = new ObjectMapper().readTree(agent.bodyText());
        assertEquals(1, posted.get("accruals").get(0).get("gaps").asInt());
    }

    @Test
    void thePostCarriesEachRunningProgramAsTheRegistryReadsIt() throws Exception {
        book(T0, false);
        sendOrder(1, Side.Bid, 51, 500);
        receive(1, ExecType.NEW, 500, T0);
        agent.pollRings();
        final long asOf = T0 + PERIOD / 2;
        agent.scorer().advanceTo(asOf);

        assertEquals(1, agent.encode(asOf / 1_000_000L, asOf));
        final JsonNode posted = new ObjectMapper().readTree(agent.bodyText());
        assertEquals("paper-1", posted.get("sessionId").asText());
        final JsonNode entry = posted.get("accruals").get(0);
        assertEquals(LISTING_ID, entry.get("listingId").asInt());
        assertEquals("KXRT-VIO-60", entry.get("venueMarket").asText());
        assertEquals("k1", entry.get("venueProgramId").asText());
        assertEquals("LIQUIDITY", entry.get("program").asText());
        assertEquals(T0 / 1_000_000L, entry.get("periodStartMs").asLong());
        assertEquals((T0 + PERIOD) / 1_000_000L, entry.get("periodEndMs").asLong());
        assertEquals(asOf / 1_000_000L, entry.get("asOfMs").asLong());
        assertEquals(5 * DOLLAR, entry.get("gross").asLong(), 1);
        assertTrue(entry.get("countingNanos").asLong() > 0);
    }

    @Test
    void aSessionWithNoVenueTheScorerSupportsHasNoAgent() {
        final Listing hyperliquid =
                new Listing(9, new Exchange(4, "HYPERLIQUID", "Hyperliquid", "ap-northeast-1"), null, "BTC", "BTC");
        assertNull(RewardsAgent.create(
                "paper-1",
                List.of(hyperliquid),
                List.of(new ListingSpec(9, CENT, CONTRACT, 0, 1, 0)),
                new StreamDirectory(),
                marketData,
                orders,
                reports,
                new RegistryConnection("registry.invalid", "unused"),
                () -> nowMs[0],
                900_000,
                300_000,
                new NullLogger()));
    }
}
