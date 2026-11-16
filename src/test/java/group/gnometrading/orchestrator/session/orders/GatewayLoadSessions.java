package group.gnometrading.orchestrator.session.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.schemas.RejectReason;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link LoadSessions} as a process of its own, on its own host, for an order gateway's load test: many sessions,
 * each linked to the gateway over Aeron and quoting on its listings, so the gateway carries their books and orders.
 *
 * <p>{@code --listings} names a file of {@code listingId securityId} lines: session {@code i} takes
 * {@code --per-session} of them from {@code i * per-session}, wrapping, so fewer listings than sessions × per-session
 * means sessions share books. {@code --write-sessions <path>} writes the paper gateway's {@code gateway.local.session}
 * records for those sessions and exits; run it with the same flags as the load.
 *
 * <p>Each session keeps {@code --quotes} post-only bids working at {@code --price}, cancelling each after
 * {@code --rest-ms}, so it sends about {@code quotes * 1000 / rest-ms} orders and as many cancels a second. Every
 * {@code 10 s} it prints one JSON line (and appends it to {@code --out}): sessions linked, orders acknowledged,
 * cancels, fills and refusals so far, and the acknowledgement and cancel waits. {@code --accounts n} spreads sessions
 * over accounts {@code --account-id} onwards, for a live gateway holding several.
 */
public final class GatewayLoadSessions {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long REPORT_MILLIS = 10_000;

    private GatewayLoadSessions() {}

    public static void main(final String[] args) throws Exception {
        final Map<String, String> flags = flags(args);
        final List<long[]> listings = listings(Path.of(flags.get("listings")));
        final int sessions = Integer.parseInt(flags.get("sessions"));
        final int perSession = Integer.parseInt(flags.getOrDefault("per-session", "10"));
        final String prefix = flags.getOrDefault("prefix", "load-");
        final long slotBase = Long.parseLong(flags.getOrDefault("slot-base", "1000"));
        final int strategyId = Integer.parseInt(flags.getOrDefault("strategy-id", "1"));
        if (flags.containsKey("write-sessions")) {
            writeSessions(
                    Path.of(flags.get("write-sessions")),
                    listings,
                    sessions,
                    perSession,
                    prefix,
                    slotBase,
                    strategyId,
                    flags.getOrDefault("as-of", "2026-11-01T00:00:00Z"));
            return;
        }
        final long[] securityIds = new long[listings.size()];
        for (int i = 0; i < securityIds.length; i++) {
            securityIds[i] = listings.get(i)[1];
        }
        final String[] gateway = flags.get("gateway").split(":");
        final LoadSessions.Spec spec = new LoadSessions.Spec(
                prefix,
                slotBase,
                strategyId,
                flags.get("gateway-key"),
                Integer.parseInt(flags.get("exchange-id")),
                securityIds,
                perSession,
                Integer.parseInt(flags.getOrDefault("account-id", "0")),
                Integer.parseInt(flags.getOrDefault("accounts", "1")),
                Long.parseLong(flags.getOrDefault("price", "10000000")),
                Long.parseLong(flags.getOrDefault("size", "1000000")),
                Integer.parseInt(flags.getOrDefault("quotes", "1")),
                TimeUnit.MILLISECONDS.toNanos(Long.parseLong(flags.getOrDefault("rest-ms", "1000"))),
                Integer.parseInt(flags.getOrDefault("max-queue-ms", "50")),
                gateway[0],
                Integer.parseInt(gateway[1]),
                flags.get("reply-host"));
        final long minutes = Long.parseLong(flags.getOrDefault("minutes", "10"));
        final String out = flags.get("out");

        final AeronRuntime aeron =
                AeronRuntime.launch("gateway-load-sessions", AeronProfile.GATEWAY, Throwable::printStackTrace);
        try (LoadSessions load = new LoadSessions(aeron, spec, sessions);
                PrintStream file = out == null ? null : new PrintStream(new FileOutputStream(out, true))) {
            boolean quoting = false;
            final long endMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes);
            while (System.currentTimeMillis() < endMs && load.failure() == null) {
                Thread.sleep(REPORT_MILLIS);
                if (!quoting && load.allReady()) {
                    load.startQuoting();
                    quoting = true;
                }
                final String line = report(load, quoting);
                System.out.println(line);
                if (file != null) {
                    file.println(line);
                    file.flush();
                }
            }
            if (load.failure() != null) {
                load.failure().printStackTrace();
            }
            load.stopQuoting();
        } finally {
            aeron.close();
        }
    }

    private static String report(final LoadSessions load, final boolean quoting) throws Exception {
        long acks = 0;
        long cancels = 0;
        long fills = 0;
        int ready = 0;
        final Map<RejectReason, Long> rejects = new HashMap<>();
        for (final LoadSessions.Session session : load.sessions()) {
            acks += session.acks;
            cancels += session.cancels;
            fills += session.fills;
            ready += session.ready() ? 1 : 0;
            for (final RejectReason reason : RejectReason.values()) {
                final long count = session.rejects(reason);
                if (count > 0) {
                    rejects.merge(reason, count, Long::sum);
                }
            }
        }
        final ObjectNode line = MAPPER.createObjectNode()
                .put("tsMs", System.currentTimeMillis())
                .put("sessions", load.sessions().size())
                .put("ready", ready)
                .put("quoting", quoting)
                .put("acks", acks)
                .put("cancels", cancels)
                .put("fills", fills)
                .put("working", load.working())
                .put("hearing", load.describeHearing());
        final ObjectNode refused = line.putObject("rejects");
        rejects.forEach((reason, count) -> refused.put(reason.name(), count));
        return MAPPER.writeValueAsString(line);
    }

    /** The paper gateway's records of these sessions: each running, paper, on its listings, simulated as of a time. */
    static void writeSessions(
            final Path path,
            final List<long[]> listings,
            final int sessions,
            final int perSession,
            final String prefix,
            final long slotBase,
            final int strategyId,
            final String asOf)
            throws Exception {
        final ArrayNode records = MAPPER.createArrayNode();
        for (int i = 0; i < sessions; i++) {
            final ObjectNode record = records.addObject()
                    .put("session_id", prefix + i)
                    .put("session_seq", slotBase + i)
                    .put("strategy_id", strategyId);
            final ArrayNode ids = record.putArray("listings");
            for (int k = 0; k < perSession; k++) {
                ids.add(listings.get((i * perSession + k) % listings.size())[0]);
            }
            record.putObject("simulation").put("as_of", asOf);
        }
        Files.writeString(path, MAPPER.writeValueAsString(records));
    }

    /** {@code listingId securityId} per line; blank lines and {@code #} comments skipped. */
    static List<long[]> listings(final Path path) throws Exception {
        final List<long[]> listings = new ArrayList<>();
        for (final String line : Files.readAllLines(path)) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            final String[] parts = trimmed.split("\\s+");
            listings.add(new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1])});
        }
        if (listings.isEmpty()) {
            throw new IllegalArgumentException("No listings in " + path);
        }
        return listings;
    }

    private static Map<String, String> flags(final String[] args) {
        final Map<String, String> flags = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Expected a --flag, got " + args[i]);
            }
            flags.put(args[i].substring(2), args[i + 1]);
        }
        return flags;
    }
}
