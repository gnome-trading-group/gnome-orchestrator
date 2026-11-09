package group.gnometrading.orchestrator.gateway.core;

import group.gnometrading.gateways.orders.CrossMarket;

/**
 * A listing's {@link CrossMarket} in a form ingress compares without allocating: the market as a hash of its exchange
 * and the venue's name for it, and whether the listing prices the market's complement.
 *
 * @param market the venue market, as a hash of its exchange and the venue's own name for it
 * @param complement whether the listing prices the market's complement
 */
public record CrossKey(long market, boolean complement) {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /** The key of a listing on {@code exchangeId} whose venue says it trades {@code cross}. */
    public static CrossKey of(final int exchangeId, final CrossMarket cross) {
        final String market = cross.market();
        long hash = FNV_OFFSET;
        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
            hash = (hash ^ ((exchangeId >>> shift) & 0xFF)) * FNV_PRIME;
        }
        for (int i = 0; i < market.length(); i++) {
            hash = (hash ^ market.charAt(i)) * FNV_PRIME;
        }
        return new CrossKey(hash, cross.complement());
    }
}
