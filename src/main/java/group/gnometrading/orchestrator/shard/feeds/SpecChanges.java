package group.gnometrading.orchestrator.shard.feeds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import group.gnometrading.RegistryConnection;
import group.gnometrading.gateways.feed.SpecChangeNotices;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.strings.GnomeString;
import group.gnometrading.strings.ViewString;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Listings a shard's feeds saw announce new trading terms, passed on to the registry, which reads the venue again and
 * dates any change when the shard saw it. Each exchange's feeds share one queue; {@link #post} runs on one side thread.
 * A post that fails is dropped: the registry's regular pass reads every listing anyway, only later and dated then.
 */
public final class SpecChanges {

    /** For feeds whose notices go nowhere (a local run, a test). */
    public static final SpecChanges NONE = new SpecChanges(null, null, 0, null);

    private static final GnomeString PATH = new ViewString("/api/listing-specs/refresh");
    // The registry takes at most this many in one request.
    private static final int PER_POST = 50;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    // The registry refuses a whole request over one notice older than a day, so older ones (a replay, a bad clock)
    // stay here; a little short of the day, for the time the post takes.
    private static final long MAX_AGE_NANOS = 23 * 3_600 * NANOS_PER_SECOND;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Pending(String exchangeCode, String exchangeSecurityId, long newTick, long venueNanos) {}

    private final Logger logger;
    private final RegistryConnection registry;
    private final int capacity;
    private final EpochNanoClock clock;
    private final Map<String, SpecChangeNotices> byExchange = new ConcurrentHashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private long droppedReported;

    /**
     * @param registry a connection for the posting thread alone
     * @param capacity each exchange's queue; a power of two
     */
    public SpecChanges(
            final Logger logger, final RegistryConnection registry, final int capacity, final EpochNanoClock clock) {
        this.logger = logger;
        this.registry = registry;
        this.capacity = capacity;
        this.clock = clock;
    }

    /** The queue an exchange's feed readers put notices on, or null when notices go nowhere. */
    public SpecChangeNotices notices(final String exchangeCode) {
        if (this.registry == null) {
            return null;
        }
        return this.byExchange.computeIfAbsent(exchangeCode, code -> new SpecChangeNotices(this.capacity));
    }

    /** Posts every queued notice. */
    public void post() {
        if (this.registry == null) {
            return;
        }
        long dropped = 0;
        for (final Map.Entry<String, SpecChangeNotices> exchange : this.byExchange.entrySet()) {
            final String code = exchange.getKey();
            exchange.getValue()
                    .drain((id, newTick, venueNanos) -> this.pending.add(new Pending(code, id, newTick, venueNanos)));
            dropped += exchange.getValue().dropped();
        }
        if (dropped > this.droppedReported) {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Spec change notices: %d dropped (%d in all); the registry's regular pass will catch them",
                    dropped - this.droppedReported,
                    dropped);
            this.droppedReported = dropped;
        }
        final long now = this.clock.nanoTime();
        final int stale = (int) this.pending.stream()
                .filter(notice -> notice.venueNanos() < now - MAX_AGE_NANOS)
                .count();
        if (stale > 0) {
            this.pending.removeIf(notice -> notice.venueNanos() < now - MAX_AGE_NANOS);
            this.logger.logf(LogMessage.UNKNOWN_ERROR, "Spec change notices: %d over a day old not sent", stale);
        }
        for (int from = 0; from < this.pending.size(); from += PER_POST) {
            send(this.pending.subList(from, Math.min(this.pending.size(), from + PER_POST)), now);
        }
        this.pending.clear();
    }

    private void send(final List<Pending> batch, final long now) {
        final ArrayNode body = MAPPER.createArrayNode();
        for (final Pending notice : batch) {
            final ObjectNode item = body.addObject();
            item.put("exchangeCode", notice.exchangeCode());
            item.put("exchangeSecurityId", notice.exchangeSecurityId());
            // A venue clock ahead of ours would have the registry refuse the request, every other notice with it.
            final long observed = Math.min(notice.venueNanos(), now);
            item.put(
                    "observedAt",
                    Instant.ofEpochSecond(observed / NANOS_PER_SECOND, observed % NANOS_PER_SECOND)
                            .toString());
            item.put("newTick", notice.newTick());
        }
        final byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        final int status = this.registry.tryPost(PATH, bytes, bytes.length);
        if (status / 100 == 2) {
            this.logger.logf(LogMessage.DEBUG, "Spec change notices: %d sent for a refresh", batch.size());
        } else {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Spec change notices: %d not taken by the registry (HTTP %d); its regular pass will catch them",
                    batch.size(),
                    status);
        }
    }
}
