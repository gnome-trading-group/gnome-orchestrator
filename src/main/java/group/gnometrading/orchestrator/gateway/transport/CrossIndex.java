package group.gnometrading.orchestrator.gateway.transport;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.orchestrator.gateway.core.CrossKey;
import group.gnometrading.orchestrator.gateway.core.CrossKeys;
import group.gnometrading.schemas.Side;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Every working order of every session on every account the gateway holds, by venue market, so an order that would
 * trade against another session's is refused before it is sent. Venue self-trade prevention only works within one
 * account, and two of the firm's accounts trading each other is still a wash trade, so this is the only check that
 * spans them. Different strategies may trade the same listing, so crossing between sessions is routine.
 *
 * <p>An order counts from the moment ingress admits it, not from the venue's acknowledgement: two crossing orders sent
 * within one round trip would otherwise both pass. Prices are held in the market's first-side terms, so an order on a
 * listing its venue calls the market's complement is checked as the opposite side at {@code 1 - p}.
 * An amend counts at both its old and new prices until it is answered, since the venue may still execute either; the
 * OMS never sends a second amend before the first is answered, so a new one replaces an amend that was refused. A
 * refusal (CANCEL_REJECT) can't say whether it answers the amend or a cancel sent beside it, so while a cancel is out
 * the refusal is taken as the cancel's, keeping both prices: the safe reading, settled when the order ends.
 *
 * <p>Ingress owns the index. Egress, which sees every report, tells it when an order ends or an amend is accepted
 * through a ring; one the ring has no room for waits on egress's side and goes on its next pass, since a lost end would
 * leave an order in the index that blocks other sessions for good. A session's own orders never block it: its OMS and
 * the venue's account-wide prevention cover those. Allocates nothing once built.
 */
public final class CrossIndex {

    /** A whole contract, in price units: the complement of price p is {@code FULL - p}. */
    public static final long FULL = 1_000_000_000L;

    /** What the index does with an order that would cross. */
    public enum Mode {
        /** Refused {@code SELF_TRADE}. */
        ENFORCE,
        /** Sent, and counted: to see what enforcing would refuse. */
        SHADOW,
        /**
         * Not checked, nothing held: a paper gateway, whose sessions each trade against books of their own, so their
         * orders never meet.
         */
        OFF
    }

    /** The order may go. */
    public static final int CLEAR = 0;
    /** The order crosses another session's. */
    public static final int CROSSES = 1;
    /** The index has no room to hold another order. */
    public static final int FULL_INDEX = 2;

    private static final long NO_PRICE = -1;
    private static final int COUNTER_BITS = 40;
    private static final long COUNTER_MASK = (1L << COUNTER_BITS) - 1;

    /** One end or accepted amend, egress to ingress. */
    public static final class Notice {
        static final int ENDED = 0;
        static final int AMEND_ACCEPTED = 1;
        static final int REFUSED = 2;

        long slot;
        long counter;
        int kind;
    }

    private static final class Entry {
        long key;
        long slot;
        long market;
        boolean buy;
        boolean complement;
        long price;
        long amendPrice;
        // Cancels of it sent and not yet answered.
        int cancelsOut;
        Entry prev;
        Entry next;
    }

    private final CrossKeys crossKeys;
    private final Mode mode;
    private final Long2ObjectHashMap<Entry> orders;
    private final Long2ObjectHashMap<Entry> markets;
    private final Entry[] free;
    private int freeCount;
    private final ManyToOneRingBuffer<Notice> notices;
    private final MessageConsumer<Notice> noticeHandler = this::onNotice;

    // Egress's side: notices the ring had no room for, sent first on its next pass.
    private final long[] pendingSlots;
    private final long[] pendingCounters;
    private final int[] pendingKinds;
    private int pending;

    // Written by one thread each; volatile so the gateway's minute report reads them.
    private volatile long refused;
    private volatile long shadowed;
    private volatile long noRoom;
    private volatile long lost;

    /**
     * @param capacity how many orders it holds at once, across every account
     * @param noticeCapacity the egress-to-ingress ring's size, and as many again wait on egress's side when it's full
     */
    public CrossIndex(final CrossKeys crossKeys, final Mode mode, final int capacity, final int noticeCapacity) {
        this.crossKeys = crossKeys;
        this.mode = mode;
        this.orders = new Long2ObjectHashMap<>(capacity * 2, 0.6f);
        this.markets = new Long2ObjectHashMap<>(capacity * 2, 0.6f);
        this.free = new Entry[capacity];
        for (int i = 0; i < capacity; i++) {
            this.free[i] = new Entry();
        }
        this.freeCount = capacity;
        this.notices = new ManyToOneRingBuffer<>(Notice[]::new, Notice::new, noticeCapacity);
        this.pendingSlots = new long[noticeCapacity];
        this.pendingCounters = new long[noticeCapacity];
        this.pendingKinds = new int[noticeCapacity];
    }

