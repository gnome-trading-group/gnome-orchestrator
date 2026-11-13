package group.gnometrading.orchestrator.gateway.simulated;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.gateways.account.admission.AdmissionEntry;
import group.gnometrading.gateways.account.admission.AdmissionSession;
import group.gnometrading.gateways.account.admission.FairQueue;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Liquidity;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.OrderStatus;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.book.Mbp10Decoder;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * A simulated account's lane: one thread for every paper session on the venue. Each pass takes sessions' commands
 * first (cancels never wait), lets each session's queued new orders and amends go as its own budget allows (each
 * session budgets as if it were alone on the account), feeds a bounded number of market-data records to the books, then
 * has each book take what has reached it and send back what has made the trip.
 *
 * <p>Admission is gnome-gateways' (the queue, caps, the hold and its expiry the live lane uses); the venue is
 * gnome-simulation's, one per session and listing. Allocates where the simulator does.
 */
final class SimulatedLane implements GnomeAgent {

    // Market-data records a pass takes at most, so a burst can't hold cancels and admission up for long.
    static final int RECORDS_PER_PASS = 256;
    private static final int COMMANDS_PER_PASS = 256;
    private static final long NO_RECORD = Long.MIN_VALUE;

    /** A session's books, built on the control thread, or a session to forget; handed over to this thread. */
    record AddSession(SimulatedSession session) {}

    record RemoveSession(long slot) {}

    private final ManyToOneRingBuffer<LaneCommand> commands;
    private final ManyToOneRingBuffer<AccountReport> reports;
    private final ManyToOneConcurrentArrayQueue<Object> handoffs;
    private final SequencedPoller marketData;
    private final FairQueue<SimulatedOrder> admission;
    private final SimulatedOrder[] orders;
    private final int[] free;
    private int freeCount;
    private final AccountLane.Caps caps;
    private final LongSupplier nanoClock;
    private final EpochNanoClock wallClock;
    private final Long2ObjectHashMap<SimulatedSession> sessions = new Long2ObjectHashMap<>();
    private final Int2ObjectHashMap<List<SimulatedBook>> booksByStream = new Int2ObjectHashMap<>();
    // Each read stream's newest venue receive time, so only a record newer than it counts towards lag: a shard re-sends
    // a quiet stream's last record with its original time, and a book's first record may be that re-send, minutes old
    // on a quiet market though nothing is late.
    private final Long2LongHashMap lastRecvByStream = new Long2LongHashMap(NO_RECORD);
    // Only books with something on its way or held at the venue: a pass costs what is happening, not how many books
    // there are. A book joins when it is handed an order, cancel, amend or a record that sends something back.
    private final List<SimulatedBook> busy = new ArrayList<>();
    private final Mbp10Schema record = new Mbp10Schema();
    private final OrderExecutionReport reject = new OrderExecutionReport();
    private final SimulatedBook.ReportSink sink = this::deliver;
    private long now;
    private long wallNow;
    private int commandsTaken;

    private volatile int working;
    private volatile long passes;
    // Refused because the listing's book had no clean record yet, and the oldest new record any book took, from the
    // venue's receipt of it: shard to gateway, then the gateway's own queue.
    private volatile long refusedNotReady;
    private volatile long lagMaxNanos;

    SimulatedLane(
            final ManyToOneRingBuffer<LaneCommand> commands,
            final ManyToOneRingBuffer<AccountReport> reports,
            final ManyToOneConcurrentArrayQueue<Object> handoffs,
            final SequencedRingBuffer<?> marketData,
            final int maxOrders,
            final AccountLane.Caps caps,
            final long defaultMaxQueueNanos,
            final LongSupplier nanoClock,
            final EpochNanoClock wallClock) {
        this.commands = commands;
        this.reports = reports;
        this.handoffs = handoffs;
        this.marketData = marketData.createPoller(this::onMarketData);
        this.orders = new SimulatedOrder[maxOrders];
        this.free = new int[maxOrders];
        for (int i = 0; i < maxOrders; i++) {
            this.orders[i] = new SimulatedOrder(i);
            this.free[i] = maxOrders - 1 - i;
        }
        this.freeCount = maxOrders;
        this.caps = caps;
        this.admission = new FairQueue<>(this.orders, defaultMaxQueueNanos, nanoClock, this::expire, 0);
        this.admission.defaultCaps(caps.sessionMaxOpenOrders(), caps.sessionMaxNewPerSecond());
        this.nanoClock = nanoClock;
        this.wallClock = wallClock;
    }

