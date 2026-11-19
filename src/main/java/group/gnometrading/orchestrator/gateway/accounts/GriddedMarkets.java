package group.gnometrading.orchestrator.gateway.accounts;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.sm.PriceGrid;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * An account's loaded markets whose order entry prices on their listing's grid, kept on the grid the registry has now:
 * a venue can change a listing's tick while the gateway runs. Markets are added on the control thread as they load and
 * moved onto new grids from one side thread.
 */
final class GriddedMarkets {

    private final Logger logger;
    private final String name;
    private final Map<Integer, Consumer<PriceGrid>> markets = new ConcurrentHashMap<>();
    // The side thread's alone.
    private final Map<Integer, PriceGrid> applied = new HashMap<>();

    GriddedMarkets(final Logger logger, final String name) {
        this.logger = logger;
        this.name = name;
    }

    void add(final int listingId, final Consumer<PriceGrid> market) {
        this.markets.put(listingId, market);
    }

    void apply(final IntFunction<PriceGrid> grids) {
        for (final Map.Entry<Integer, Consumer<PriceGrid>> market : this.markets.entrySet()) {
            final int listingId = market.getKey();
            final PriceGrid grid = grids.apply(listingId);
            if (grid == null || grid.equals(this.applied.get(listingId))) {
                continue;
            }
            try {
                market.getValue().accept(grid);
                this.applied.put(listingId, grid);
                this.logger.logf(LogMessage.DEBUG, "%s: listing %d prices on %s", this.name, listingId, grid);
            } catch (RuntimeException e) {
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "%s: listing %d keeps its grid, refusing %s: %s",
                        this.name,
                        listingId,
                        grid,
                        e.getMessage());
            }
        }
    }
}
