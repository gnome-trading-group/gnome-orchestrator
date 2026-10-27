package group.gnometrading.feeds;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.SecurityMaster;
import group.gnometrading.md.SourceAccount;
import java.util.Locale;
import java.util.Map;

/**
 * Which Secrets Manager secret a source's market-data connections sign in with. A shard collects with one of the
 * source's dedicated market-data accounts, the label it is bound to; a session still uses the per-exchange key until
 * it reads its data from shards (collection-sharding decision 8).
 */
@FunctionalInterface
public interface MarketDataCredentials {

    /** A shard's account per source when it isn't told otherwise. */
    String DEFAULT_LABEL = "md-1";

    /** Sessions: one key per exchange, {@code gnome/market-data/<exchange>}. */
    MarketDataCredentials SESSION = sourceCode -> "gnome/market-data/" + secretSuffix(sourceCode);

    /** The secret for {@code sourceCode}'s connections; throws if it has none it may use. */
    String secretFor(String sourceCode);

    /**
     * Shards: the account each source is bound to, by label ({@code SOURCE_ACCOUNTS}, {@link #DEFAULT_LABEL} for one
     * not named), looked up as each connection opens so an account retired since is refused rather than used.
     */
    static MarketDataCredentials forShard(final SecurityMaster securityMaster, final Map<String, String> labels) {
        return sourceCode -> {
            final String label = labels.getOrDefault(sourceCode, DEFAULT_LABEL);
            final SourceAccount account = securityMaster.getSourceAccount(sourceCode, label);
            if (account == null) {
                throw new IllegalStateException("No market-data account " + label + " for " + sourceCode
                        + ": add it on the Credentials page, or bind another with SOURCE_ACCOUNTS");
            }
            if (!account.active()) {
                throw new IllegalStateException(
                        "Market-data account " + label + " for " + sourceCode + " is " + account.status());
            }
            return account.secretName();
        };
    }

    /** {@code SOURCE_ACCOUNTS}: a JSON object of source code to label, e.g. {@code {"KALSHI":"md-2"}}. */
    static Map<String, String> parseLabels(final ObjectMapper mapper, final String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(mapper.readValue(json, new TypeReference<Map<String, String>>() {}));
        } catch (Exception e) {
            throw new IllegalArgumentException("SOURCE_ACCOUNTS must be a JSON object of source to label: " + json, e);
        }
    }

    private static String secretSuffix(final String sourceCode) {
        return sourceCode.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
