package group.gnometrading.orchestrator.session.lifecycle;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.oms.ledger.LedgerAgent;
import group.gnometrading.oms.ledger.LedgerRing;
import group.gnometrading.oms.pnl.PriceSlotRegistry;
import group.gnometrading.oms.pnl.SharedPriceBuffer;
import group.gnometrading.oms.position.Position;
import group.gnometrading.oms.position.PositionView;
import group.gnometrading.orchestrator.runtime.GatewayHealth;
import group.gnometrading.orchestrator.session.journal.JournalSegmentSealer;
import java.util.List;

/**
 * The session's state over time, every interval, as samples of their own kinds:
 * <ul>
 *   <li>{@code session.positions}: per listing, the position (net, cost, realized, fees, leaves, open orders) and its
 *       book (bid, ask, last trade, mark, and whether it is flagged bad);
 *   <li>{@code session.ledger}: events published and stored, the lag between, whether fenced, failures in a row;
 *   <li>{@code session.gateways}: each order gateway link, linked or not and the orders working through it;
 *   <li>{@code session.marketdata}: each market-data stream, whether its book is trusted, and since the last sample its
 *       resyncs, records and bytes passed on, shard re-sends dropped and messages refused (-1 where a feed doesn't
 *       count one);
 *   <li>{@code session.journal}: segments waiting to ship and how old the oldest is.
 * </ul>
 * Positions and feeds go out in parts ({@code part}, {@code parts}) a millisecond apart, so a large session's never
 * outgrow a sample. Reads only what the agents publish for other threads (seqlocked positions and prices, volatile fields), on the
 * heartbeat's thread; allocates nothing once constructed.
 */
public final class SessionStateSampler {

    // About 230 bytes a listing and 190 a feed: parts of these stay near 10 KB.
    static final int LISTINGS_PER_SAMPLE = 40;
    static final int FEEDS_PER_SAMPLE = 50;

    private final SampleBuffer samples;
    private final PositionView positions;
    private final SharedPriceBuffer prices;
    private final PriceSlotRegistry priceSlots;
    private final LedgerRing ledgerRing;
    private final LedgerAgent ledger;
    private final OrderGatewayLinkStatus[] links;
    private final GatewayHealth[] feeds;
    private final JournalSegmentSealer journal;
    private final long intervalMs;
    private final long[] top = new long[3];
    private final long[] previousResyncs;
    private final long[] previousRecords;
    private final long[] previousBytes;
    private final long[] previousRepeats;
    private final long[] previousRefused;
    // Each feed's counters as read for this sample, so what is written and what the next sample starts from agree.
    private final long[] nowResyncs;
    private final long[] nowRecords;
    private final long[] nowBytes;
    private final long[] nowRepeats;
    private final long[] nowRefused;
    private boolean first = true;
    private long nextAtMs;

    /**
     * @param positions a view of this session's strategy's positions for this thread alone
     * @param ledgerRing null, with {@code ledger}, for a run that writes no ledger
     * @param journal null with the journal off
     */
    public SessionStateSampler(
            final SampleBuffer samples,
            final PositionView positions,
            final SharedPriceBuffer prices,
            final PriceSlotRegistry priceSlots,
            final LedgerRing ledgerRing,
            final LedgerAgent ledger,
            final List<? extends OrderGatewayLinkStatus> links,
            final List<GatewayHealth> feeds,
            final JournalSegmentSealer journal,
            final long intervalMs) {
        this.samples = samples;
        this.positions = positions;
        this.prices = prices;
        this.priceSlots = priceSlots;
        this.ledgerRing = ledgerRing;
        this.ledger = ledger;
        this.links = links.toArray(new OrderGatewayLinkStatus[0]);
        this.feeds = feeds.toArray(new GatewayHealth[0]);
        this.journal = journal;
        this.intervalMs = intervalMs;
        this.previousResyncs = new long[this.feeds.length];
        this.previousRecords = new long[this.feeds.length];
        this.previousBytes = new long[this.feeds.length];
        this.previousRepeats = new long[this.feeds.length];
        this.previousRefused = new long[this.feeds.length];
        this.nowResyncs = new long[this.feeds.length];
        this.nowRecords = new long[this.feeds.length];
        this.nowBytes = new long[this.feeds.length];
        this.nowRepeats = new long[this.feeds.length];
        this.nowRefused = new long[this.feeds.length];
    }

