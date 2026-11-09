package group.gnometrading.orchestrator.gateway.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.RegistryConnection;
import group.gnometrading.strings.ViewString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Where the gateway reads the kill switches in force. Blocks; read on the kill poller's own thread. */
@FunctionalInterface
public interface KillSource {

    /**
     * One kill switch: the targets it names, each null or 0 when it names none. A row naming none kills everything.
     *
     * @param sessionId a session, by its id
     * @param strategyId a strategy
     * @param exchangeId an exchange
     * @param listingId a listing, by its registry id
     * @param accountId a venue account
     */
    record Kill(String sessionId, int strategyId, int exchangeId, int listingId, int accountId) {}

    String KILL_SWITCH = "KILL_SWITCH";

    List<Kill> fetch() throws IOException;

    /** Every enabled kill switch in the registry: the same rows the OMS reads, and the account kills only this reads. */
    static KillSource over(final RegistryConnection registry) {
        final ObjectMapper mapper = new ObjectMapper();
        final ViewString path = new ViewString("/api/risk/policies?enabled=true&policyType=KILL_SWITCH");
        return () -> {
            final ByteBuffer body = registry.get(path);
            final byte[] bytes = new byte[body.remaining()];
            body.get(bytes);
            return parse(mapper.readTree(bytes));
        };
    }

    /**
     * Kill switches written by hand as the registry would return them, one JSON array in a file, re-read on every
     * fetch: for running a gateway against real venues without touching the registry's kills. A missing file is no
     * kills.
     */
    static KillSource file(final Path path) {
        final ObjectMapper mapper = new ObjectMapper();
        return () -> Files.exists(path) ? parse(mapper.readTree(Files.readAllBytes(path))) : List.of();
    }

    private static List<Kill> parse(final JsonNode rows) throws IOException {
        if (!rows.isArray()) {
            throw new IOException("kill switches aren't a list: " + rows.getNodeType());
        }
        final List<Kill> kills = new ArrayList<>();
        for (final JsonNode row : rows) {
            // A registry that doesn't know the filters answers every policy; only enabled kill switches are kills.
            if ((row.has("policy_type")
                            && !KILL_SWITCH.equals(row.path("policy_type").asText()))
                    || (row.has("enabled") && !row.path("enabled").asBoolean())) {
                continue;
            }
            final JsonNode session = row.path("session_id");
            kills.add(new Kill(
                    session.isTextual() ? session.asText() : null,
                    row.path("strategy_id").asInt(0),
                    row.path("exchange_id").asInt(0),
                    row.path("listing_id").asInt(0),
                    row.path("account_id").asInt(0)));
        }
        return kills;
    }
}