    // ---- ingress -----------------------------------------------------------------------------------------------

    /** Applies what egress has told of since the last call; before admitting anything, so no end is checked late. */
    public void drain() {
        this.notices.read(this.noticeHandler, Integer.MAX_VALUE);
    }

    /**
     * Whether a new order from {@code slot} may go: {@link #CLEAR}, {@link #CROSSES} another session's, or
     * {@link #FULL_INDEX}. In shadow mode a crossing order is counted and reported clear. Changes nothing: the order is
     * held only once {@link #add} says it went.
     */
    public int checkNew(final long slot, final long listingKey, final Side side, final long price) {
        if (this.mode == Mode.OFF) {
            return CLEAR;
        }
        final CrossKey key = this.crossKeys.get(listingKey);
        if (key == null || price <= 0) {
            return CLEAR;
        }
        if (this.freeCount == 0) {
            this.noRoom = this.noRoom + 1;
            // Shadow mode only watches: an order it can't hold goes unchecked rather than refused.
            return this.mode == Mode.SHADOW ? CLEAR : FULL_INDEX;
        }
        return verdict(crosses(slot, key.market(), buys(side, key), firstSide(price, key)));
    }

    /** Holds a new order {@link #checkNew} cleared and ingress has sent on to its lane. */
    public void add(final long slot, final long counter, final long listingKey, final Side side, final long price) {
        if (this.mode == Mode.OFF) {
            return;
        }
        final CrossKey key = this.crossKeys.get(listingKey);
        if (key == null || side == null || price <= 0 || this.freeCount == 0) {
            return;
        }
        final long orderKey = key(slot, counter);
        if (this.orders.containsKey(orderKey)) {
            return;
        }
        final Entry entry = this.free[--this.freeCount];
        entry.key = orderKey;
        entry.slot = slot;
        entry.market = key.market();
        entry.buy = buys(side, key);
        entry.complement = key.complement();
        entry.price = firstSide(price, key);
        entry.amendPrice = NO_PRICE;
        entry.cancelsOut = 0;
        this.orders.put(orderKey, entry);
        link(entry);
    }

    /**
     * Whether an amend of order {@code counter} to {@code price} may go. An order the index doesn't hold (it had no
     * room, or the listing has no key) can't be checked, and goes.
     */
    public int checkAmend(final long slot, final long counter, final long price) {
        if (this.mode == Mode.OFF) {
            return CLEAR;
        }
        final Entry entry = this.orders.get(key(slot, counter));
        if (entry == null || price <= 0) {
            return CLEAR;
        }
        return verdict(crosses(slot, entry.market, entry.buy, firstSide(price, entry.complement)));
    }

    /** An amend {@link #checkAmend} cleared has gone: the order counts at both prices until it is answered. */
    public void amend(final long slot, final long counter, final long price) {
        if (this.mode == Mode.OFF) {
            return;
        }
        final Entry entry = this.orders.get(key(slot, counter));
        if (entry != null && price > 0) {
            entry.amendPrice = firstSide(price, entry.complement);
        }
    }

    /** A cancel of order {@code counter} has gone to its lane. */
    public void cancelSent(final long slot, final long counter) {
        final Entry entry = this.orders.get(key(slot, counter));
        if (entry != null) {
            entry.cancelsOut++;
        }
    }

    /** Takes back an order a restart recovered, before any thread starts. */
    public void restore(final long slot, final long counter, final long listingKey, final Side side, final long price) {
        if (this.mode == Mode.OFF) {
            return;
        }
        add(slot, counter, listingKey, side, price);
    }

    /** Orders it holds; ingress's, approximate from any other thread. */
    public int size() {
        return this.orders.size();
    }

    /** Orders refused for crossing another session's. */
    public long refused() {
        return this.refused;
    }

    /** Orders that would have been refused, sent in shadow mode. */
    public long shadowed() {
        return this.shadowed;
    }

    /** New orders the index had no room for: refused, or in shadow mode sent unchecked. */
    public long noRoom() {
        return this.noRoom;
    }

    // ---- egress ------------------------------------------------------------------------------------------------

    /** Order {@code counter} of {@code slot} has ended: filled, cancelled, rejected or expired. */
    public void ended(final long slot, final long counter) {
        tell(slot, counter, Notice.ENDED);
    }

    /** Order {@code counter} of {@code slot} took its amend: it counts at the new price only. */
    public void amendAccepted(final long slot, final long counter) {
        tell(slot, counter, Notice.AMEND_ACCEPTED);
    }