    /**
     * Takes the samples if due at {@code nowMs}; @return whether it did. A sample that fails to write (too large, say) is
     * abandoned without stopping the others; the first failure is rethrown once all are done, for the caller to log.
     */
    public boolean sampleIfDue(final long nowMs) {
        if (nowMs < nextAtMs) {
            return false;
        }
        nextAtMs = nowMs + intervalMs;
        RuntimeException failure = null;
        for (int part = 0; part < chunks(priceSlots.count(), LISTINGS_PER_SAMPLE); part++) {
            failure = firstOf(failure, writePositions(nowMs, part));
        }
        if (ledgerRing != null) {
            failure = firstOf(failure, writeLedger(nowMs));
        }
        failure = firstOf(failure, writeGateways(nowMs));
        for (int part = 0; part < chunks(feeds.length, FEEDS_PER_SAMPLE); part++) {
            failure = firstOf(failure, writeMarketData(nowMs, part));
        }
        if (journal != null) {
            failure = firstOf(failure, writeJournal(nowMs));
        }
        first = false;
        if (failure != null) {
            throw failure;
        }
        return true;
    }

    private static int chunks(final int items, final int perChunk) {
        return Math.max(1, (items + perChunk - 1) / perChunk);
    }

    private static RuntimeException firstOf(final RuntimeException first, final RuntimeException next) {
        return first != null ? first : next;
    }

    // A kind that grows with the session goes out in parts, each its own sample a millisecond apart (one sample per
    // kind and moment is the registry's key), so none outgrows what a heartbeat can carry.
    private JsonEncoder beginPart(final String kind, final long nowMs, final int part, final int parts) {
        return samples.begin(kind, nowMs + part)
                .writeObjectEntry("part", part)
                .writeComma()
                .writeObjectEntry("parts", parts)
                .writeComma();
    }

    private RuntimeException writePositions(final long nowMs, final int part) {
        try {
            final int count = priceSlots.count();
            final JsonEncoder json = beginPart("session.positions", nowMs, part, chunks(count, LISTINGS_PER_SAMPLE));
            json.writeString("listings").writeColon().writeArrayStart();
            final int end = Math.min(count, (part + 1) * LISTINGS_PER_SAMPLE);
            for (int slot = part * LISTINGS_PER_SAMPLE; slot < end; slot++) {
                final int listingId = priceSlots.listingId(slot);
                final Position position = positions.getPosition(listingId);
                prices.readTop(slot, top);
                if (slot > part * LISTINGS_PER_SAMPLE) {
                    json.writeComma();
                }
                json.writeObjectStart()
                        .writeObjectEntry("listingId", listingId)
                        .writeComma()
                        .writeObjectEntry("netQuantity", position.netQuantity)
                        .writeComma()
                        .writeObjectEntry("totalCost", position.totalCost)
                        .writeComma()
                        .writeObjectEntry("realizedPnl", position.realizedPnl)
                        .writeComma()
                        .writeObjectEntry("totalFees", position.totalFees)
                        .writeComma()
                        .writeObjectEntry("leavesBuyQty", position.leavesBuyQty)
                        .writeComma()
                        .writeObjectEntry("leavesSellQty", position.leavesSellQty)
                        .writeComma()
                        .writeObjectEntry("openOrders", position.openOrders)
                        .writeComma()
                        .writeObjectEntry("bid", top[0])
                        .writeComma()
                        .writeObjectEntry("ask", top[1])
                        .writeComma()
                        .writeObjectEntry("lastTrade", top[2])
                        .writeComma()
                        .writeObjectEntry("mark", prices.markPrice(slot))
                        .writeComma()
                        .writeObjectEntry("bad", prices.badSince(slot) != 0)
                        .writeObjectEnd();
            }
            json.writeArrayEnd();
            samples.commit();
            return null;
        } catch (RuntimeException e) {
            samples.abandon();
            return e;
        }
    }

    private RuntimeException writeLedger(final long nowMs) {
        try {
            final long published = ledgerRing.publishedEvents();
            final long stored = ledgerRing.acknowledgedEvents();
            samples.begin("session.ledger", nowMs)
                    .writeObjectEntry("published", published)
                    .writeComma()
                    .writeObjectEntry("stored", stored)
                    .writeComma()
                    .writeObjectEntry("lag", Math.max(0, published - stored))
                    .writeComma()
                    .writeObjectEntry("fenced", ledgerRing.isFenced())
                    .writeComma()
                    .writeObjectEntry("consecutiveFailures", ledger == null ? 0 : ledger.consecutiveFailures())
                    .writeComma()
                    .writeObjectEntry("lastStoredAgoMs", lastStoredAgoMs(nowMs));
            samples.commit();
            return null;
        } catch (RuntimeException e) {
            samples.abandon();
            return e;
        }
    }

