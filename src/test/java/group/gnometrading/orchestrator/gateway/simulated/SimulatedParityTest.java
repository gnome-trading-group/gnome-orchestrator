package group.gnometrading.orchestrator.gateway.simulated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.orchestrator.session.orders.PaperTradingOutboundGateway;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.StreamHeader;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.Mbp10Decoder;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
import group.gnometrading.simulation.queues.QueueModel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;

/**
 * The same market data and orders through a session's own simulation and through a paper gateway's give the same
 * reports: moving the simulator into the gateway changes where it runs, not what it fills. With latency off: the
 * session's simulation draws a latency on every pass, so with latency on the two draw differently however alike they
 * are.
 */
class SimulatedParityTest {

    private static final int EXCHANGE = 5;
    private static final long SECURITY = 77;
    private static final int STREAM = 4242;
    private static final int STRATEGY = 3;
    private static final long SLOT = 11;
    private static final String TAG = "parity-session";
    private static final long P = 1_000_000_000L;
    private static final long S = 1_000_000L;

    /** What a report says that a strategy would act on. */
    record Report(
            long counter, ExecType type, OrderStatus status, long filled, long fillPrice, long leaves, String venueId) {
        static Report of(final OrderExecutionReport report) {
            final byte[] id = new byte[32];
            int length = 0;
            for (; length < id.length; length++) {
                final byte b = report.decoder.exchangeOrderId(length);
                if (b == 0) {
                    break;
                }
                id[length] = b;
            }
            return new Report(
                    report.getClientOidCounter(),
                    report.decoder.execType(),
                    report.decoder.orderStatus(),
                    report.decoder.filledQty(),
                    report.decoder.fillPrice(),
                    report.decoder.leavesQty(),
                    new String(id, 0, length, StandardCharsets.US_ASCII));
        }
    }

    /** One step of the script both sides run. */
    sealed interface Step {}

    record Book(long bid, long bidSize, long ask, long askSize) implements Step {}

    record Trade(group.gnometrading.schemas.book.Side aggressor, long price, long size) implements Step {}

    record New(long counter, Side side, long price, long size) implements Step {}

    record Cancel(long counter) implements Step {}

    private static final List<Step> SCRIPT = List.of(
            new Book(100 * P, 10 * S, 102 * P, 40 * S),
            new New(1, Side.Bid, 100 * P, 5 * S),
            new Trade(group.gnometrading.schemas.book.Side.Ask, 100 * P, 12 * S),
            new Trade(group.gnometrading.schemas.book.Side.Ask, 100 * P, 20 * S),
            new New(2, Side.Ask, 102 * P, 5 * S),
            new Trade(group.gnometrading.schemas.book.Side.Bid, 102 * P, 50 * S),
            new New(3, Side.Bid, 99 * P, 5 * S),
            new Book(99 * P, 30 * S, 101 * P, 10 * S),
            new Cancel(3),
            new New(4, Side.Bid, 101 * P, 3 * S));

    private final AtomicLong wallNanos = new AtomicLong(TimeUnit.SECONDS.toNanos(1_700_000_000));

    private static MbpSimulatedExchange venue() {
        final QueueModel queue = (prev, next, orders) -> {};
        return new MbpSimulatedExchange(
                (nowNanos, buy, price, quantity, maker, state) -> 0L, () -> 0L, () -> 0L, queue);
    }

    @Test
    void theGatewaysSimulationReportsWhatTheSessionsOwnDid() throws Exception {
        final List<Report> session = runInSession();
        final List<Report> gateway = runInGateway();

        assertFalse(session.isEmpty());
        assertEquals(session, gateway);
    }

    // ----- the session's own simulation -----

    private List<Report> runInSession() throws Exception {
        final GlobalSequence sequence = new GlobalSequence();
        final SequencedRingBuffer<Mbp10Schema> marketData = new SequencedRingBuffer<>(Mbp10Schema::new, sequence, 1024);
        final SequencedRingBuffer<Intent> orders = new SequencedRingBuffer<>(Intent::new, sequence, 1024);
        final SequencedRingBuffer<OrderExecutionReport> reports =
                new SequencedRingBuffer<>(OrderExecutionReport::new, sequence, 1024);
        final PaperTradingOutboundGateway gateway =
                new PaperTradingOutboundGateway(venue(), marketData, STREAM, orders, reports, this.wallNanos::get, TAG);
        final List<Report> heard = new ArrayList<>();
        final OrderExecutionReport read = new OrderExecutionReport();
        final SequencedPoller poller = reports.createPoller((seq, templateId, buffer, length) -> {
            read.wrap(buffer);
            heard.add(Report.of(read));
        });
        final Order order = new Order();
        final CancelOrder cancel = new CancelOrder();
        for (final Step step : SCRIPT) {
            if (step instanceof Book book) {
                publish(marketData, bookRecord(book));
            } else if (step instanceof Trade trade) {
                publish(marketData, tradeRecord(trade));
            } else if (step instanceof New n) {
                order.encoder.exchangeId(EXCHANGE).securityId(SECURITY).timestampSend(this.wallNanos.get());
                order.encodeClientOid(n.counter(), STRATEGY);
                order.encoder.price(n.price()).size(n.size()).side(n.side());
                order.encoder.orderType(OrderType.LIMIT).timeInForce(TimeInForce.GOOD_TILL_CANCELED);
                order.encoder.flags().clear();
                orders.publishRaw(order.buffer, OrderDecoder.TEMPLATE_ID, order.totalMessageSize());
            } else if (step instanceof Cancel c) {
                cancel.encoder.exchangeId(EXCHANGE).securityId(SECURITY).timestampSend(this.wallNanos.get());
                cancel.encodeClientOid(c.counter(), STRATEGY);
                orders.publishRaw(cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, cancel.totalMessageSize());
            }
            for (int i = 0; i < 4; i++) {
                gateway.doWork();
            }
            poller.poll();
        }
        return heard;
    }

