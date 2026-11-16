package group.gnometrading.orchestrator.gateway;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Whether a SIGTERM is the gateway reconciler rolling this gateway, so it should hand over (stop without cancelling,
 * its orders left for the next process on this host) rather than stop as planned. The reconciler writes the gateway's
 * roll item before it stops the task, naming the host it pins the next one to, the release it stops and until when the
 * roll holds. Anything that doesn't match exactly, or can't be read, is a planned stop: cancelling is the safe default,
 * as a stale or unread item must never leave orders resting behind a gateway that isn't coming back.
 */
final class GatewayRoll {

    /** How long the gateway reads its roll item for at SIGTERM before it stops as planned. */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    /**
     * How long a roll leaves orders with no gateway in front of them, at the most the reconciler allows before it rolls
     * back: a venue that cancels resting orders sooner can't be rolled without losing them.
     */
    static final Duration LONGEST_GAP = Duration.ofMinutes(10);

    // The phases in which the reconciler stops the task: the roll's stop, and its rollback's.
    private static final Set<String> STOPPING_PHASES = Set.of("stopping", "rolling_back");

    static final String PHASE = "phase";
    static final String INSTANCE_ID = "instance_id";
    // The release whose process the stop is for: the roll's from-release, or for its rollback's stop its to-release.
    static final String STOPS_VERSION = "stops_version";
    static final String UNTIL_MS = "until_ms";

    /** What the hook does, and why, for its log. */
    record Decision(boolean handOver, String why) {
        static Decision stop(final String why) {
            return new Decision(false, why);
        }
    }

    private GatewayRoll() {}

    /**
     * @param item the roll item, empty without one
     * @param instanceId the host this process runs on, as {@code gateway-start.sh} found it; null off EC2
     * @param version this process's release
     * @param ordersOutliveGatewayFor how long the venue keeps resting orders with no gateway in front of them
     */
    static Decision decide(
            final Map<String, AttributeValue> item,
            final String instanceId,
            final String version,
            final Duration ordersOutliveGatewayFor,
            final long nowMillis) {
        if (item.isEmpty()) {
            return Decision.stop("no roll asked for");
        }
        final String phase = string(item, PHASE);
        if (!STOPPING_PHASES.contains(phase)) {
            return Decision.stop("the roll is " + phase + ", not stopping this gateway");
        }
        if (instanceId == null || !instanceId.equals(string(item, INSTANCE_ID))) {
            return Decision.stop("the roll is for host " + string(item, INSTANCE_ID) + ", not " + instanceId);
        }
        if (!version.equals(string(item, STOPS_VERSION))) {
            return Decision.stop("the roll stops release " + string(item, STOPS_VERSION) + ", not " + version);
        }
        final AttributeValue until = item.get(UNTIL_MS);
        if (until == null || until.n() == null || Long.parseLong(until.n()) <= nowMillis) {
            return Decision.stop("the roll's time is up");
        }
        if (ordersOutliveGatewayFor.compareTo(LONGEST_GAP) < 0) {
            return Decision.stop("the venue cancels resting orders after " + ordersOutliveGatewayFor.toSeconds()
                    + " s without a gateway, sooner than a roll's gap may last");
        }
        return new Decision(true, "rolled off " + version + " on " + instanceId);
    }

    private static String string(final Map<String, AttributeValue> item, final String name) {
        final AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }
}