    @Override
    public String roleName() {
        return "simulated-lane";
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.HOT_PATH;
    }

    @Override
    public void onStart() {}

    @Override
    public int doWork() throws Exception {
        this.now = this.nanoClock.getAsLong();
        this.wallNow = this.wallClock.nanoTime();
        int work = takeHandoffs();
        this.commandsTaken = 0;
        this.commands.read(this::onCommand, COMMANDS_PER_PASS);
        work += this.commandsTaken;
        if (this.admission.anyQueued()) {
            this.admission.expireDue();
            work += admit();
        }
        work += this.marketData.poll(RECORDS_PER_PASS);
        // Backwards, so the book a rest moves into this slot has already been worked this pass.
        for (int i = this.busy.size() - 1; i >= 0; i--) {
            final SimulatedBook book = this.busy.get(i);
            work += book.work(this.wallNow, this.sink);
            if (book.idle()) {
                rest(i);
            }
        }
        this.passes = this.passes + 1;
        return work;
    }

    int workingOrders() {
        return this.working;
    }

    long passes() {
        return this.passes;
    }

    /** Books with something on its way or held at the venue; the lane's thread only. */
    int busyBooks() {
        return this.busy.size();
    }

    long refusedNotReady() {
        return this.refusedNotReady;
    }

    /** The most a record waited between the shard and the books since the last call. */
    long takeLagMaxNanos() {
        final long lag = this.lagMaxNanos;
        this.lagMaxNanos = 0;
        return lag;
    }

    // ----- sessions -----

    private int takeHandoffs() {
        int work = 0;
        Object handoff;
        while ((handoff = this.handoffs.poll()) != null) {
            if (handoff instanceof AddSession add) {
                addSession(add.session());
            } else if (handoff instanceof RemoveSession remove) {
                forget(remove.slot());
            }
            work++;
        }
        return work;
    }

    private void addSession(final SimulatedSession session) {
        forget(session.slot);
        this.sessions.put(session.slot, session);
        for (final SimulatedBook book : session.books) {
            this.booksByStream
                    .computeIfAbsent(book.streamId, id -> new ArrayList<>())
                    .add(book);
        }
    }

    /** Drops a session and its books; reports still on their way have no one left to go to. */
    private void forget(final long slot) {
        final SimulatedSession session = this.sessions.remove(slot);
        if (session == null) {
            return;
        }
        for (final SimulatedBook book : session.books) {
            if (book.busy) {
                rest(this.busy.indexOf(book));
            }
            final List<SimulatedBook> onStream = this.booksByStream.get(book.streamId);
            if (onStream != null) {
                onStream.remove(book);
                if (onStream.isEmpty()) {
                    this.booksByStream.remove(book.streamId);
                    // Read again later, the stream starts over: its first record then is a join, not a delay.
                    this.lastRecvByStream.remove(book.streamId);
                }
            }
        }
        for (final SimulatedOrder order : session.orders.values()) {
            this.admission.unqueue(order);
            release(order);
        }
        session.orders.clear();
    }

    // ----- commands -----