    // ----- a paper gateway's -----

    private List<Report> runInGateway() throws Exception {
        final SequencedRingBuffer<Mbp10Schema> marketData =
                new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence(), 1024);
        final ManyToOneRingBuffer<LaneCommand> commands =
                new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, 64);
        final ManyToOneRingBuffer<AccountReport> reports =
                new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 256);
        final ManyToOneConcurrentArrayQueue<Object> handoffs = new ManyToOneConcurrentArrayQueue<>(4);
        final AtomicLong nanos = new AtomicLong(1);
        final SimulatedLane lane = new SimulatedLane(
                commands,
                reports,
                handoffs,
                marketData,
                64,
                new AccountLane.Caps(0, 0, 0),
                TimeUnit.SECONDS.toNanos(1),
                nanos::get,
                this.wallNanos::get);
        handoffs.offer(new SimulatedLane.AddSession(new SimulatedSession(
                SLOT,
                TAG,
                null,
                List.of(new SimulatedBook(
                        SLOT, SimulatedAccount.listingKey(EXCHANGE, SECURITY), 900, STREAM, venue(), TAG)))));
        final List<Report> heard = new ArrayList<>();
        for (final Step step : SCRIPT) {
            if (step instanceof Book book) {
                publish(marketData, bookRecord(book));
            } else if (step instanceof Trade trade) {
                publish(marketData, tradeRecord(trade));
            } else if (step instanceof New n) {
                command(commands, LaneCommand.NEW, n.counter(), n.side(), n.price(), n.size());
            } else if (step instanceof Cancel c) {
                command(commands, LaneCommand.CANCEL, c.counter(), null, 0, 0);
            }
            for (int i = 0; i < 4; i++) {
                lane.doWork();
            }
            reports.read(report -> heard.add(Report.of(report.report)), 256);
        }
        return heard;
    }

    private static void command(
            final ManyToOneRingBuffer<LaneCommand> commands,
            final int kind,
            final long counter,
            final Side side,
            final long price,
            final long size) {
        final int index = commands.tryClaim();
        final LaneCommand command = commands.indexAt(index);
        command.reset();
        command.kind = kind;
        command.sessionSlot = SLOT;
        command.clientOidCounter = counter;
        command.clientOidStrategyId = STRATEGY;
        command.exchangeId = EXCHANGE;
        command.securityId = SECURITY;
        command.side = side;
        command.price = price;
        command.size = size;
        command.timeInForce = TimeInForce.GOOD_TILL_CANCELED;
        command.orderType = OrderType.LIMIT;
        commands.commit(index);
    }

    // ----- market data -----

    private void publish(final SequencedRingBuffer<Mbp10Schema> ring, final Mbp10Schema record) {
        final int header = SbeMessage.HEADER_LENGTH;
        record.buffer.putInt(header + StreamHeader.STREAM_ID_OFFSET, STREAM);
        record.buffer.putLong(header + StreamHeader.TIMESTAMP_RECV_OFFSET, this.wallNanos.get());
        ring.publishRaw(record.buffer, Mbp10Decoder.TEMPLATE_ID, record.totalMessageSize());
    }

    private static Mbp10Schema bookRecord(final Book book) {
        final Mbp10Schema record = new Mbp10Schema();
        record.encoder.action(Action.Add);
        record.encoder.side(group.gnometrading.schemas.book.Side.None);
        record.encoder.price(Mbp10Decoder.priceNullValue());
        record.encoder.size(Mbp10Decoder.sizeNullValue());
        record.encoder
                .bidPrice0(book.bid())
                .bidSize0(book.bidSize())
                .askPrice0(book.ask())
                .askSize0(book.askSize());
        emptyBelowTop(record);
        return record;
    }

    private static Mbp10Schema tradeRecord(final Trade trade) {
        final Mbp10Schema record = new Mbp10Schema();
        record.encoder.action(Action.Trade);
        record.encoder.side(trade.aggressor());
        record.encoder.price(trade.price());
        record.encoder.size(trade.size());
        record.encoder.bidPrice0(Mbp10Decoder.priceNullValue()).bidSize0(Mbp10Decoder.sizeNullValue());
        record.encoder.askPrice0(Mbp10Decoder.priceNullValue()).askSize0(Mbp10Decoder.sizeNullValue());
        emptyBelowTop(record);
        return record;
    }

    private static void emptyBelowTop(final Mbp10Schema record) {
        final long px = Mbp10Decoder.priceNullValue();
        final long sz = Mbp10Decoder.sizeNullValue();
        record.encoder.bidPrice1(px).bidSize1(sz).askPrice1(px).askSize1(sz);
        record.encoder.bidPrice2(px).bidSize2(sz).askPrice2(px).askSize2(sz);
        record.encoder.bidPrice3(px).bidSize3(sz).askPrice3(px).askSize3(sz);
        record.encoder.bidPrice4(px).bidSize4(sz).askPrice4(px).askSize4(sz);
        record.encoder.bidPrice5(px).bidSize5(sz).askPrice5(px).askSize5(sz);
        record.encoder.bidPrice6(px).bidSize6(sz).askPrice6(px).askSize6(sz);
        record.encoder.bidPrice7(px).bidSize7(sz).askPrice7(px).askSize7(sz);
        record.encoder.bidPrice8(px).bidSize8(sz).askPrice8(px).askSize8(sz);
        record.encoder.bidPrice9(px).bidSize9(sz).askPrice9(px).askSize9(sz);
    }
}
