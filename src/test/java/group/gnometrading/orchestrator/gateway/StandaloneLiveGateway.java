package group.gnometrading.orchestrator.gateway;

import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.orchestrator.gateway.accounts.GatewayAccount;
import group.gnometrading.orchestrator.gateway.core.CrossKeys;
import group.gnometrading.orchestrator.runtime.SamplePoster;
import group.gnometrading.orchestrator.runtime.SamplesApi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A live order gateway as a process of its own, for a load test from sessions on another host: the real ingress,
 * egress, control, journal and account lanes ({@link GatewayFixture}), with each account on a {@link SyntheticVenue}
 * in this process held to Kalshi's limits and answering after {@code --venue-latency-ms}. With {@code --sign true}
 * every request is signed as Kalshi's are ({@link SigningLaneVenue}), so the lanes pay a live gateway's cost per
 * request. Each account's lane holds {@code --connections} (default 8, as live gateways run).
 *
 * <p>It takes sessions on {@code --host:--port}: {@code --sessions} of them, ids {@code load-0} onwards in slots from
 * 1000, spread over {@code --accounts} accounts from {@link GatewayFixture#ACCOUNT_ID} as {@code GatewayLoadSessions}
 * with the same counts spreads them. Its samples, as a deployed gateway posts them, go to {@code --samples-out}; every
 * 10 s it prints what each venue took and refused.
 */
public final class StandaloneLiveGateway {

    private static final long REPORT_MILLIS = 10_000;

    private StandaloneLiveGateway() {}

    public static void main(final String[] args) throws Exception {
        final Map<String, String> flags = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            flags.put(args[i].substring(2), args[i + 1]);
        }
        final String host = flags.get("host");
        final int port = Integer.parseInt(flags.getOrDefault("port", "40985"));
        final int accounts = Integer.parseInt(flags.getOrDefault("accounts", "1"));
        final int sessions = Integer.parseInt(flags.getOrDefault("sessions", "30"));
        final long latencyMillis = Long.parseLong(flags.getOrDefault("venue-latency-ms", "6"));
        final double share = Double.parseDouble(flags.getOrDefault("share", "0.9"));
        final boolean sign = Boolean.parseBoolean(flags.getOrDefault("sign", "true"));
        final long minutes = Long.parseLong(flags.getOrDefault("minutes", "10"));
        final int connections = Integer.parseInt(flags.getOrDefault("connections", "8"));
        final long runtimeMillis = Long.parseLong(flags.getOrDefault("runtime-ms", "60000"));
        final Path state = Files.createTempDirectory("live-gateway-state");
        final Path journal = Files.createTempDirectory("live-gateway-journal");

        final List<SyntheticVenue> venues = new ArrayList<>();
        final List<GatewayAccount> gatewayAccounts = new ArrayList<>();
        final CrossKeys crossKeys = new CrossKeys();
        for (int i = 0; i < accounts; i++) {
            final SyntheticVenue venue = new SyntheticVenue(
                    i == 0 ? state : Files.createTempDirectory("live-gateway-venue"),
                    SyntheticVenue.Model.KALSHI,
                    latencyMillis);
            venues.add(venue);
            gatewayAccounts.add(GatewayFixture.account(
                    venue.venue,
                    SyntheticVenue.limits(SyntheticVenue.Model.KALSHI, share),
                    4096,
                    connections,
                    1,
                    GatewayFixture.ACCOUNT_ID + i,
                    crossKeys,
                    plain -> sign ? new SigningLaneVenue(plain) : plain));
        }
        final GatewayFixture.Registry registry = venues.get(0).venue.registry();
        for (int i = 0; i < sessions; i++) {
            registry.register(
                    "load-" + i,
                    1_000 + i,
                    1,
                    GatewayFixture.EXCHANGE_ID,
                    GatewayFixture.LISTING_ID,
                    GatewayFixture.ACCOUNT_ID + i % accounts);
        }
        final GatewayFixture gateway = GatewayFixture.start(
                "standalone-live-gateway",
                host,
                port,
                new GatewayFixture.Setup(
                        gatewayAccounts,
                        List.of(GatewayFixture.listing()),
                        registry,
                        state,
                        journal,
                        1 << 16,
                        false,
                        AccountLane.Admissions.NONE));
        final SamplePoster samples = flags.containsKey("samples-out")
                ? gateway.startSamples("live:load:main", SamplesApi.toFile(flags.get("samples-out")), runtimeMillis)
                : null;
        System.out.printf(
                "Live gateway on %s:%d: %d accounts, %d sessions registered, signing %s%n",
                host, port, accounts, sessions, sign);
        try {
            final long endMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes);
            while (System.currentTimeMillis() < endMs) {
                Thread.sleep(REPORT_MILLIS);
                final StringBuilder line = new StringBuilder("{\"tsMs\":").append(System.currentTimeMillis());
                line.append(",\"venues\":[");
                for (int i = 0; i < venues.size(); i++) {
                    final SyntheticVenue venue = venues.get(i);
                    line.append(i == 0 ? "" : ",")
                            .append("{\"account\":")
                            .append(GatewayFixture.ACCOUNT_ID + i)
                            .append(",\"requests\":")
                            .append(venue.requests())
                            .append(",\"accepted\":")
                            .append(venue.accepted())
                            .append(",\"refusals\":")
                            .append(venue.refusals())
                            .append('}');
                }
                System.out.println(line.append("]}"));
            }
        } finally {
            if (samples != null) {
                samples.stop(GatewaySamples.STOP_MILLIS);
            }
            gateway.close();
            for (final SyntheticVenue venue : venues) {
                venue.venue.server().close();
            }
        }
    }
}
