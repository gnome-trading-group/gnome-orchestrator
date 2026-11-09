package group.gnometrading.orchestrator.gateway.core;

import group.gnometrading.gateways.account.reader.PublishedMap;

/**
 * Every tradeable listing's {@link CrossKey}, by listing key, across all of the gateway's accounts: put by the control
 * thread as listings load, read by ingress on its hot path without allocating.
 */
public final class CrossKeys {

    private final PublishedMap<CrossKey> keys = new PublishedMap<>();

    /** Off the hot path. */
    public void put(final long listingKey, final CrossKey key) {
        if (key != null && !key.equals(this.keys.get(listingKey))) {
            this.keys.put(listingKey, key);
        }
    }

    /** The listing's key, or null if it was never made tradeable. */
    public CrossKey get(final long listingKey) {
        return this.keys.get(listingKey);
    }
}
