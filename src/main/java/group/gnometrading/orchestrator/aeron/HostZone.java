package group.gnometrading.orchestrator.aeron;

import java.util.concurrent.TimeUnit;

/**
 * The availability zone this host runs in, by name (as this account calls it, {@code us-east-2b}) and by ID (the
 * physical zone, the same in every account, {@code use2-az2}): what a process advertises in {@code service-endpoints}
 * so its reconciler can tell whether it runs in its exchange's home zone, and sessions can follow it.
 *
 * @param name the zone's name in this account
 * @param id the zone's ID
 */
public record HostZone(String name, String id) {

    // A failed read is tried again at most this often: a host's zone never changes, so one good read is kept for good,
    // but a metadata hiccup at boot mustn't leave a process unable to say where it runs.
    private static final long RETRY_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static volatile HostZone current;
    private static volatile long lastTryNanos;
    private static volatile boolean tried;

    /**
     * This host's zone from instance metadata, read once when it succeeds; null off EC2 or while metadata can't be read
     * (tried again a minute on). Never throws.
     */
    public static HostZone current() {
        final HostZone known = current;
        if (known != null) {
            return known;
        }
        synchronized (HostZone.class) {
            final long now = System.nanoTime();
            if (current == null && (!tried || now - lastTryNanos >= RETRY_NANOS)) {
                tried = true;
                lastTryNanos = now;
                current = of(new InstanceMetadata());
            }
            return current;
        }
    }

    static HostZone of(final InstanceMetadata metadata) {
        try {
            final String[] values = metadata.read("placement/availability-zone", "placement/availability-zone-id");
            return values[0].isEmpty() || values[1].isEmpty() ? null : new HostZone(values[0], values[1]);
        } catch (Exception e) {
            return null;
        }
    }
}
