package group.gnometrading.orchestrator.session.rewards;

import group.gnometrading.RegistryConnection;
import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.md.StreamDirectory;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportDecoder;
import group.gnometrading.schemas.Side;
import group.gnometrading.sequencer.LappingPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.simulation.rewards.RewardAccrual;
import group.gnometrading.simulation.rewards.RewardScorer;
import group.gnometrading.simulation.rewards.venues.RewardVenues;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.ListingSpec;
import group.gnometrading.sm.incentive.IncentiveProgram;
import group.gnometrading.sm.incentive.IncentiveTimeline;
import group.gnometrading.sm.incentive.IncentiveTimelineJson;
import group.gnometrading.strings.ExpandingMutableString;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.ViewString;
import group.gnometrading.utils.Schedule;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Estimates what a paper session's resting orders earn from venue liquidity programs, while it trades, and posts the
 * estimates to the registry ({@code /ledger/reward-accruals}).
 *
 * <p>It reads the session's market data, the OMS's outbound orders and the reports reaching the OMS through lapping
 * pollers, so it never holds up any of them, taking their events in the session's global sequence, the order they
 * happened in; from those it rebuilds our resting orders as the OMS does (a NEW report
 * rests the order at its last price, fills shrink it to what's left, a cancel, expiry, reject or full fill removes
 * it) and feeds them and the books to a {@link RewardScorer}. Everything is taken at the session's receive time.
 * Falling a whole ring behind loses track: every market scores nothing until its next book, and the lap is counted in
 * what it posts. It reloads its listings' programs from the registry as now known, and posts what accrued every post
 * interval and once more on close. A failed post or reload is logged and retried; nothing here can stop trading.
 */
public final class RewardsAgent implements GnomeAgent {

    private static final GnomeString POST_PATH = new ViewString("/api/ledger/reward-accruals");
    private static final String PROGRAMS_PATH = "/api/incentive-programs?listingIds=";
    private static final int HTTP_OK = 200;
    private static final long MAX_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int ORDERS_PER_MARKET = 64;
    private static final int ORDERS = 4096;
    // An accrual's entry is well under this; the body holds the few dozen a session runs.
    private static final int ENTRY_BYTES = 768;
    /** The most a post's body takes, so the connection that sends it can be sized. */
    public static final int BODY_BYTES = 256 * 1024;

    private static final int LISTINGS_PER_PROGRAM = 2;

    private final String sessionId;
    private final RegistryConnection registry;
    private final EpochClock clock;
    private final Logger logger;
    private final StreamDirectory streams;
    private final int[] listingIds;
    private final RewardScorer scorer = new RewardScorer(RewardScorer.Mode.INSERT_OURS, ORDERS_PER_MARKET, ORDERS);
    private static final int BOOK_RING = 0;
    private static final int ORDER_RING = 1;
    private static final int REPORT_RING = 2;
    private static final int RINGS = 3;
    private static final long PEEK = -1;
    // Events taken in one pass, so the agent's schedules still run while a backlog drains.
    private static final int MAX_EVENTS_PER_PASS = 4096;

    private final LappingPoller[] pollers = new LappingPoller[RINGS];
    // Per ring: take events with a global sequence up to the limit, and hold (and note) the first past it.
    private final long[] limits = new long[RINGS];
    private final long[] heads = new long[RINGS];
    private final boolean[] held = new boolean[RINGS];
    private int taken;
    private final Mbp10Schema book = new Mbp10Schema();
    private final Order order = new Order();
    private final ModifyOrder modify = new ModifyOrder();
    private final OrderExecutionReport report = new OrderExecutionReport();
    private final Long2ObjectHashMap<Tracked> tracked = new Long2ObjectHashMap<>();
    private final Tracked[] free = new Tracked[ORDERS];
    private int freeCount;
    private final Schedule refreshSchedule;
    private final Schedule postSchedule;
    private final ByteBuffer body = ByteBuffer.allocate(BODY_BYTES);
    private final JsonEncoder json = new JsonEncoder();
    private long lappedSeen;
    private int gaps;
    private boolean postFailing;

    /** An order we sent, by client order id: where it would rest. */
    private static final class Tracked {
        int listingId;
        boolean buy;
        long price;
    }