    /** A cancel or amend of order {@code counter} of {@code slot} was refused. */
    public void requestRefused(final long slot, final long counter) {
        tell(slot, counter, Notice.REFUSED);
    }

    /** Sends what waited for room on the ring; egress calls it once a pass, before its reports. */
    public void flush() {
        int sent = 0;
        while (sent < this.pending
                && offer(this.pendingSlots[sent], this.pendingCounters[sent], this.pendingKinds[sent])) {
            sent++;
        }
        if (sent > 0) {
            final int left = this.pending - sent;
            System.arraycopy(this.pendingSlots, sent, this.pendingSlots, 0, left);
            System.arraycopy(this.pendingCounters, sent, this.pendingCounters, 0, left);
            System.arraycopy(this.pendingKinds, sent, this.pendingKinds, 0, left);
            this.pending = left;
        }
    }

    /** Ends and amends lost because both the ring and egress's own queue were full: their orders stay in the index. */
    public long lost() {
        return this.lost;
    }

    private void tell(final long slot, final long counter, final int kind) {
        if (this.pending == 0 && offer(slot, counter, kind)) {
            return;
        }
        if (this.pending == this.pendingSlots.length) {
            this.lost = this.lost + 1;
            return;
        }
        this.pendingSlots[this.pending] = slot;
        this.pendingCounters[this.pending] = counter;
        this.pendingKinds[this.pending] = kind;
        this.pending++;
    }

    private boolean offer(final long slot, final long counter, final int kind) {
        final int index = this.notices.tryClaim();
        if (index < 0) {
            return false;
        }
        final Notice notice = this.notices.indexAt(index);
        notice.slot = slot;
        notice.counter = counter;
        notice.kind = kind;
        this.notices.commit(index);
        return true;
    }

    // ---- ingress internals -------------------------------------------------------------------------------------

    private void onNotice(final Notice notice) {
        final long orderKey = key(notice.slot, notice.counter);
        final Entry entry = this.orders.get(orderKey);
        if (entry == null) {
            return;
        }
        if (notice.kind == Notice.AMEND_ACCEPTED) {
            if (entry.amendPrice != NO_PRICE) {
                entry.price = entry.amendPrice;
                entry.amendPrice = NO_PRICE;
            }
            return;
        }
        if (notice.kind == Notice.REFUSED) {
            if (entry.cancelsOut > 0) {
                entry.cancelsOut--;
            } else {
                entry.amendPrice = NO_PRICE;
            }
            return;
        }
        this.orders.remove(orderKey);
        unlink(entry);
        entry.prev = null;
        entry.next = null;
        this.free[this.freeCount++] = entry;
    }

    private int verdict(final boolean crosses) {
        if (!crosses) {
            return CLEAR;
        }
        if (this.mode == Mode.SHADOW) {
            this.shadowed = this.shadowed + 1;
            return CLEAR;
        }
        this.refused = this.refused + 1;
        return CROSSES;
    }

    /** Whether a buy (or sell) at {@code price}, in first-side terms, meets another session's order on the market. */
    private boolean crosses(final long slot, final long market, final boolean buy, final long price) {
        for (Entry other = this.markets.get(market); other != null; other = other.next) {
            if (other.slot == slot || other.buy == buy) {
                continue;
            }
            if (meets(buy, price, other.price)
                    || (other.amendPrice != NO_PRICE && meets(buy, price, other.amendPrice))) {
                return true;
            }
        }
        return false;
    }

    private static boolean meets(final boolean buy, final long price, final long resting) {
        return buy ? price >= resting : price <= resting;
    }

    private void link(final Entry entry) {
        final Entry head = this.markets.get(entry.market);
        entry.prev = null;
        entry.next = head;
        if (head != null) {
            head.prev = entry;
        }
        this.markets.put(entry.market, entry);
    }

    private void unlink(final Entry entry) {
        if (entry.prev != null) {
            entry.prev.next = entry.next;
        } else if (entry.next != null) {
            this.markets.put(entry.market, entry.next);
        } else {
            this.markets.remove(entry.market);
        }
        if (entry.next != null) {
            entry.next.prev = entry.prev;
        }
    }

    private static boolean buys(final Side side, final CrossKey key) {
        return (side == Side.Bid) != key.complement();
    }

    private static long firstSide(final long price, final CrossKey key) {
        return firstSide(price, key.complement());
    }

    private static long firstSide(final long price, final boolean complement) {
        return complement ? FULL - price : price;
    }

    /** A session's slot and order counter as one key; counters stay far below 2^40. */
    private static long key(final long slot, final long counter) {
        return (slot << COUNTER_BITS) | (counter & COUNTER_MASK);
    }
}
