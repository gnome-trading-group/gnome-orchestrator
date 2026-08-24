package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.simulation.exchange.SimulatedExchange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PaperTradingOutboundGatewayTest {

    private static final long P = Statics.PRICE_SCALING_FACTOR;
    private static final long S = Statics.SIZE_SCALING_FACTOR;
    private static final long NETWORK_LATENCY = 5_000_000L;
    private static final long ORDER_LATENCY = 1_000_000L;
    private static final long FULL_DELAY = 2 * NETWORK_LATENCY + ORDER_LATENCY;

    private long currentNano = 1_000_000_000L;
    private SimulatedExchange exchange;
    private SequencedRingBuffer<Order> orderBuffer;
    private SequencedRingBuffer<Mbp10Schema> marketDataBuffer;
    private SequencedRingBuffer<OrderExecutionReport> execReportBuffer;
    private SequencedPoller execReportPoller;
    private PaperTradingOutboundGateway gateway;
    private final List<OrderExecutionReport> capturedReports = new ArrayList<>();

    @BeforeEach
    void setUp() {
        exchange = mock(SimulatedExchange.class);
        when(exchange.simulateNetworkLatency()).thenReturn(NETWORK_LATENCY);
        when(exchange.simulateOrderProcessingTime()).thenReturn(ORDER_LATENCY);
        when(exchange.simulateOrderProcessingTime(anyBoolean())).thenReturn(ORDER_LATENCY);

        orderBuffer = new SequencedRingBuffer<>(Order::new, new GlobalSequence());
        marketDataBuffer = new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
        execReportBuffer = new SequencedRingBuffer<>(OrderExecutionReport::new, new GlobalSequence());

        gateway = new PaperTradingOutboundGateway(
                exchange, marketDataBuffer, orderBuffer, execReportBuffer, () -> currentNano);

        execReportPoller = execReportBuffer.createPoller((globalSeq, templateId, buf, len) -> {
            final OrderExecutionReport report = new OrderExecutionReport();
            report.buffer.putBytes(0, buf, 0, len);
            capturedReports.add(report);
        });

        orderBuffer.start();
        marketDataBuffer.start();
        execReportBuffer.start();
    }

    @AfterEach
    void tearDown() {
        orderBuffer.shutdown();
        marketDataBuffer.shutdown();
        execReportBuffer.shutdown();
    }

    @Test
    void submitOrder_zeroLatency_publishesReportOnNextCycle() throws Exception {
        when(exchange.simulateNetworkLatency()).thenReturn(0L);
        when(exchange.simulateOrderProcessingTime(anyBoolean())).thenReturn(0L);
        when(exchange.submitOrder(any())).thenReturn(List.of(makeNewAck()));

        publishOrder();
        gateway.doWork(); // enqueues action (drain runs before poll, so action not processed yet)
        gateway.doWork(); // drains action → processes → drains report
        execReportPoller.poll();

        assertEquals(1, capturedReports.size());
    }

    @Test
    void submitOrder_withLatency_delaysReport() throws Exception {
        when(exchange.submitOrder(any())).thenReturn(List.of(makeNewAck()));

        publishOrder();
        final long submitTime = currentNano;

        // First doWork: order enqueued, not yet at exchange
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(0, capturedReports.size());

        // Advance to just before order arrives at exchange
        currentNano = submitTime + NETWORK_LATENCY - 1;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(0, capturedReports.size());

        // Order arrives at exchange, report scheduled for delivery at submitTime + FULL_DELAY
        currentNano = submitTime + NETWORK_LATENCY;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(0, capturedReports.size()); // report not yet due

        // Advance to 1ns before delivery
        currentNano = submitTime + FULL_DELAY - 1;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(0, capturedReports.size());

        // Exactly at delivery time
        currentNano = submitTime + FULL_DELAY;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(1, capturedReports.size());
    }

    @Test
    void submitOrder_takerFill_usesTakerProcessingTime() throws Exception {
        when(exchange.submitOrder(any())).thenReturn(List.of(makeFill()));

        publishOrder();
        gateway.doWork(); // enqueues pending action

        currentNano += NETWORK_LATENCY;
        gateway.doWork(); // drains action → calls exchange

        verify(exchange).simulateOrderProcessingTime(false); // isMaker=false for taker fill
    }

    @Test
    void submitOrder_makerAck_usesMakerProcessingTime() throws Exception {
        when(exchange.submitOrder(any())).thenReturn(List.of(makeNewAck()));

        publishOrder();
        gateway.doWork(); // enqueues pending action

        currentNano += NETWORK_LATENCY;
        gateway.doWork(); // drains action → calls exchange

        verify(exchange).simulateOrderProcessingTime(true); // isMaker=true for NEW ack
    }

    @Test
    void marketDataFill_usesNetworkLatencyOnly() throws Exception {
        when(exchange.onMarketData(any())).thenReturn(List.of(makeFill()));

        publishMarketData();
        final long scheduleTime = currentNano;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(0, capturedReports.size());

        currentNano = scheduleTime + NETWORK_LATENCY - 1;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(0, capturedReports.size()); // single-leg delay, not full round trip

        currentNano = scheduleTime + NETWORK_LATENCY;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(1, capturedReports.size());
    }

    @Test
    void marketData_noFills_publishesNothing() throws Exception {
        when(exchange.onMarketData(any())).thenReturn(Collections.emptyList());

        publishMarketData();
        currentNano += FULL_DELAY;
        gateway.doWork();
        execReportPoller.poll();

        assertEquals(0, capturedReports.size());
    }

    @Test
    void timestamps_areSetCorrectly() throws Exception {
        when(exchange.simulateNetworkLatency()).thenReturn(0L);
        when(exchange.simulateOrderProcessingTime(anyBoolean())).thenReturn(0L);
        when(exchange.submitOrder(any())).thenReturn(List.of(makeNewAck()));

        final long expectedTimestamp = currentNano;
        publishOrder();
        gateway.doWork(); // enqueues action
        gateway.doWork(); // processes action → report delivered
        execReportPoller.poll();

        assertEquals(1, capturedReports.size());
        assertEquals(expectedTimestamp, capturedReports.get(0).decoder.timestampEvent());
        assertEquals(expectedTimestamp, capturedReports.get(0).decoder.timestampRecv());
    }

    @Test
    void timestamps_withLatency_recvIsAfterEvent() throws Exception {
        when(exchange.submitOrder(any())).thenReturn(List.of(makeNewAck()));

        final long submitTime = currentNano;
        publishOrder();
        gateway.doWork(); // enqueues pending action

        // Order arrives at exchange at submitTime + NETWORK_LATENCY
        currentNano = submitTime + NETWORK_LATENCY;
        gateway.doWork(); // exchange processes → timestampEvent = submitTime + NETWORK_LATENCY

        // Advance to delivery time
        currentNano = submitTime + FULL_DELAY;
        gateway.doWork();
        execReportPoller.poll();

        assertEquals(1, capturedReports.size());
        assertEquals(
                submitTime + NETWORK_LATENCY, capturedReports.get(0).decoder.timestampEvent());
        assertEquals(submitTime + FULL_DELAY, capturedReports.get(0).decoder.timestampRecv());
    }

    @Test
    void multipleReports_drainInFifoOrder() throws Exception {
        when(exchange.simulateNetworkLatency()).thenReturn(0L);
        when(exchange.simulateOrderProcessingTime(anyBoolean())).thenReturn(0L);
        when(exchange.submitOrder(any())).thenReturn(List.of(makeNewAck())).thenReturn(List.of(makeFill()));

        publishOrder();
        publishOrder();
        gateway.doWork(); // enqueues both actions
        gateway.doWork(); // processes both → reports delivered
        execReportPoller.poll();

        assertEquals(2, capturedReports.size());
        assertEquals(ExecType.NEW, capturedReports.get(0).decoder.execType());
        assertEquals(ExecType.FILL, capturedReports.get(1).decoder.execType());
    }

    @Test
    void marketDataDuringInboundLatency_bookUpdatedBeforeOrderProcessed() throws Exception {
        when(exchange.submitOrder(any())).thenReturn(List.of(makeNewAck()));
        when(exchange.onMarketData(any())).thenReturn(Collections.emptyList());

        publishOrder();
        gateway.doWork(); // order enqueued, not yet at exchange

        publishMarketData();
        currentNano += NETWORK_LATENCY;
        gateway.doWork(); // market data processed first, then order drains

        InOrder inOrder = inOrder(exchange);
        inOrder.verify(exchange).onMarketData(any());
        inOrder.verify(exchange).submitOrder(any());
    }

    // ========== cancel lifecycle ==========

    @Test
    void cancelOrder_withLatency_correctTiming() throws Exception {
        when(exchange.cancelOrder(any())).thenReturn(List.of(makeCancelAck()));

        final long submitTime = currentNano;
        publishCancelOrder();
        gateway.doWork();

        currentNano = submitTime + NETWORK_LATENCY;
        gateway.doWork(); // cancel arrives at exchange
        execReportPoller.poll();
        assertEquals(0, capturedReports.size()); // return leg still in flight

        currentNano = submitTime + FULL_DELAY - 1;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(0, capturedReports.size());

        currentNano = submitTime + FULL_DELAY;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(1, capturedReports.size());
        assertEquals(ExecType.CANCEL, capturedReports.get(0).decoder.execType());
    }

    @Test
    void cancelOrder_lifecycle_submitThenCancel() throws Exception {
        when(exchange.submitOrder(any())).thenReturn(List.of(makeNewAck()));
        when(exchange.cancelOrder(any())).thenReturn(List.of(makeCancelAck()));

        final long orderSubmitTime = currentNano;
        publishOrder();
        gateway.doWork(); // enqueues order

        // Order arrives at exchange
        currentNano = orderSubmitTime + NETWORK_LATENCY;
        gateway.doWork();

        // Submit cancel while order ack is in flight
        final long cancelSubmitTime = currentNano;
        publishCancelOrder();
        gateway.doWork(); // enqueues cancel with deliveryNano = cancelSubmitTime + NETWORK_LATENCY

        // Cancel arrives at exchange before the order ack is delivered
        currentNano = cancelSubmitTime + NETWORK_LATENCY;
        gateway.doWork(); // cancel processed → CANCEL ack at cancelSubmitTime + FULL_DELAY

        // Order ack delivered
        currentNano = orderSubmitTime + FULL_DELAY;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(1, capturedReports.size());
        assertEquals(ExecType.NEW, capturedReports.get(0).decoder.execType());

        // Cancel ack delivered
        currentNano = cancelSubmitTime + FULL_DELAY;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(2, capturedReports.size());
        assertEquals(ExecType.CANCEL, capturedReports.get(1).decoder.execType());
    }

    // ========== modify lifecycle ==========

    @Test
    void modifyOrder_withLatency_correctTiming() throws Exception {
        when(exchange.modifyOrder(any())).thenReturn(List.of(makeModifyAck()));

        final long submitTime = currentNano;
        publishModifyOrder();
        gateway.doWork();

        currentNano = submitTime + NETWORK_LATENCY;
        gateway.doWork(); // modify arrives at exchange

        currentNano = submitTime + FULL_DELAY;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(1, capturedReports.size());
        assertEquals(ExecType.NEW, capturedReports.get(0).decoder.execType());
        assertEquals(OrderStatus.NEW, capturedReports.get(0).decoder.orderStatus());
    }

    @Test
    void modifyOrder_lifecycle_submitThenModify() throws Exception {
        when(exchange.submitOrder(any())).thenReturn(List.of(makeNewAck()));
        when(exchange.modifyOrder(any())).thenReturn(List.of(makeModifyAck()));

        final long orderSubmitTime = currentNano;
        publishOrder();
        gateway.doWork();

        currentNano = orderSubmitTime + NETWORK_LATENCY;
        gateway.doWork(); // order at exchange

        final long modifySubmitTime = currentNano;
        publishModifyOrder();
        gateway.doWork(); // enqueues modify with deliveryNano = modifySubmitTime + NETWORK_LATENCY

        // Modify arrives at exchange before the order ack is delivered
        currentNano = modifySubmitTime + NETWORK_LATENCY;
        gateway.doWork(); // modify processed → ack at modifySubmitTime + FULL_DELAY

        // Order ack delivered
        currentNano = orderSubmitTime + FULL_DELAY;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(1, capturedReports.size());
        assertEquals(ExecType.NEW, capturedReports.get(0).decoder.execType());

        // Modify ack delivered
        currentNano = modifySubmitTime + FULL_DELAY;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(2, capturedReports.size());
        assertEquals(ExecType.NEW, capturedReports.get(1).decoder.execType());
        assertEquals(OrderStatus.NEW, capturedReports.get(1).decoder.orderStatus());
    }

    // ========== overlapping / racing actions ==========

    @Test
    void overlappingOrders_drainInFifoOrder() throws Exception {
        when(exchange.submitOrder(any())).thenReturn(List.of(makeNewAck())).thenReturn(List.of(makeFill()));

        final long submitTime = currentNano;
        publishOrder();
        publishOrder();
        gateway.doWork(); // both enqueued with same deliveryNano

        currentNano = submitTime + NETWORK_LATENCY;
        gateway.doWork(); // both drain in FIFO order

        currentNano = submitTime + FULL_DELAY;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(2, capturedReports.size());
        assertEquals(ExecType.NEW, capturedReports.get(0).decoder.execType());
        assertEquals(ExecType.FILL, capturedReports.get(1).decoder.execType());
    }

    @Test
    void cancelRacesOrder_cancelArrivesAfterOrderDueToFifo() throws Exception {
        when(exchange.submitOrder(any())).thenReturn(List.of(makeNewAck()));
        when(exchange.cancelOrder(any())).thenReturn(List.of(makeCancelAck()));

        final long submitTime = currentNano;
        publishOrder();
        publishCancelOrder(); // cancel queued immediately after order
        gateway.doWork(); // both enqueued

        currentNano = submitTime + NETWORK_LATENCY;
        gateway.doWork(); // order drains first (FIFO), cancel second

        InOrder inOrder = inOrder(exchange);
        inOrder.verify(exchange).submitOrder(any());
        inOrder.verify(exchange).cancelOrder(any());

        currentNano = submitTime + FULL_DELAY;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(2, capturedReports.size());
        assertEquals(ExecType.NEW, capturedReports.get(0).decoder.execType());
        assertEquals(ExecType.CANCEL, capturedReports.get(1).decoder.execType());
    }

    // ========== different latencies ==========

    @Test
    void differentLatencies_eachActionUsesItsOwnLatency() throws Exception {
        final long orderNetworkLatency = 3_000_000L;
        final long cancelNetworkLatency = 8_000_000L;
        final long processingLatency = 1_000_000L;

        when(exchange.simulateNetworkLatency())
                .thenReturn(orderNetworkLatency) // inbound for order
                .thenReturn(orderNetworkLatency) // outbound for order (return trip)
                .thenReturn(cancelNetworkLatency) // inbound for cancel
                .thenReturn(cancelNetworkLatency); // outbound for cancel (return trip)
        when(exchange.simulateOrderProcessingTime(anyBoolean())).thenReturn(processingLatency);
        when(exchange.simulateOrderProcessingTime()).thenReturn(processingLatency);
        when(exchange.submitOrder(any())).thenReturn(List.of(makeNewAck()));
        when(exchange.cancelOrder(any())).thenReturn(List.of(makeCancelAck()));

        final long t0 = currentNano;
        publishOrder();
        gateway.doWork();

        currentNano = t0 + orderNetworkLatency;
        final long t1 = currentNano;
        publishCancelOrder();
        gateway.doWork(); // order drains; cancel enqueued

        // Order report arrives at t0 + 2*orderNetworkLatency + processingLatency
        currentNano = t0 + 2 * orderNetworkLatency + processingLatency;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(1, capturedReports.size());
        assertEquals(ExecType.NEW, capturedReports.get(0).decoder.execType());

        // Cancel arrives at exchange at t1 + cancelNetworkLatency
        currentNano = t1 + cancelNetworkLatency;
        gateway.doWork();

        // Cancel report arrives at t1 + 2*cancelNetworkLatency + processingLatency
        currentNano = t1 + 2 * cancelNetworkLatency + processingLatency;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(2, capturedReports.size());
        assertEquals(ExecType.CANCEL, capturedReports.get(1).decoder.execType());
    }

    // ========== market data fill after resting order ==========

    @Test
    void submitOrder_restingOnBook_filledBySubsequentMarketData() throws Exception {
        when(exchange.submitOrder(any())).thenReturn(List.of(makeNewAck()));
        when(exchange.onMarketData(any())).thenReturn(List.of(makeFill()));

        final long orderSubmitTime = currentNano;
        publishOrder();
        gateway.doWork();

        currentNano = orderSubmitTime + NETWORK_LATENCY;
        gateway.doWork(); // order at exchange → NEW ack scheduled

        // Collect NEW ack
        currentNano = orderSubmitTime + FULL_DELAY;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(1, capturedReports.size());
        assertEquals(ExecType.NEW, capturedReports.get(0).decoder.execType());

        // Market data arrives and triggers fill on resting order
        final long mdTime = currentNano;
        publishMarketData();
        gateway.doWork(); // fill scheduled with single-leg network latency

        currentNano = mdTime + NETWORK_LATENCY - 1;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(1, capturedReports.size()); // not yet

        currentNano = mdTime + NETWORK_LATENCY;
        gateway.doWork();
        execReportPoller.poll();
        assertEquals(2, capturedReports.size());
        assertEquals(ExecType.FILL, capturedReports.get(1).decoder.execType());
    }

    private void publishOrder() {
        final Order order = new Order();
        order.encoder
                .exchangeId((short) 1)
                .securityId(1)
                .price(50 * P)
                .size(10 * S)
                .side(Side.Bid)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        order.encodeClientOid(1L, 0);
        orderBuffer.publishRaw(order.buffer, order.messageHeaderDecoder.templateId(), order.totalMessageSize());
    }

    private void publishMarketData() {
        final Mbp10Schema schema = new Mbp10Schema();
        marketDataBuffer.publishRaw(schema.buffer, schema.messageHeaderDecoder.templateId(), schema.totalMessageSize());
    }

    private static OrderExecutionReport makeNewAck() {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.encoder
                .execType(ExecType.NEW)
                .orderStatus(OrderStatus.NEW)
                .leavesQty(10)
                .cumulativeQty(0)
                .filledQty(OrderExecutionReportEncoder.filledQtyNullValue())
                .fillPrice(OrderExecutionReportEncoder.fillPriceNullValue())
                .fee(OrderExecutionReportEncoder.feeNullValue())
                .rejectReason(RejectReason.NULL_VAL);
        return report;
    }

    private static OrderExecutionReport makeFill() {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.encoder
                .execType(ExecType.FILL)
                .orderStatus(OrderStatus.FILLED)
                .leavesQty(0)
                .cumulativeQty(10)
                .filledQty(10)
                .fillPrice(50 * P)
                .fee(0)
                .rejectReason(RejectReason.NULL_VAL);
        return report;
    }

    private static OrderExecutionReport makeCancelAck() {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.encoder
                .execType(ExecType.CANCEL)
                .orderStatus(OrderStatus.CANCELED)
                .leavesQty(0)
                .cumulativeQty(0)
                .filledQty(OrderExecutionReportEncoder.filledQtyNullValue())
                .fillPrice(OrderExecutionReportEncoder.fillPriceNullValue())
                .fee(OrderExecutionReportEncoder.feeNullValue())
                .rejectReason(RejectReason.NULL_VAL);
        return report;
    }

    private static OrderExecutionReport makeModifyAck() {
        final OrderExecutionReport report = new OrderExecutionReport();
        report.encoder
                .execType(ExecType.NEW)
                .orderStatus(OrderStatus.NEW)
                .leavesQty(20)
                .cumulativeQty(0)
                .filledQty(OrderExecutionReportEncoder.filledQtyNullValue())
                .fillPrice(OrderExecutionReportEncoder.fillPriceNullValue())
                .fee(OrderExecutionReportEncoder.feeNullValue())
                .rejectReason(RejectReason.NULL_VAL);
        return report;
    }

    private void publishCancelOrder() {
        final CancelOrder cancel = new CancelOrder();
        cancel.encoder.exchangeId((short) 1).securityId(1).orderId(42L);
        cancel.encodeClientOid(1L, 0);
        orderBuffer.publishRaw(cancel.buffer, cancel.messageHeaderDecoder.templateId(), cancel.totalMessageSize());
    }

    private void publishModifyOrder() {
        final ModifyOrder modify = new ModifyOrder();
        modify.encoder
                .exchangeId((short) 1)
                .securityId(1)
                .orderId(42L)
                .price(60 * P)
                .size(20 * S)
                .orderType(OrderType.LIMIT)
                .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
        modify.encodeClientOid(1L, 0);
        orderBuffer.publishRaw(modify.buffer, modify.messageHeaderDecoder.templateId(), modify.totalMessageSize());
    }
}
