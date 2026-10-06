package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.schemas.Action;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Mbp10Decoder;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.simulation.book.SelfTradePrevention;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
import group.gnometrading.simulation.latency.MakerTakerLatencyModel;
import group.gnometrading.simulation.latency.StaticLatency;
import group.gnometrading.simulation.queues.RiskAverseQueueModel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Drives the gateway against a real simulated exchange, advancing a hand-held clock. */
class PaperTradingOutboundGatewayTest {

    private static final long NETWORK = 5_000_000L;
    private static final long PROCESSING = 1_000_000L;
    private static final long TAKER_DELAY = 250_000_000L;
    private static final long STEP = 1_000_000L;
    private static final long PRICE_NULL = Mbp10Decoder.priceNullValue();
    private static final long SIZE_NULL = Mbp10Decoder.sizeNullValue();

    private long now = 1_000_000_000L;
    private SequencedRingBuffer<Order> orderBuffer;
    private SequencedRingBuffer<Mbp10Schema> marketDataBuffer;
    private SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private SequencedPoller execReportPoller;
    private PaperTradingOutboundGateway gateway;
    private final List<OrderExecutionReport> reports = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        MbpSimulatedExchange exchange = new MbpSimulatedExchange(
                (price, quantity, isMaker) -> 0,
                new StaticLatency(NETWORK),
                new MakerTakerLatencyModel(PROCESSING, TAKER_DELAY, 0),
                new RiskAverseQueueModel(),
                SelfTradePrevention.CANCEL_INCOMING);
        orderBuffer = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        marketDataBuffer = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        execReportBuffer = new SequencedRingBuffer<>(OrderExecutionReport::new, new GlobalSequence());
        gateway = new PaperTradingOutboundGateway(
                exchange, marketDataBuffer, orderBuffer, execReportBuffer, () -> now, "session-a");
        execReportPoller = execReportBuffer.createPoller((globalSeq, templateId, buf, len) -> {
            OrderExecutionReport report = new OrderExecutionReport();
            report.buffer.putBytes(0, buf, 0, len);
            report.wrap(report.buffer);
            reports.add(report);
        });
        orderBuffer.start();
        marketDataBuffer.start();
        execReportBuffer.start();