    private RewardsAgent(
            final String sessionId,
            final List<Listing> bound,
            final List<ListingSpec> specs,
            final StreamDirectory streams,
            final SequencedRingBuffer<?> marketData,
            final SequencedRingBuffer<?> outboundOrders,
            final SequencedRingBuffer<?> execReports,
            final RegistryConnection registry,
            final EpochClock clock,
            final long refreshIntervalMs,
            final long postIntervalMs,
            final Logger logger) {
        this.sessionId = sessionId;
        this.registry = registry;
        this.clock = clock;
        this.logger = logger;
        this.streams = streams;
        this.listingIds = new int[bound.size()];
        for (int i = 0; i < bound.size(); i++) {
            final Listing listing = bound.get(i);
            final RewardVenues.Binding binding = RewardVenues.of(listing);
            final long tick = specs.get(i).tickSize();
            this.scorer.bind(
                    listing.listingId(),
                    binding.marketKey(),
                    binding.complement(),
                    binding.rule(),
                    IncentiveTimeline.EMPTY,
                    nanos -> tick);
            this.listingIds[i] = listing.listingId();
        }
        for (int i = 0; i < ORDERS; i++) {
            this.free[i] = new Tracked();
        }
        this.freeCount = ORDERS;
        this.pollers[BOOK_RING] = marketData.createControlledLappingPoller(
                (sequence, templateId, buffer, length) -> offer(BOOK_RING, sequence, templateId, buffer, length));
        this.pollers[ORDER_RING] = outboundOrders.createControlledLappingPoller(
                (sequence, templateId, buffer, length) -> offer(ORDER_RING, sequence, templateId, buffer, length));
        this.pollers[REPORT_RING] = execReports.createControlledLappingPoller(
                (sequence, templateId, buffer, length) -> offer(REPORT_RING, sequence, templateId, buffer, length));
        this.refreshSchedule = new Schedule(clock, refreshIntervalMs, this::refreshPrograms);
        this.postSchedule = new Schedule(clock, postIntervalMs, this::postNow);
    }

    /**
     * The agent for a session's listings whose venue runs a program the scorer estimates, or null when none does.
     * Create it before the session's producers start: its pollers see only what is published after.
     *
     * @param specs each listing's spec now, in the order of {@code listings}, for its price tick
     * @param registry a connection of its own: a registry connection serves one thread
     */
    public static RewardsAgent create(
            final String sessionId,
            final List<Listing> listings,
            final List<ListingSpec> specs,
            final StreamDirectory streams,
            final SequencedRingBuffer<?> marketData,
            final SequencedRingBuffer<?> outboundOrders,
            final SequencedRingBuffer<?> execReports,
            final RegistryConnection registry,
            final EpochClock clock,
            final long refreshIntervalMs,
            final long postIntervalMs,
            final Logger logger) {
        final List<Integer> scored = java.util.stream.IntStream.range(0, listings.size())
                .filter(i -> RewardVenues.of(listings.get(i)) != null && specs.get(i) != null)
                .boxed()
                .toList();
        if (scored.isEmpty()) {
            return null;
        }
        return new RewardsAgent(
                sessionId,
                scored.stream().map(listings::get).toList(),
                scored.stream().map(specs::get).toList(),
                streams,
                marketData,
                outboundOrders,
                execReports,
                registry,
                clock,
                refreshIntervalMs,
                postIntervalMs,
                logger);
    }

    @Override
    public void onStart() {
        this.refreshSchedule.start();
        this.refreshSchedule.forceTrigger();
        this.postSchedule.start();
    }

    @Override
    public int doWork() throws Exception {
        final int work = pollRings();
        this.refreshSchedule.check();
        this.postSchedule.check();
        if (work == 0) {
            LockSupport.parkNanos(MAX_PARK_NANOS);
        }
        return work;
    }

    /**
     * Takes what is new on the three rings into the scorer, merged by global sequence: each ring's next event is peeked
     * (held), and the ring holding the earliest gives up every event before the next-earliest ring's. Package-private
     * so a test can drive it alone.
     */
    int pollRings() throws Exception {
        this.taken = 0;
        for (int ring = 0; ring < RINGS; ring++) {
            peek(ring);
        }
        while (this.taken < MAX_EVENTS_PER_PASS) {
            final int first = earliestHeld(-1);
            if (first < 0) {
                break;
            }
            final int second = earliestHeld(first);
            this.limits[first] = second < 0 ? Long.MAX_VALUE : this.heads[second];
            this.held[first] = false;
            this.pollers[first].poll();
            if (!this.held[first]) {
                peek(first);
            }
        }
        checkLapped();
        return this.taken;
    }

    private void peek(final int ring) throws Exception {
        this.limits[ring] = PEEK;
        this.held[ring] = false;
        this.pollers[ring].poll();
    }

    /** The ring whose held event comes first, other than {@code except}; -1 when none holds one. */
    private int earliestHeld(final int except) {
        int earliest = -1;
        for (int ring = 0; ring < RINGS; ring++) {
            if (ring != except && this.held[ring] && (earliest < 0 || this.heads[ring] < this.heads[earliest])) {
                earliest = ring;
            }
        }
        return earliest;
    }