    private void onCommand(final LaneCommand command) {
        this.commandsTaken++;
        switch (command.kind) {
            case LaneCommand.SESSION -> {
                this.admission.configure(command.sessionSlot, command.weight, command.maxQueueNanos);
                this.admission.caps(command.sessionSlot, command.maxOpenOrders, command.maxNewPerSecond);
            }
            case LaneCommand.SESSION_ENDED -> this.admission.ended(command.sessionSlot);
            case LaneCommand.NEW -> onNew(command);
            case LaneCommand.CANCEL -> onCancel(command);
            case LaneCommand.AMEND -> onAmend(command);
            case LaneCommand.CANCEL_SESSION -> {
                this.admission.fence(command.sessionSlot);
                cancelOrders(command.sessionSlot, 0, 0);
            }
            case LaneCommand.CANCEL_ORDERS -> cancelOrders(command.sessionSlot, command.exchangeId, command.securityId);
            default -> {}
        }
    }

    private void onNew(final LaneCommand command) {
        final SimulatedSession session = this.sessions.get(command.sessionSlot);
        final SimulatedBook book = session == null ? null : session.bookByListing.get(listingKey(command));
        if (book == null || !book.clean) {
            this.refusedNotReady = this.refusedNotReady + 1;
            refuse(command, ExecType.REJECT, OrderStatus.REJECTED, RejectReason.GATEWAY_NOT_READY);
            return;
        }
        if (this.admission.fenced(command.sessionSlot)) {
            refuse(command, ExecType.REJECT, OrderStatus.REJECTED, RejectReason.GATEWAY_REJECTED);
            return;
        }
        final AdmissionSession place = this.admission.session(command.sessionSlot);
        // As if alone: the account's cap holds for each session's own orders.
        if (place == null
                || this.freeCount == 0
                || (this.caps.accountMaxOpenOrders() > 0 && session.orders.size() >= this.caps.accountMaxOpenOrders())
                || !place.admitNew(this.now)) {
            refuse(command, ExecType.REJECT, OrderStatus.REJECTED, RejectReason.SESSION_CAP);
            return;
        }
        final SimulatedOrder order = this.orders[this.free[--this.freeCount]];
        order.reuse();
        order.slot = command.sessionSlot;
        order.counter = command.clientOidCounter;
        order.strategyId = command.clientOidStrategyId;
        order.exchangeId = command.exchangeId;
        order.securityId = command.securityId;
        order.side = command.side;
        order.price = command.price;
        order.size = command.size;
        order.timeInForce = command.timeInForce;
        order.orderType = command.orderType;
        order.postOnly = command.postOnly;
        order.book = book;
        session.orders.put(order.counter, order);
        place.opened();
        this.working = this.working + 1;
        this.admission.enqueue(order, AdmissionEntry.SUBMIT);
    }

    private void onCancel(final LaneCommand command) {
        final SimulatedSession session = this.sessions.get(command.sessionSlot);
        final SimulatedOrder order = session == null ? null : session.orders.get(command.clientOidCounter);
        if (order == null) {
            refuse(command, ExecType.CANCEL_REJECT, OrderStatus.CANCELED, RejectReason.UNKNOWN_ORDER);
            return;
        }
        cancel(order);
    }

    private void onAmend(final LaneCommand command) {
        final SimulatedSession session = this.sessions.get(command.sessionSlot);
        final SimulatedOrder order = session == null ? null : session.orders.get(command.clientOidCounter);
        if (order == null) {
            refuse(command, ExecType.CANCEL_REJECT, OrderStatus.CANCELED, RejectReason.UNKNOWN_ORDER);
            return;
        }
        if (!order.sent) {
            // Its submit hasn't gone yet: it goes at the new terms.
            order.price = command.price;
            order.size = command.size;
            order.postOnly = command.postOnly;
            return;
        }
        order.amendPrice = command.price;
        order.amendSize = command.size;
        order.amendPostOnly = command.postOnly;
        if (!order.amendWanted) {
            order.amendWanted = true;
            this.admission.enqueue(order, AdmissionEntry.AMEND);
        }
    }

