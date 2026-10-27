package group.gnometrading.shared;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Secrets for the whole process, each fetched once however many gateways ask for it at the same time: a collector
 * starting dozens of streams on one venue makes one call, not one per stream.
 *
 * <p>An entry lasts {@link #TTL_NANOS}, well under the first quarantine retry, so a stream retried after its key was
 * rotated reads the new one instead of failing again on the old.
 */
public final class SecretCache {

    static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private record Entry(String value, long fetchedAtNanos) {}

    private final Function<String, String> fetch;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public SecretCache(final SecretsManagerClient client) {
        this(
                secretId -> client.getSecretValue(GetSecretValueRequest.builder()
                                .secretId(secretId)
                                .build())
                        .secretString(),
                System::nanoTime);
    }

    SecretCache(final Function<String, String> fetch, final LongSupplier nanoTime) {
        this.fetch = fetch;
        this.nanoTime = nanoTime;
    }

    /** The secret's string value. Callers asking for one while it is being fetched wait for that fetch. */
    public String get(final String secretId) {
        return entries.compute(secretId, (id, entry) -> {
                    final long now = nanoTime.getAsLong();
                    return entry != null && now - entry.fetchedAtNanos() < TTL_NANOS
                            ? entry
                            : new Entry(fetch.apply(id), now);
                })
                .value();
    }
}