        publishBook(49, 10, 51, 10);
        gateway.doWork();
    }

    @AfterEach
    void tearDown() {
        orderBuffer.shutdown();
        marketDataBuffer.shutdown();
        execReportBuffer.shutdown();
    }

    @Test
    void makerAckComesBackAfterTwoNetworkHopsAndProcessing() throws Exception {
        long sent = now;
        publishLimit(50, 5, Side.Bid, 1L);

        stepTo(sent + 2 * NETWORK + PROCESSING - 1);
        assertEquals(0, reports.size());

        stepTo(sent + 2 * NETWORK + PROCESSING);
        assertEquals(1, reports.size());
        assertEquals(ExecType.NEW, reports.get(0).decoder.execType());
        // Event time is when the exchange processed it, after it arrived and its processing time passed.
        assertEquals(sent + NETWORK + PROCESSING, reports.get(0).decoder.timestampEvent());
        assertEquals(sent + 2 * NETWORK + PROCESSING, reports.get(0).decoder.timestampRecv());
    }

    @Test
    void everyReportCarriesAnOrderIdUniqueToTheSession() throws Exception {
        long sent = now;
        publishLimit(50, 5, Side.Bid, 1L);
        publishLimit(51, 5, Side.Bid, 12_345L);
        stepTo(sent + 2 * NETWORK + PROCESSING + TAKER_DELAY);

        assertEquals("session-a-1", reports.get(0).decoder.exchangeOrderId());
        assertEquals("session-a-12345", reports.get(1).decoder.exchangeOrderId());
    }

    @Test
    void takerMatchesTheBookAsItIsWhenItsDelayEnds() throws Exception {
        long sent = now;
        publishMarket(5, Side.Bid, 1L);
        stepTo(sent + NETWORK);

        // The ask moves up while the order is held.
        publishBook(49, 10, 52, 10);
        stepTo(sent + NETWORK + PROCESSING + TAKER_DELAY + NETWORK);

        assertEquals(1, reports.size());
        assertEquals(ExecType.FILL, reports.get(0).decoder.execType());
        assertEquals(52, reports.get(0).decoder.fillPrice());
    }

    @Test
    void cancelInsideTheTakerDelayIsRefusedAndTheOrderStillFills() throws Exception {
        long sent = now;
        publishLimit(51, 5, Side.Bid, 1L);
        stepTo(sent + NETWORK);
        publishCancel(1L);

        stepTo(sent + NETWORK + PROCESSING + TAKER_DELAY + 2 * NETWORK);

        assertEquals(2, reports.size());
        assertEquals(ExecType.CANCEL_REJECT, reports.get(0).decoder.execType());
        assertEquals(ExecType.FILL, reports.get(1).decoder.execType());
    }

    @Test
    void restingOrdersKeepTheirOwnPriceAndIdWhenTheGatewayReusesItsOrderObject() throws Exception {
        publishLimit(50, 5, Side.Bid, 1L);
        publishLimit(49, 5, Side.Bid, 2L);
        stepTo(now + 2 * NETWORK + PROCESSING);
        reports.clear();

        long tradeTime = now;
        publishTrade(Side.Ask, 50, 20);
        stepTo(tradeTime + NETWORK);

        assertEquals(1, reports.size());
        assertEquals(1L, reports.get(0).getClientOidCounter());
        assertEquals(50, reports.get(0).decoder.fillPrice());
    }

    @Test
    void fillFromMarketDataTakesOneNetworkHop() throws Exception {
        publishLimit(50, 5, Side.Bid, 1L);
        stepTo(now + 2 * NETWORK + PROCESSING);
        reports.clear();

        long tradeTime = now;
        publishTrade(Side.Ask, 50, 5);
        stepTo(tradeTime + NETWORK - 1);
        assertEquals(0, reports.size());
        stepTo(tradeTime + NETWORK);
        assertEquals(ExecType.FILL, reports.get(0).decoder.execType());
    }

    @Test
    void reportsArriveInTheOrderTheExchangeMadeThem() throws Exception {
        publishLimit(50, 5, Side.Bid, 1L);
        publishLimit(48, 5, Side.Bid, 2L);
        stepTo(now + 2 * NETWORK + PROCESSING);

        assertEquals(2, reports.size());
        assertEquals(1L, reports.get(0).getClientOidCounter());
        assertEquals(2L, reports.get(1).getClientOidCounter());
    }

    // --- helpers ---

    /**
     * Advances the clock to {@code time} a millisecond at a time, running the gateway at each step as its agent loop
     * would, and collects published reports. The gateway hands a message to the exchange when it polls it, so time
     * cannot jump past the moments messages arrive.
     */
    private void stepTo(long time) throws Exception {
        gateway.doWork();
        while (now < time) {
            now = Math.min(time, now + STEP);
            gateway.doWork();
            gateway.doWork();
        }
        execReportPoller.poll();
    }

    private void publishLimit(long price, long size, Side side, long clientOid) {
        publishOrder(price, size, side, clientOid, OrderType.LIMIT);
    }

    private void publishMarket(long size, Side side, long clientOid) {
        publishOrder(0, size, side, clientOid, OrderType.MARKET);
    }

    private void publishOrder(long price, long size, Side side, long clientOid, OrderType type) {
        Order order = new Order();
        order.encoder
                .exchangeId((short) 1)
                .securityId(1)
                .price(price)
                .size(size)
                .side(side)
                .orderType(type)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        order.encoder.flags().clear();
        order.encodeClientOid(clientOid, 0);
        orderBuffer.publishRaw(order.buffer, order.messageHeaderDecoder.templateId(), order.totalMessageSize());
    }

    private void publishCancel(long clientOid) {
        CancelOrder cancel = new CancelOrder();
        cancel.encoder.exchangeId((short) 1).securityId(1);
        cancel.encodeClientOid(clientOid, 0);
        orderBuffer.publishRaw(cancel.buffer, cancel.messageHeaderDecoder.templateId(), cancel.totalMessageSize());
    }

    private void publishBook(long bidPx, long bidSz, long askPx, long askSz) {
        Mbp10Schema schema = emptyMbp10(Action.Add);
        schema.encoder.bidPrice0(bidPx).bidSize0(bidSz).askPrice0(askPx).askSize0(askSz);
        publishMarketData(schema);
    }

    private void publishTrade(Side aggressor, long price, long size) {
        Mbp10Schema schema = emptyMbp10(Action.Trade);
        schema.encoder.side(aggressor).price(price).size(size);
        publishMarketData(schema);
    }

    private void publishMarketData(Mbp10Schema schema) {
        marketDataBuffer.publishRaw(schema.buffer, schema.messageHeaderDecoder.templateId(), schema.totalMessageSize());
    }

    private static Mbp10Schema emptyMbp10(Action action) {
        Mbp10Schema schema = new Mbp10Schema();
        var e = schema.encoder;
        e.action(action).side(Side.None).price(PRICE_NULL).size(SIZE_NULL);
        e.bidPrice0(PRICE_NULL).bidSize0(SIZE_NULL).askPrice0(PRICE_NULL).askSize0(SIZE_NULL);
        e.bidPrice1(PRICE_NULL).bidSize1(SIZE_NULL).askPrice1(PRICE_NULL).askSize1(SIZE_NULL);
        e.bidPrice2(PRICE_NULL).bidSize2(SIZE_NULL).askPrice2(PRICE_NULL).askSize2(SIZE_NULL);
        e.bidPrice3(PRICE_NULL).bidSize3(SIZE_NULL).askPrice3(PRICE_NULL).askSize3(SIZE_NULL);
        e.bidPrice4(PRICE_NULL).bidSize4(SIZE_NULL).askPrice4(PRICE_NULL).askSize4(SIZE_NULL);
        e.bidPrice5(PRICE_NULL).bidSize5(SIZE_NULL).askPrice5(PRICE_NULL).askSize5(SIZE_NULL);
        e.bidPrice6(PRICE_NULL).bidSize6(SIZE_NULL).askPrice6(PRICE_NULL).askSize6(SIZE_NULL);
        e.bidPrice7(PRICE_NULL).bidSize7(SIZE_NULL).askPrice7(PRICE_NULL).askSize7(SIZE_NULL);
        e.bidPrice8(PRICE_NULL).bidSize8(SIZE_NULL).askPrice8(PRICE_NULL).askSize8(SIZE_NULL);
        e.bidPrice9(PRICE_NULL).bidSize9(SIZE_NULL).askPrice9(PRICE_NULL).askSize9(SIZE_NULL);
        return schema;
    }
}
