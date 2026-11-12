package group.gnometrading.orchestrator.gateway.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.RegistryConnection;
import group.gnometrading.account.SessionAccounts;
import group.gnometrading.account.VenueAccount;
import group.gnometrading.strings.ViewString;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * What the registry says of a session saying hello: its slot, status, strategy, mode, the listings it holds leases on,
 * and the venue account it is bound to on each exchange. Asked afresh at every hello; a record read within the last 10
 * minutes stands in when the registry can't be reached, so a session the gateway already knew isn't shut out by a
 * registry outage. The control thread's; allocates freely.
 */
public final class SessionRegistry {

    static final long CACHE_NANOS = TimeUnit.MINUTES.toNanos(10);

    /**
     * One session as the registry has it.
     *
     * @param rateWeight its strategy's weight in its share of an account's request budget
     * @param simulationJson a paper session's simulation, as its launch pinned it ({@code config.simulation}); null for
     *     a live one
     */
    public record SessionRecord(
            long sessionSeq,
            String status,
            int strategyId,
            String mode,
            Set<Integer> listingIds,
            Map<Integer, Integer> accountByExchange,
            int rateWeight,
            String simulationJson) {

        boolean running() {
            return "STARTING".equals(this.status) || "RUNNING".equals(this.status);
        }
    }

    /** Reads one session from the registry; null if it has none by that id. */
    @FunctionalInterface
    public interface Source {
        SessionRecord fetch(String sessionId) throws Exception;
    }

    /** The registry couldn't be asked, and nothing recent enough was cached. */
    static final class UnavailableException extends Exception {
        UnavailableException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    private record Cached(SessionRecord record, long fetchedAtNanos) {}

    private final Source source;
    private final Map<String, Cached> cache = new HashMap<>();

    public SessionRegistry(final Source source) {
        this.source = source;
    }

    /** A record read before this process started, as if read at {@code fetchedAtNanos} on this process's clock. */
    public void seed(final String sessionId, final SessionRecord record, final long fetchedAtNanos) {
        this.cache.put(sessionId, new Cached(record, fetchedAtNanos));
    }

    /** @return the session's record, or null if the registry has no such session */
    SessionRecord lookup(final String sessionId, final long nowNanos) throws UnavailableException {
        try {
            final SessionRecord record = this.source.fetch(sessionId);
            if (record == null) {
                this.cache.remove(sessionId);
            } else {
                this.cache.put(sessionId, new Cached(record, nowNanos));
            }
            return record;
        } catch (Exception e) {
            final Cached cached = this.cache.get(sessionId);
            if (cached != null && nowNanos - cached.fetchedAtNanos() < CACHE_NANOS) {
                return cached.record();
            }
            throw new UnavailableException("Registry unreachable for session " + sessionId, e);
        }
    }

    /** Reads sessions over the registry API: the session row, then its bound accounts. */
    public static Source over(final RegistryConnection registry) {
        final ObjectMapper mapper = new ObjectMapper();
        return sessionId -> {
            final JsonNode rows = read(
                    mapper,
                    registry,
                    "/api/strategy-sessions?sessionId=" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8));
            if (rows.isEmpty()) {
                return null;
            }
            final JsonNode row = rows.get(0);
            final Set<Integer> listings = new HashSet<>();
            for (final JsonNode listing : row.path("config").path("listings")) {
                listings.add(listing.asInt());
            }
            final Map<Integer, Integer> accounts = new HashMap<>();
            for (final VenueAccount account :
                    SessionAccounts.fetch(registry, sessionId).all()) {
                accounts.put(account.exchangeId(), account.accountId());
            }
            return new SessionRecord(
                    row.path("session_seq").asLong(),
                    row.path("status").asText(),
                    row.path("strategy_id").asInt(),
                    row.path("mode").asText(),
                    listings,
                    accounts,
                    row.path("rate_weight").asInt(1),
                    simulationJson(row.path("config").path("simulation")));
        };
    }

    /**
     * A local run's only session, whatever its id: a gateway run beside one session without the registry answers every
     * hello with this record, so the session's slot ({@code session.seq}) and listings must match it.
     *
     * @param json {@code session_seq}, {@code strategy_id}, {@code listings} (ids) and {@code simulation} (as a session's
     *     {@code SIMULATION_JSON}); a paper session, running, bound to no account
     */
    public static Source local(final String json) {
        final JsonNode config;
        try {
            config = new ObjectMapper().readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("A local session's record isn't JSON: " + e.getMessage(), e);
        }
        final Set<Integer> listings = new HashSet<>();
        for (final JsonNode listing : config.path("listings")) {
            listings.add(listing.asInt());
        }
        final SessionRecord record = new SessionRecord(
                config.path("session_seq").asLong(),
                "RUNNING",
                config.path("strategy_id").asInt(),
                "paper",
                Set.copyOf(listings),
                Map.of(),
                1,
                simulationJson(config.path("simulation")));
        return sessionId -> record;
    }

    /**
     * The id of the session with this {@code session_seq}; null if the registry has none. Polymarket US orders name a
     * session's slot, which is its {@code session_seq} until 100,000 sessions wrap it.
     */
    public static String idBySeq(final RegistryConnection registry, final long sessionSeq) throws IOException {
        final JsonNode rows = read(new ObjectMapper(), registry, "/api/strategy-sessions?sessionSeq=" + sessionSeq);
        return rows.isEmpty() ? null : rows.get(0).path("session_id").asText(null);
    }

    /** A session's simulation as one JSON value, in the form a session reads it from {@code SIMULATION_JSON}. */
    private static String simulationJson(final JsonNode simulation) {
        if (simulation.isMissingNode() || simulation.isNull()) {
            return null;
        }
        return simulation.isTextual() ? simulation.asText() : simulation.toString();
    }

    private static JsonNode read(final ObjectMapper mapper, final RegistryConnection registry, final String path)
            throws IOException {
        final ByteBuffer body = registry.get(new ViewString(path));
        final byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        return mapper.readTree(bytes);
    }
}
