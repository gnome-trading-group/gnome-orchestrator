package group.gnometrading.orchestrator.gateway.simulated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.ratelimit.AccountBudget;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.latency.MinuteHistograms;
import group.gnometrading.latency.MinuteSummary;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.StreamHeader;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.schemas.book.Action;
import group.gnometrading.schemas.book.Mbp10Decoder;
import group.gnometrading.sequencer.GlobalSequence;
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

/** A simulated account's lane on real simulated venues, fed records and commands as the gateway feeds it. */
class SimulatedLaneTest {

    private static final int EXCHANGE = 5;
    private static final long SECURITY = 77;
    private static final int LISTING = 900;
    private static final int STREAM = 4242;
    private static final long A = 11;
    private static final long B = 12;
    private static final long P = 1_000_000_000L;
    private static final long S = 1_000_000L;
    private static final long MAX_QUEUE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    /** What a session heard back, enough to compare. */
    record Heard(
            long slot, long counter, ExecType type, OrderStatus status, RejectReason reason, long filled, String id) {}

    private final AtomicLong nanos = new AtomicLong(TimeUnit.SECONDS.toNanos(1_000));
    private final AtomicLong wallNanos = new AtomicLong(TimeUnit.SECONDS.toNanos(1_700_000_000));
    private final SequencedRingBuffer<Mbp10Schema> marketData =
            new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence(), 1024, this.wallNanos::get);
    private final ManyToOneRingBuffer<LaneCommand> commands =
            new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, 64);
    private final ManyToOneRingBuffer<AccountReport> reports =
            new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 256);
    private final ManyToOneConcurrentArrayQueue<Object> handoffs = new ManyToOneConcurrentArrayQueue<>(16);
    private final List<Heard> heard = new ArrayList<>();
    private SimulatedLane lane = lane(new AccountLane.Caps(0, 0, 0));

    private SimulatedLane lane(final AccountLane.Caps caps) {
        return new SimulatedLane(
                this.commands,
                this.reports,
                this.handoffs,
                this.marketData,
                64,
                caps,
                MAX_QUEUE_NANOS,
                this.nanos::get,
                this.wallNanos::get);
    }

    private static MbpSimulatedExchange venue(final long latencyNanos) {
        final QueueModel queue = (prev, next, orders) -> {};
        return new MbpSimulatedExchange(
                (nowNanos, buy, price, quantity, maker, state) -> 0L, () -> latencyNanos, () -> 0L, queue);
    }

    private void join(final long slot, final String sessionId, final AccountBudget budget) {
        join(slot, sessionId, budget, 0);
    }

    /** A session whose venue is {@code latencyNanos} away each way. */
    private void join(final long slot, final String sessionId, final AccountBudget budget, final long latencyNanos) {
        final SimulatedBook book = new SimulatedBook(
                slot, SimulatedAccount.listingKey(EXCHANGE, SECURITY), LISTING, STREAM, venue(latencyNanos), sessionId);
        this.handoffs.offer(new SimulatedLane.AddSession(new SimulatedSession(slot, sessionId, budget, List.of(book))));
        pass();
    }

    // ----- driving -----

    private void pass() {
        try {
            for (int i = 0; i < 4; i++) {
                this.lane.doWork();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        this.reports.read(
                report -> this.heard.add(new Heard(
                        report.sessionSlot,
                        report.report.getClientOidCounter(),
                        report.report.decoder.execType(),
                        report.report.decoder.orderStatus(),
                        report.report.decoder.rejectReason(),
                        report.report.decoder.filledQty(),
                        venueId(report))),
                256);
    }

    private static String venueId(final AccountReport report) {
        final byte[] bytes = new byte[32];
        int length = 0;
        for (; length < bytes.length; length++) {
            final byte b = report.report.decoder.exchangeOrderId(length);
            if (b == 0) {
                break;
            }
            bytes[length] = b;
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }

    private void command(final int kind, final long slot, final long counter, final Side side, final long price) {
        final int index = this.commands.tryClaim();
        final LaneCommand command = this.commands.indexAt(index);
        command.reset();
        command.kind = kind;
        command.sessionSlot = slot;
        command.clientOidCounter = counter;
        command.clientOidStrategyId = 3;
        command.exchangeId = EXCHANGE;
        command.securityId = SECURITY;
        command.side = side;
        command.price = price;
        command.size = 5 * S;
        command.timeInForce = TimeInForce.GOOD_TILL_CANCELED;
        command.orderType = OrderType.LIMIT;
        this.commands.commit(index);
        pass();
    }

    private void book(final long bid, final long bidSize, final long ask, final long askSize) {
        bookReceivedAt(this.wallNanos.get(), bid, bidSize, ask, askSize);
    }

    /** A book record the venue's feed received at {@code recvNanos} (wall clock). */
    private void bookReceivedAt(
            final long recvNanos, final long bid, final long bidSize, final long ask, final long askSize) {
        publish(bookRecord(bid, bidSize, ask, askSize), false, recvNanos);
    }

    private static Mbp10Schema bookRecord(final long bid, final long bidSize, final long ask, final long askSize) {
        final Mbp10Schema record = new Mbp10Schema();
        record.encoder.action(Action.Add);
        record.encoder.side(group.gnometrading.schemas.book.Side.None);
        record.encoder.price(Mbp10Decoder.priceNullValue());
        record.encoder.size(Mbp10Decoder.sizeNullValue());
        record.encoder.bidPrice0(bid).bidSize0(bidSize).askPrice0(ask).askSize0(askSize);
        emptyBelowTop(record);
        return record;
    }

    private void trade(
            final group.gnometrading.schemas.book.Side aggressor,
            final long price,
            final long size,
            final boolean bad) {
        final Mbp10Schema record = new Mbp10Schema();
        record.encoder.action(Action.Trade);
        record.encoder.side(aggressor);
        record.encoder.price(price);
        record.encoder.size(size);
        record.encoder.bidPrice0(Mbp10Decoder.priceNullValue()).bidSize0(Mbp10Decoder.sizeNullValue());
        record.encoder.askPrice0(Mbp10Decoder.priceNullValue()).askSize0(Mbp10Decoder.sizeNullValue());
        emptyBelowTop(record);
        publish(record, bad, this.wallNanos.get());
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

    private void publish(final Mbp10Schema record, final boolean bad, final long recvNanos) {
        enqueue(record, bad, recvNanos);
        pass();
    }

    /** Into the reader's ring, stamped with the wall clock now, without the lane taking it yet. */
    private void enqueue(final Mbp10Schema record, final boolean bad, final long recvNanos) {
        final int header = SbeMessage.HEADER_LENGTH;
        record.buffer.putInt(header + StreamHeader.STREAM_ID_OFFSET, STREAM);
        record.buffer.putLong(header + StreamHeader.TIMESTAMP_RECV_OFFSET, recvNanos);
        record.buffer.putByte(header + StreamHeader.FLAGS_OFFSET, (byte) (bad ? 1 << StreamHeader.BAD_DATA_BIT : 0));
        this.marketData.publishRaw(record.buffer, Mbp10Decoder.TEMPLATE_ID, record.totalMessageSize());
    }

    private List<Heard> of(final long slot, final ExecType type) {
        return this.heard.stream()
                .filter(h -> h.slot() == slot && h.type() == type)
                .toList();
    }

    /** A budget of one new order a second, burst one: it starts empty, as a gateway's does. */
    private AccountBudget oneOrderASecond() {
        return new AccountBudget(
                VenueRateLimits.of(
                        "Test",
                        List.of(new VenueRateLimits.Bucket("orders", 1, 1, 0)),
                        List.of(new VenueRateLimits.Rule(null, "", null, "orders", 1, RequestClass.NEW)),
                        List.of()),
                this.nanos.get());
    }

    // ----- tests -----

    @Test
    void lagCountsOnlyRecordsNewerThanTheStreamsLastNotAJoinOrAReSend() {
        join(A, "session-a", null);
        final long quietSince = this.wallNanos.get() - TimeUnit.SECONDS.toNanos(90);

        // A book joining a quiet stream gets the shard's re-send of its last record, received long ago.
        bookReceivedAt(quietSince, 100 * P, 10 * S, 102 * P, 10 * S);
        this.wallNanos.addAndGet(TimeUnit.SECONDS.toNanos(5));
        bookReceivedAt(quietSince, 100 * P, 10 * S, 102 * P, 10 * S);
        assertEquals(0, this.lane.takeLagMaxNanos(), "neither the join nor the re-send is a delay");

        bookReceivedAt(this.wallNanos.get() - TimeUnit.MILLISECONDS.toNanos(3), 100 * P, 12 * S, 102 * P, 10 * S);
        assertEquals(TimeUnit.MILLISECONDS.toNanos(3), this.lane.takeLagMaxNanos(), "a new record's age counts");
    }

    @Test
    void eachRecordsWaitAfterTheReaderAndItsWholeLagGoIntoTheMinutesHistograms() {
        join(A, "session-a", null);
        bookReceivedAt(this.wallNanos.get() - TimeUnit.MILLISECONDS.toNanos(10), 100 * P, 10 * S, 102 * P, 10 * S);
        final long received = this.wallNanos.get() - TimeUnit.MILLISECONDS.toNanos(3);
        enqueue(bookRecord(100 * P, 12 * S, 102 * P, 10 * S), false, received);
        this.wallNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(2));
        pass();

        final MinuteHistograms latency = this.lane.latency();
        latency.roll(this.wallNanos.get() + MinuteHistograms.MINUTE_NANOS);
        final MinuteSummary minute = new MinuteSummary(latency.capacity());
        assertTrue(latency.summarize(minute));
        assertEquals("paper.queue", latency.label(0).metric());
        assertEquals(2, minute.count(0), "every record's wait after the reader");
        assertEquals(TimeUnit.MILLISECONDS.toNanos(2), minute.max(0));
        assertEquals("paper.lag", latency.label(2).metric());
        assertEquals(1, minute.count(2), "only the record new to its stream");
        assertEquals(TimeUnit.MILLISECONDS.toNanos(5), minute.max(2), "from the venue's receipt");
        assertEquals(2, this.lane.counters().get(2), "records counted");
    }

    @Test
    void aNewOrderWaitsForItsBookToHaveData_ThenRestsAndFillsAsTheVenueSaysWithTheSessionsVenueId() {
        join(A, "session-a", null);

        command(LaneCommand.NEW, A, 1, Side.Bid, 100 * P);
        assertEquals(
                List.of(RejectReason.GATEWAY_NOT_READY),
                of(A, ExecType.REJECT).stream().map(Heard::reason).toList(),
                "no order rests on a book the first snapshot could cross");

        book(100 * P, 10 * S, 102 * P, 40 * S);
        command(LaneCommand.NEW, A, 2, Side.Bid, 100 * P);
        assertEquals(1, of(A, ExecType.NEW).size());
        assertEquals("session-a-2", of(A, ExecType.NEW).get(0).id(), "venue ids as a live venue would give them");

        trade(group.gnometrading.schemas.book.Side.Ask, 100 * P, 20 * S, false);
        final List<Heard> fills = of(A, ExecType.FILL);
        assertEquals(1, fills.size());
        assertEquals(OrderStatus.FILLED, fills.get(0).status());
        assertEquals(5 * S, fills.get(0).filled());
        assertEquals(0, this.lane.workingOrders(), "a filled order is no longer held");
    }

    @Test
    void eachSessionHasTheVenuesBudgetToItself() {
        join(A, "session-a", oneOrderASecond());
        join(B, "session-b", oneOrderASecond());
        book(100 * P, 10 * S, 102 * P, 40 * S);
        this.nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));

        command(LaneCommand.NEW, A, 1, Side.Bid, 99 * P);
        command(LaneCommand.NEW, A, 2, Side.Bid, 98 * P);
        command(LaneCommand.NEW, B, 1, Side.Bid, 99 * P);
        assertEquals(1, of(A, ExecType.NEW).size(), "A's second order waits for A's budget");
        assertEquals(1, of(B, ExecType.NEW).size(), "B's isn't held up behind A's");

        this.nanos.addAndGet(MAX_QUEUE_NANOS + 1);
        pass();
        assertEquals(
                List.of(RejectReason.RATE_LIMITED),
                of(A, ExecType.REJECT).stream().map(Heard::reason).toList(),
                "refused once it waited its session's limit, as live refuses it");
    }

    @Test
    void twoSessionsWhoseOrdersCrossBothRestOnBooksOfTheirOwn() {
        join(A, "session-a", null);
        join(B, "session-b", null);
        book(100 * P, 10 * S, 104 * P, 40 * S);

        command(LaneCommand.NEW, A, 1, Side.Bid, 102 * P);
        command(LaneCommand.NEW, B, 1, Side.Ask, 102 * P);

        assertEquals(1, of(A, ExecType.NEW).size());
        assertEquals(1, of(B, ExecType.NEW).size());
        assertTrue(this.heard.stream().noneMatch(h -> h.type() == ExecType.FILL), "paper orders never meet");
        assertTrue(this.heard.stream().noneMatch(h -> h.reason() == RejectReason.SELF_TRADE));
    }

    @Test
    void aSessionsCancelCancelsItsOrdersAndFencesItsNewOnes() {
        join(A, "session-a", null);
        book(100 * P, 10 * S, 104 * P, 40 * S);
        command(LaneCommand.NEW, A, 1, Side.Bid, 99 * P);

        command(LaneCommand.CANCEL_SESSION, A, 0, null, 0);
        assertEquals(OrderStatus.CANCELED, of(A, ExecType.CANCEL).get(0).status());
        command(LaneCommand.NEW, A, 2, Side.Bid, 99 * P);
        assertEquals(
                RejectReason.GATEWAY_REJECTED, of(A, ExecType.REJECT).get(0).reason());

        command(LaneCommand.SESSION, A, 0, null, 0);
        command(LaneCommand.NEW, A, 3, Side.Bid, 99 * P);
        assertEquals(2, of(A, ExecType.NEW).size(), "introduced again, it trades again");
    }

    @Test
    void aSessionPastItsCapIsRefused() {
        this.lane = lane(new AccountLane.Caps(1, 0, 0));
        join(A, "session-a", null);
        book(100 * P, 10 * S, 104 * P, 40 * S);

        command(LaneCommand.NEW, A, 1, Side.Bid, 99 * P);
        command(LaneCommand.NEW, A, 2, Side.Bid, 98 * P);

        assertEquals(1, of(A, ExecType.NEW).size());
        assertEquals(RejectReason.SESSION_CAP, of(A, ExecType.REJECT).get(0).reason());
    }

    @Test
    void aBadRecordFillsNothing_TheNextCleanOneDoes() {
        join(A, "session-a", null);
        book(100 * P, 10 * S, 102 * P, 40 * S);
        command(LaneCommand.NEW, A, 1, Side.Bid, 100 * P);

        trade(group.gnometrading.schemas.book.Side.Ask, 100 * P, 20 * S, true);
        assertTrue(of(A, ExecType.FILL).isEmpty());
        trade(group.gnometrading.schemas.book.Side.Ask, 100 * P, 20 * S, false);
        assertEquals(1, of(A, ExecType.FILL).size());
    }

    @Test
    void aForgottenSessionHasNoBooksLeft() {
        join(A, "session-a", null);
        book(100 * P, 10 * S, 102 * P, 40 * S);
        this.handoffs.offer(new SimulatedLane.RemoveSession(A));
        pass();

        command(LaneCommand.NEW, A, 1, Side.Bid, 99 * P);
        assertEquals(
                RejectReason.GATEWAY_NOT_READY, of(A, ExecType.REJECT).get(0).reason());
    }

    @Test
    void onlyBooksWithSomethingOnItsWayAreWorked_ARestingOrderStillFillsAndItsReportStillArrives() {
        final long latency = TimeUnit.MILLISECONDS.toNanos(10);
        join(A, "session-a", null, latency);
        join(B, "session-b", null, latency);
        book(100 * P, 10 * S, 102 * P, 40 * S);
        assertEquals(0, this.lane.busyBooks(), "a record that sends nothing back leaves both books idle");

        command(LaneCommand.NEW, A, 1, Side.Bid, 100 * P);
        assertEquals(1, this.lane.busyBooks(), "A's order is on its way; B's book has nothing to do");
        assertTrue(of(A, ExecType.NEW).isEmpty());

        this.wallNanos.addAndGet(latency);
        pass();
        assertTrue(of(A, ExecType.NEW).isEmpty(), "at the venue now; its ack is on the way back");
        this.wallNanos.addAndGet(latency);
        pass();
        assertEquals(1, of(A, ExecType.NEW).size());
        assertEquals(0, this.lane.busyBooks(), "a resting order alone gives its book nothing to do");

        trade(group.gnometrading.schemas.book.Side.Ask, 100 * P, 20 * S, false);
        assertEquals(1, this.lane.busyBooks(), "the fill the trade made is on its way back");
        this.wallNanos.addAndGet(latency);
        pass();
        assertEquals(1, of(A, ExecType.FILL).size());
        assertEquals(0, this.lane.busyBooks());
    }

    @Test
    void aSessionForgottenWithAnOrderOnItsWayLeavesNothingBusy() {
        final long latency = TimeUnit.MILLISECONDS.toNanos(10);
        join(A, "session-a", null, latency);
        join(B, "session-b", null, latency);
        book(100 * P, 10 * S, 102 * P, 40 * S);
        command(LaneCommand.NEW, A, 1, Side.Bid, 99 * P);
        command(LaneCommand.NEW, B, 1, Side.Bid, 99 * P);
        assertEquals(2, this.lane.busyBooks());

        this.handoffs.offer(new SimulatedLane.RemoveSession(A));
        pass();
        assertEquals(1, this.lane.busyBooks());

        this.wallNanos.addAndGet(latency);
        pass();
        this.wallNanos.addAndGet(latency);
        pass();
        assertTrue(of(A, ExecType.NEW).isEmpty(), "a forgotten session hears nothing more");
        assertEquals(1, of(B, ExecType.NEW).size(), "the other session's order carries on");
        assertEquals(0, this.lane.busyBooks());
    }
}
