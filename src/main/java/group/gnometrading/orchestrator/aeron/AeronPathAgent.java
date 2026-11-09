package group.gnometrading.orchestrator.aeron;

import group.gnometrading.concurrent.GnomeAgent;

/**
 * An agent on an Aeron path, carrying market data from a shard to a session or orders between a session and its
 * gateway: idles as {@link AeronPathIdle}.
 */
public interface AeronPathAgent extends GnomeAgent {}