    /** Every order of the session's, or only those on one listing when {@code exchangeId} is set. */
    private void cancelOrders(final long slot, final int exchangeId, final long securityId) {
        final SimulatedSession session = this.sessions.get(slot);
        if (session == null) {
            return;
        }
        final List<SimulatedOrder> covered = new ArrayList<>();
        for (final SimulatedOrder order : session.orders.values()) {
            if (exchangeId == 0 || (order.exchangeId == exchangeId && order.securityId == securityId)) {
                covered.add(order);
            }
        }
        for (final SimulatedOrder order : covered) {
            cancel(order);
        }
    }

    private void cancel(final SimulatedOrder order) {
        if (!order.sent) {
            // Never reached the venue: it ends here, as a venue's cancel of it would.
            this.admission.unqueue(order);
            end(order, ExecType.CANCEL, OrderStatus.CANCELED, RejectReason.NULL_VAL);
            return;
        }
        if (order.amendWanted) {
            order.amendWanted = false;
            this.admission.unqueue(order);
        }
        order.book.cancel(order, this.wallNow);
        wake(order.book);
    }

    // ----- admission -----

    /** Each session's queued requests as its own budget allows: no session waits behind another's. */
    private int admit() {
        int work = 0;
        AdmissionSession place = this.admission.firstQueued();
        while (place != null) {
            final AdmissionSession next = this.admission.nextQueued(place);
            work += admit(place);
            place = next;
        }
        return work;
    }

    private int admit(final AdmissionSession place) {
        final SimulatedSession session = this.sessions.get(place.slot());
        int work = 0;
        int head;
        while ((head = place.head()) != -1) {
            final SimulatedOrder order = this.orders[head];
            final int kind = order.queuedKind();
            if (session == null || !order.stillQueued(kind)) {
                this.admission.unqueue(order);
                continue;
            }
            final boolean submit = kind == AdmissionEntry.SUBMIT;
            if (!session.admits(
                    submit ? session.newRule : session.amendRule,
                    submit ? RequestClass.NEW : RequestClass.AMEND,
                    this.now)) {
                break;
            }
            this.admission.unqueue(order);
            if (submit) {
                order.sent = true;
                order.book.submit(order, this.wallNow);
            } else {
                order.amendWanted = false;
                order.book.amend(order, this.wallNow);
            }
            wake(order.book);
            work++;
        }
        return work;
    }

    /** A queued request that waited longer than its session allows for its budget. */
    private void expire(final SimulatedOrder order) {
        final int kind = order.queuedKind();
        this.admission.unqueue(order);
        if (!order.stillQueued(kind)) {
            return;
        }
        if (kind == AdmissionEntry.SUBMIT) {
            end(order, ExecType.REJECT, OrderStatus.REJECTED, RejectReason.RATE_LIMITED);
        } else {
            order.amendWanted = false;
            write(
                    order.slot,
                    order.counter,
                    order.strategyId,
                    order.exchangeId,
                    order.securityId,
                    ExecType.CANCEL_REJECT,
                    OrderStatus.CANCELED,
                    RejectReason.RATE_LIMITED);
        }
    }

    // ----- market data and reports -----

    private void onMarketData(final long globalSeq, final int templateId, final UnsafeBuffer buffer, final int length) {
        if (templateId != Mbp10Decoder.TEMPLATE_ID) {
            return;
        }
        this.record.wrap(buffer);
        final List<SimulatedBook> onStream = this.booksByStream.get(this.record.streamId());
        if (onStream == null) {
            return;
        }
        final long recv = this.record.getRecvTimestamp();
        final long newest = this.lastRecvByStream.get(this.record.streamId());
        if (newest == NO_RECORD || recv > newest) {
            this.lastRecvByStream.put(this.record.streamId(), recv);
            final long lag = this.wallNow - recv;
            if (newest != NO_RECORD && lag > this.lagMaxNanos) {
                this.lagMaxNanos = lag;
            }
        }
        for (int i = 0; i < onStream.size(); i++) {
            final SimulatedBook book = onStream.get(i);
            book.onMarketData(this.record, this.wallNow);
            wake(book);
        }
    }

