package group.gnometrading.orchestrator.shard.feeds;

import group.gnometrading.SecurityMaster;
import group.gnometrading.md.SourceAccount;
import java.util.function.UnaryOperator;

/**
 * Which Secrets Manager secret a source's market-data connections sign in with: a shard collects with one of the
 * source's dedicated market-data accounts, the label it is bound to.
 */
@FunctionalInterface
public interface MarketDataCredentials {

    /** A shard's account per source when it isn't told otherwise. */
    String DEFAULT_LABEL = "md-1";

    /** The secret for {@code sourceCode}'s connections; throws if it has none it may use. */
    String secretFor(String sourceCode);

    /**
     * Shards: the account each source is bound to, by label ({@code labelOf}, from the shard's assignment), looked up as
     * each connection opens so an account retired since is refused rather than used.
     */
    static MarketDataCredentials forShard(final SecurityMaster securityMaster, final UnaryOperator<String> labelOf) {
        return sourceCode -> {
            final String label = labelOf.apply(sourceCode);
            final SourceAccount account = securityMaster.getSourceAccount(sourceCode, label);
            if (account == null) {
                throw new IllegalStateException("No market-data account " + label + " for " + sourceCode
                        + ": add it on the Credentials page, or bind the shard to another");
            }
            if (!account.active()) {
                throw new IllegalStateException(
                        "Market-data account " + label + " for " + sourceCode + " is " + account.status());
            }
            return account.secretName();
        };
    }
}
