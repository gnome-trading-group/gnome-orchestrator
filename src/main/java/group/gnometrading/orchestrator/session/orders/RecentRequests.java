package group.gnometrading.orchestrator.session.orders;

/**
 * What the session sent of each recent order, kept past the order's end: a gateway's timing for a request can come
 * after the report that ended its order (an IOC's fill beats the HTTP answer that placed it), and the timing is only
 * worth something joined to what the request was sent for. Direct-mapped by client order counter over the last
 * {@link #CAPACITY} orders, which a timing arrives well within; allocates nothing.
 */
final class RecentRequests {

    static final int CAPACITY = 4096;
    private static final int MASK = CAPACITY - 1;
    private static final int ACTIONS = GatewayClient.LATENCY_ACTIONS.length;

    private final long[] counters = new long[CAPACITY];
    private final int[] exchangeIds = new int[CAPACITY];
    // By slot and action: the trigger's kind and platform receipt (its origin), and when the session handed it off.
    private final int[] triggerKinds = new int[CAPACITY * ACTIONS];
    private final long[] originAt = new long[CAPACITY * ACTIONS];
    private final long[] handedOffAt = new long[CAPACITY * ACTIONS];
    // For the cancel race: the venue's time of the order's latest fill, and when its cancel was written to the venue.
    private final long[] fillAt = new long[CAPACITY];
    private final long[] cancelWiredAt = new long[CAPACITY];

    RecentRequests() {
        for (int i = 0; i < CAPACITY; i++) {
            this.counters[i] = -1;
        }
    }

    /** The order's request of {@code action} handed off at {@code at}, for a trigger the platform got at {@code originNanos}. */
    void handedOff(
            final long counter,
            final int action,
            final int exchangeId,
            final int triggerKind,
            final long originNanos,
            final long at) {
        final int slot = (int) (counter & MASK);
        if (this.counters[slot] != counter) {
            clear(slot);
            this.counters[slot] = counter;
            this.exchangeIds[slot] = exchangeId;
        }
        final int index = slot * ACTIONS + action;
        this.triggerKinds[index] = triggerKind;
        this.originAt[index] = originNanos;
        this.handedOffAt[index] = at;
    }

    /** The order's slot, or -1 if it is no longer held. */
    int find(final long counter) {
        final int slot = (int) (counter & MASK);
        return this.counters[slot] == counter ? slot : -1;
    }

    int exchangeId(final int slot) {
        return this.exchangeIds[slot];
    }

    int triggerKind(final int slot, final int action) {
        return this.triggerKinds[slot * ACTIONS + action];
    }

    long originAt(final int slot, final int action) {
        return this.originAt[slot * ACTIONS + action];
    }

    long handedOffAt(final int slot, final int action) {
        return this.handedOffAt[slot * ACTIONS + action];
    }

    long fillAt(final int slot) {
        return this.fillAt[slot];
    }

    void fillAt(final int slot, final long venueNanos) {
        this.fillAt[slot] = venueNanos;
    }

    long cancelWiredAt(final int slot) {
        return this.cancelWiredAt[slot];
    }

    void cancelWiredAt(final int slot, final long nanos) {
        this.cancelWiredAt[slot] = nanos;
    }

    private void clear(final int slot) {
        this.exchangeIds[slot] = 0;
        this.fillAt[slot] = 0;
        this.cancelWiredAt[slot] = 0;
        for (int action = 0; action < ACTIONS; action++) {
            final int index = slot * ACTIONS + action;
            this.triggerKinds[index] = 0;
            this.originAt[index] = 0;
            this.handedOffAt[index] = 0;
        }
    }
}