    private boolean offer(
            final int ring, final long sequence, final int templateId, final UnsafeBuffer buffer, final int length) {
        if (sequence > this.limits[ring] || this.taken >= MAX_EVENTS_PER_PASS) {
            this.heads[ring] = sequence;
            this.held[ring] = true;
            return false;
        }
        this.taken++;
        if (ring == BOOK_RING) {
            onBook(sequence, templateId, buffer, length);
        } else if (ring == ORDER_RING) {
            onOrder(sequence, templateId, buffer, length);
        } else {
            onReport(sequence, templateId, buffer, length);
        }
        return true;
    }

    @Override
    public void onClose() {
        postNow();
    }

    @Override
    public String roleName() {
        return "rewards";
    }

    private void onBook(final long globalSequence, final int templateId, final UnsafeBuffer buffer, final int length) {
        this.book.buffer.putBytes(0, buffer, 0, length);
        final int listingId = this.streams.listingId((int) this.book.decoder.streamId());
        if (listingId == StreamDirectory.NONE) {
            return;
        }
        final long at = this.book.getRecvTimestamp();
        if (this.book.isBadData()) {
            this.scorer.onGap(listingId, at);
        } else {
            this.scorer.onBook(listingId, at, this.book.decoder);
        }
    }

    private void onOrder(final long globalSequence, final int templateId, final UnsafeBuffer buffer, final int length) {
        if (templateId == OrderDecoder.TEMPLATE_ID) {
            this.order.buffer.putBytes(0, buffer, 0, length);
            final OrderDecoder decoder = this.order.decoder;
            final int listingId = this.streams.listingId(decoder.exchangeId(), (int) decoder.securityId());
            if (listingId == StreamDirectory.NONE || this.freeCount == 0) {
                return;
            }
            final Tracked order = this.free[--this.freeCount];
            order.listingId = listingId;
            order.buy = decoder.side() == Side.Bid;
            order.price = decoder.price();
            final Tracked replaced = this.tracked.put(this.order.getClientOidCounter(), order);
            if (replaced != null) {
                this.free[this.freeCount++] = replaced;
            }
        } else if (templateId == ModifyOrderDecoder.TEMPLATE_ID) {
            this.modify.buffer.putBytes(0, buffer, 0, length);
            final Tracked order = this.tracked.get(this.modify.getClientOidCounter());
            if (order != null) {
                order.price = this.modify.decoder.price();
            }
        }
    }

    private void onReport(
            final long globalSequence, final int templateId, final UnsafeBuffer buffer, final int length) {
        if (templateId != OrderExecutionReportDecoder.TEMPLATE_ID) {
            return;
        }
        this.report.buffer.putBytes(0, buffer, 0, length);
        final long counter = this.report.getClientOidCounter();
        final Tracked order = this.tracked.get(counter);
        if (order == null) {
            return;
        }
        final OrderExecutionReportDecoder decoder = this.report.decoder;
        final long received = decoder.timestampRecv();
        final long at = received == OrderExecutionReportDecoder.timestampRecvNullValue() || received <= 0
                ? this.clock.time() * NANOS_PER_MILLI
                : received;
        final ExecType type = decoder.execType();
        if (type == ExecType.NEW || type == ExecType.PARTIAL_FILL) {
            this.scorer.onOrder(order.listingId, counter, order.buy, order.price, decoder.leavesQty(), at);
        } else if (ended(type)) {
            this.scorer.onOrder(order.listingId, counter, order.buy, order.price, 0, at);
            this.tracked.remove(counter);
            this.free[this.freeCount++] = order;
        }
    }

    /** The order no longer rests: filled, cancelled, rejected or expired. */
    private static boolean ended(final ExecType type) {
        return type == ExecType.FILL || type == ExecType.CANCEL || type == ExecType.REJECT || type == ExecType.EXPIRE;
    }

    /** A lap on any ring loses the books or our orders: every market scores nothing until its next book. */
    private void checkLapped() {
        final long lapped = this.pollers[BOOK_RING].lapped()
                + this.pollers[ORDER_RING].lapped()
                + this.pollers[REPORT_RING].lapped();
        if (lapped == this.lappedSeen) {
            return;
        }
        this.lappedSeen = lapped;
        this.gaps++;
        final long now = this.clock.time() * NANOS_PER_MILLI;
        for (final int listingId : this.listingIds) {
            this.scorer.onGap(listingId, now);
        }
    }