    private long lastStoredAgoMs(final long nowMs) {
        final long last = ledger == null ? 0 : ledger.lastAcceptedMs();
        return last == 0 ? -1 : nowMs - last;
    }

    private RuntimeException writeGateways(final long nowMs) {
        try {
            final JsonEncoder json = samples.begin("session.gateways", nowMs);
            json.writeString("links").writeColon().writeArrayStart();
            for (int i = 0; i < links.length; i++) {
                if (i > 0) {
                    json.writeComma();
                }
                json.writeObjectStart()
                        .writeObjectEntry("key", links[i].gatewayKey())
                        .writeComma()
                        .writeObjectEntry("linked", links[i].linked())
                        .writeComma()
                        .writeObjectEntry("workingOrders", links[i].workingOrders())
                        .writeObjectEnd();
            }
            json.writeArrayEnd();
            samples.commit();
            return null;
        } catch (RuntimeException e) {
            samples.abandon();
            return e;
        }
    }

    // Each feed's counters move on to their new values whether or not its part was written, so a failed part loses its
    // minute rather than reporting two minutes as one next time.
    private RuntimeException writeMarketData(final long nowMs, final int part) {
        final int end = Math.min(feeds.length, (part + 1) * FEEDS_PER_SAMPLE);
        for (int i = part * FEEDS_PER_SAMPLE; i < end; i++) {
            nowResyncs[i] = feeds[i].bookResyncs();
            nowRecords[i] = feeds[i].records();
            nowBytes[i] = feeds[i].bytes();
            nowRepeats[i] = feeds[i].resentRepeats();
            nowRefused[i] = feeds[i].refusedRecords();
        }
        RuntimeException failure = null;
        try {
            final JsonEncoder json =
                    beginPart("session.marketdata", nowMs, part, chunks(feeds.length, FEEDS_PER_SAMPLE));
            json.writeString("feeds").writeColon().writeArrayStart();
            for (int i = part * FEEDS_PER_SAMPLE; i < end; i++) {
                if (i > part * FEEDS_PER_SAMPLE) {
                    json.writeComma();
                }
                json.writeObjectStart()
                        .writeObjectEntry("name", feeds[i].name())
                        .writeComma()
                        .writeObjectEntry("reconnecting", feeds[i].isReconnecting());
                if (!first) {
                    json.writeComma()
                            .writeObjectEntry("resyncs", nowResyncs[i] - previousResyncs[i])
                            .writeComma()
                            .writeObjectEntry("records", delta(nowRecords[i], previousRecords[i]))
                            .writeComma()
                            .writeObjectEntry("bytes", delta(nowBytes[i], previousBytes[i]))
                            .writeComma()
                            .writeObjectEntry("resentRepeats", delta(nowRepeats[i], previousRepeats[i]))
                            .writeComma()
                            .writeObjectEntry("refused", delta(nowRefused[i], previousRefused[i]));
                }
                json.writeObjectEnd();
            }
            json.writeArrayEnd();
            samples.commit();
        } catch (RuntimeException e) {
            samples.abandon();
            failure = e;
        }
        for (int i = part * FEEDS_PER_SAMPLE; i < end; i++) {
            previousResyncs[i] = nowResyncs[i];
            previousRecords[i] = nowRecords[i];
            previousBytes[i] = nowBytes[i];
            previousRepeats[i] = nowRepeats[i];
            previousRefused[i] = nowRefused[i];
        }
        return failure;
    }

    private static long delta(final long now, final long before) {
        return now < 0 || before < 0 ? -1 : now - before;
    }

    private RuntimeException writeJournal(final long nowMs) {
        try {
            journal.measureBacklog(nowMs);
            samples.begin("session.journal", nowMs)
                    .writeObjectEntry("pendingSegments", journal.pendingSegments())
                    .writeComma()
                    .writeObjectEntry("oldestPendingMs", journal.oldestPendingMs())
                    .writeComma()
                    .writeObjectEntry("segmentBytes", journal.segmentBytes());
            samples.commit();
            return null;
        } catch (RuntimeException e) {
            samples.abandon();
            return e;
        }
    }
}