    /** Puts a book on the busy list if it now has something to do. */
    private void wake(final SimulatedBook book) {
        if (!book.busy && !book.idle()) {
            book.busy = true;
            this.busy.add(book);
        }
    }

    /** Takes the book at {@code index} off the busy list; order there doesn't matter, so the last book takes its place. */
    private void rest(final int index) {
        final int last = this.busy.size() - 1;
        this.busy.get(index).busy = false;
        this.busy.set(index, this.busy.get(last));
        this.busy.remove(last);
    }

    /** A report a book sends back; the order ends with it if it is final. */
    private boolean deliver(final long slot, final OrderExecutionReport report) {
        final int index = this.reports.tryClaim();
        if (index < 0) {
            return false;
        }
        SimulatedBook.copy(this.reports.indexAt(index), slot, report);
        this.reports.commit(index);
        final OrderStatus status = report.decoder.orderStatus();
        if (status == OrderStatus.FILLED
                || status == OrderStatus.CANCELED && report.decoder.execType() != ExecType.CANCEL_REJECT
                || status == OrderStatus.REJECTED
                || status == OrderStatus.EXPIRED) {
            final SimulatedSession session = this.sessions.get(slot);
            final SimulatedOrder order = session == null ? null : session.orders.get(report.getClientOidCounter());
            if (order != null) {
                release(order);
            }
        }
        return true;
    }

    /** Ends an order the venue never had, with a report the lane makes itself. */
    private void end(
            final SimulatedOrder order, final ExecType type, final OrderStatus status, final RejectReason why) {
        write(order.slot, order.counter, order.strategyId, order.exchangeId, order.securityId, type, status, why);
        release(order);
    }

    private void refuse(
            final LaneCommand command, final ExecType type, final OrderStatus status, final RejectReason why) {
        write(
                command.sessionSlot,
                command.clientOidCounter,
                command.clientOidStrategyId,
                command.exchangeId,
                command.securityId,
                type,
                status,
                why);
    }

    private void release(final SimulatedOrder order) {
        final SimulatedSession session = this.sessions.get(order.slot);
        if (session != null && session.orders.remove(order.counter) == null) {
            return;
        }
        final AdmissionSession place = this.admission.existing(order.slot);
        if (place != null) {
            place.closed();
        }
        this.working = this.working - 1;
        order.reuse();
        this.free[this.freeCount++] = order.index;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void write(
            final long slot,
            final long counter,
            final int strategyId,
            final int exchangeId,
            final long securityId,
            final ExecType type,
            final OrderStatus status,
            final RejectReason why) {
        final OrderExecutionReportEncoder encoder = this.reject.encoder;
        encoder.exchangeId(exchangeId);
        encoder.securityId(securityId);
        for (int i = 0; i < OrderExecutionReportEncoder.exchangeOrderIdLength(); i++) {
            encoder.exchangeOrderId(i, (byte) 0);
        }
        this.reject.encodeClientOid(counter, strategyId);
        encoder.execType(type);
        encoder.orderStatus(status);
        encoder.filledQty(OrderExecutionReportEncoder.filledQtyNullValue());
        encoder.fillPrice(OrderExecutionReportEncoder.fillPriceNullValue());
        encoder.cumulativeQty(0);
        encoder.leavesQty(0);
        encoder.timestampEvent(this.wallNow);
        encoder.timestampRecv(this.wallNow);
        encoder.flags().clear();
        encoder.rejectReason(why);
        encoder.fee(OrderExecutionReportEncoder.feeNullValue());
        encoder.liquidity(Liquidity.NULL_VAL);
        // The ring is sized past what every session can hold; one that is full drops this, as a venue's lost answer.
        final int index = this.reports.tryClaim();
        if (index >= 0) {
            SimulatedBook.copy(this.reports.indexAt(index), slot, this.reject);
            this.reports.commit(index);
        }
    }

    private static long listingKey(final LaneCommand command) {
        return SimulatedAccount.listingKey(command.exchangeId, command.securityId);
    }
}