    /** Reloads the listings' programs as the registry knows them now; a failed reload keeps the last ones. */
    void refreshPrograms() {
        try {
            final ExpandingMutableString path = new ExpandingMutableString(PROGRAMS_PATH);
            for (int i = 0; i < this.listingIds.length; i++) {
                if (i > 0) {
                    path.append((byte) ',');
                }
                path.appendNaturalIntAscii(this.listingIds[i]);
            }
            final ByteBuffer response = this.registry.get(path);
            final byte[] bytes = new byte[response.remaining()];
            response.get(bytes);
            for (final int listingId : this.listingIds) {
                this.scorer.setPrograms(listingId, IncentiveTimelineJson.parse(bytes, listingId));
            }
        } catch (Exception e) {
            this.logger.logf(LogMessage.UNKNOWN_ERROR, "Reward programs didn't reload, keeping the last: %s", e);
        }
    }

    /** Posts what every running program has accrued up to now, and drops those whose period has ended once posted. */
    void postNow() {
        final long nowMs = this.clock.time();
        final long now = nowMs * NANOS_PER_MILLI;
        this.scorer.advanceTo(now);
        final int entries = encode(nowMs, now);
        if (entries == 0) {
            return;
        }
        final int status = this.registry.tryPost(POST_PATH, this.body.array(), this.body.position());
        if (status == HTTP_OK) {
            if (this.postFailing) {
                this.logger.logf(LogMessage.DEBUG, "Reward accruals posting again");
            }
            this.postFailing = false;
            this.scorer.retireEndedBefore(now);
        } else if (!this.postFailing) {
            this.logger.logf(LogMessage.UNKNOWN_ERROR, "Reward accruals didn't post: the registry answered %d", status);
            this.postFailing = true;
        }
    }

    /** Writes the body; returns how many accruals it holds. Package-private so a test can read what it sends. */
    int encode(final long nowMs, final long now) {
        this.body.clear();
        this.json.wrap(this.body);
        this.json
                .writeObjectStart()
                .writeObjectEntry("sessionId", this.sessionId)
                .writeComma();
        this.json.writeString("accruals").writeColon().writeArrayStart();
        int entries = 0;
        for (int i = 0; i < this.scorer.accrualCount(); i++) {
            final RewardAccrual accrual = this.scorer.accrual(i);
            final IncentiveProgram program = accrual.program();
            if (program.periodStartNanos() >= now) {
                continue;
            }
            for (int slot = 0; slot < LISTINGS_PER_PROGRAM; slot++) {
                final int listingId = accrual.listingId(slot);
                if (listingId < 0 || this.body.remaining() < ENTRY_BYTES) {
                    continue;
                }
                if (entries > 0) {
                    this.json.writeComma();
                }
                writeEntry(accrual, program, slot, listingId, nowMs);
                entries++;
            }
        }
        this.json.writeArrayEnd().writeObjectEnd();
        return entries;
    }

    private void writeEntry(
            final RewardAccrual accrual,
            final IncentiveProgram program,
            final int slot,
            final int listingId,
            final long nowMs) {
        this.json
                .writeObjectStart()
                .writeObjectEntry("listingId", listingId)
                .writeComma()
                .writeObjectEntry("venueMarket", accrual.marketKey())
                .writeComma()
                .writeObjectEntry("venueProgramId", accrual.venueProgramId())
                .writeComma()
                .writeObjectEntry("program", program.program().name())
                .writeComma()
                .writeObjectEntry("periodStartMs", program.periodStartNanos() / NANOS_PER_MILLI)
                .writeComma();
        if (program.periodEndNanos() == IncentiveProgram.OPEN_ENDED) {
            this.json.writeString("periodEndMs").writeColon().writeNull();
        } else {
            this.json.writeObjectEntry("periodEndMs", program.periodEndNanos() / NANOS_PER_MILLI);
        }
        this.json
                .writeComma()
                .writeObjectEntry("asOfMs", nowMs)
                .writeComma()
                .writeObjectEntry("gross", accrual.gross(slot))
                .writeComma()
                .writeObjectEntry("marketGross", accrual.marketGross())
                .writeComma()
                .writeObjectEntry("marketPaid", accrual.marketPaid())
                .writeComma()
                .writeObjectEntry("countingNanos", accrual.countingNanos())
                .writeComma()
                .writeObjectEntry("oneSidedNanos", accrual.oneSidedNanos())
                .writeComma()
                .writeObjectEntry("depthUnknownNanos", accrual.depthUnknownNanos())
                .writeComma()
                .writeObjectEntry("noDataNanos", accrual.noDataNanos())
                .writeComma()
                .writeObjectEntry("gaps", this.gaps)
                .writeObjectEnd();
    }

    /** The body last encoded, for tests. */
    String bodyText() {
        return new String(this.body.array(), 0, this.body.position(), java.nio.charset.StandardCharsets.UTF_8);
    }

    RewardScorer scorer() {
        return this.scorer;
    }
}
