package group.gnometrading.collectors;

import group.gnometrading.feeds.MarketDataCredentials;
import java.util.Map;

/**
 * The market-data account each source is bound to on this shard, by label, from its assignment. The reconciler binds a
 * shard once and moves streams to another shard to change account, so this is set before the first stream starts and
 * then stays; it is read as each signed connection opens.
 */
final class ShardAccounts {

    private volatile Map<String, String> labels = Map.of();

    String label(final String source) {
        return labels.getOrDefault(source, MarketDataCredentials.DEFAULT_LABEL);
    }

    void update(final Map<String, String> assigned) {
        labels = assigned;
    }
}
