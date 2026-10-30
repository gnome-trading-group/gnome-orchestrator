package group.gnometrading.aeron;

import group.gnometrading.concurrent.GnomeAgent;

/** An agent on the market-data path from a shard's reader to a session's strategy: idles as {@link MarketDataIdle}. */
public interface MarketDataPathAgent extends GnomeAgent {}
