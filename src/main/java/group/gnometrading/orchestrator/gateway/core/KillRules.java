package group.gnometrading.orchestrator.gateway.core;

/**
 * The kills in force, as the gateway enforces them behind the OMS: each rule covers the orders that match every target
 * it names (a venue account, an exchange, a listing, a strategy, a session), and a rule naming none covers everything.
 * A new order or amend a rule covers is refused; cancels always go. Built off the hot path from the registry's kill
 * switches and published whole; ingress reads the current rules without locking or allocating.
 */
public final class KillRules {

    /** No kills. */
    public static final KillRules NONE = new KillRules(new int[0], new int[0], new long[0], new int[0], new long[0]);

    // One entry per rule; 0 is "any", as no real id or listing key is 0.
    private final int[] accountIds;
    private final int[] exchangeIds;
    private final long[] listingKeys;
    private final int[] strategyIds;
    private final long[] slots;

    public KillRules(
            final int[] accountIds,
            final int[] exchangeIds,
            final long[] listingKeys,
            final int[] strategyIds,
            final long[] slots) {
        this.accountIds = accountIds;
        this.exchangeIds = exchangeIds;
        this.listingKeys = listingKeys;
        this.strategyIds = strategyIds;
        this.slots = slots;
    }

    /** Whether any rule covers an order of {@code slot}'s, from {@code strategyId}, on the listing, on the account. */
    public boolean covers(final int accountId, final long listingKey, final int strategyId, final long slot) {
        for (int i = 0; i < this.slots.length; i++) {
            if (matches(i, accountId, strategyId, slot)
                    && (this.listingKeys[i] == 0 || this.listingKeys[i] == listingKey)
                    && (this.exchangeIds[i] == 0 || this.exchangeIds[i] == exchangeOf(listingKey))) {
                return true;
            }
        }
        return false;
    }

    /** How many rules there are. */
    public int size() {
        return this.slots.length;
    }

    /** Whether the rule at {@code rule} covers some of what a session of {@code strategyId} in {@code slot} trades on the account. */
    public boolean matches(final int rule, final int accountId, final int strategyId, final long slot) {
        return (this.accountIds[rule] == 0 || this.accountIds[rule] == accountId)
                && (this.strategyIds[rule] == 0 || this.strategyIds[rule] == strategyId)
                && (this.slots[rule] == 0 || this.slots[rule] == slot);
    }

    /** The exchange the rule at {@code rule} names, or 0. */
    public int exchangeId(final int rule) {
        return this.exchangeIds[rule];
    }

    /** The listing the rule at {@code rule} names, or 0. */
    public long listingKey(final int rule) {
        return this.listingKeys[rule];
    }

    /** The rule at {@code rule}, for a log line. */
    public String describe(final int rule) {
        final StringBuilder text = new StringBuilder();
        append(text, "account", this.accountIds[rule]);
        append(text, "exchange", this.exchangeIds[rule]);
        if (this.listingKeys[rule] != 0) {
            text.append(text.isEmpty() ? "" : ", ")
                    .append("listing ")
                    .append(exchangeOf(this.listingKeys[rule]))
                    .append('/')
                    .append(this.listingKeys[rule] & 0xFFFF_FFFFL);
        }
        append(text, "strategy", this.strategyIds[rule]);
        append(text, "session slot", this.slots[rule]);
        return text.isEmpty() ? "everything" : text.toString();
    }

    private static void append(final StringBuilder text, final String what, final long id) {
        if (id != 0) {
            text.append(text.isEmpty() ? "" : ", ").append(what).append(' ').append(id);
        }
    }

    /** The exchange in a listing key's high half ({@link group.gnometrading.gateways.account.reader.PublishedMap#listingKey}). */
    private static int exchangeOf(final long listingKey) {
        return (int) (listingKey >>> 32);
    }
}
