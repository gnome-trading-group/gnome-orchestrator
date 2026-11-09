package group.gnometrading.orchestrator.gateway.core;

/**
 * The kill rules in force: published by the gateway's kill poller, read by ingress before each new order or amend.
 * Until the first read the kills are unknown, not none, so new orders wait for it: a gateway starting while the
 * registry is down takes cancels but no new orders.
 */
public final class Kills {

    private volatile KillRules rules = KillRules.NONE;
    private volatile boolean read;

    public KillRules rules() {
        return this.rules;
    }

    /** Kills read once and found empty: for a gateway built where no registry is to be read. */
    public static Kills none() {
        final Kills kills = new Kills();
        kills.publish(KillRules.NONE);
        return kills;
    }

    /** Whether the kills have been read at least once. */
    public boolean read() {
        return this.read;
    }

    public void publish(final KillRules rules) {
        this.rules = rules;
        this.read = true;
    }
}
