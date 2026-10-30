package group.gnometrading.trading;

import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.GatewayHealth;
import java.util.List;
import org.agrona.ErrorHandler;

/**
 * Market data from shards, as the strategy reads it, measured against the session's own venue connections for the same
 * listings, which nothing trades on: for timing the shard hop. The direct feeds start once the shards are live, and
 * their failures are logged, never fatal: they are only a reference.
 */
final class ComparedMarketData implements TradingOrchestrator.MarketData {

    private final ShardMarketData shards;
    private final SessionFeeds direct;
    private final FeedComparator comparator;
    private GnomeAgentRunner comparatorRunner;

    ComparedMarketData(final ShardMarketData shards, final SessionFeeds direct, final FeedComparator comparator) {
        this.shards = shards;
        this.direct = direct;
        this.comparator = comparator;
    }

    @Override
    public void start(final ErrorHandler errorHandler) throws Exception {
        shards.start(errorHandler);
        comparatorRunner = new GnomeAgentRunner(comparator, errorHandler);
        GnomeAgentRunner.startOnThread(comparatorRunner);
        direct.start(errorHandler);
    }

    @Override
    public List<GatewayHealth> health() {
        return shards.health();
    }

    /** The comparator last, so its totals include everything both sides delivered. */
    @Override
    public void close() throws Exception {
        try {
            direct.close();
        } finally {
            try {
                shards.close();
            } finally {
                if (comparatorRunner != null) {
                    comparatorRunner.close();
                }
            }
        }
    }
}
