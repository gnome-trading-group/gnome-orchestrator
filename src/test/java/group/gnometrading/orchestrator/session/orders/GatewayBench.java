package group.gnometrading.orchestrator.session.orders;

import com.sun.management.OperatingSystemMXBean;
import group.gnometrading.networking.sockets.factory.NativeSSLSocketFactory;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.gateway.bench.BenchGateway;
import group.gnometrading.orchestrator.gateway.bench.BenchTls;
import group.gnometrading.orchestrator.gateway.bench.BenchVenue;
import group.gnometrading.orchestrator.gateway.bench.GatewayHops;
import group.gnometrading.orchestrator.gateway.bench.TlsVenueServer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * The order gateway's latency bench: sessions over Aeron, a gateway built as a deployed one is, and a TLS venue that
 * answers as the real one does (Polymarket International, Kalshi or Polymarket US). Not part of the build; run by hand
 * with the test classpath, by role ({@code -Dbench.role}):
 *
 * <ul>
 *   <li>{@code local} (default): all three in this process.
 *   <li>{@code tls}: makes the certificates ({@code -Dbench.tls.dir}, valid for {@code -Dbench.tls.names}) and exits.
 *   <li>{@code gateway}: the gateway alone, as on its own host, its venue at {@code -Dbench.venue.host}, taking
 *       sessions on {@code -Dbench.ingress.host}; every {@code -Dbench.interval} seconds it reports its hops and each
 *       thread's CPU for that interval, until {@code -Dbench.minutes} pass.
 *   <li>{@code venue}: the venue alone, for as long as its gateway runs (so it outlives each client run), its
 *       counts every {@code -Dbench.interval} seconds.
 *   <li>{@code client}: the sessions, on the venue's host or another, their gateway at {@code
 *       -Dbench.gateway.host}, replies to {@code -Dbench.reply.host}.
 * </ul>
 *
 * <p>Common: {@code -Dbench.venue} (polymarket_intl, kalshi, polymarket_us), {@code -Dbench.listings}, {@code
 * -Dbench.seed} (both hosts must agree), {@code -Dbench.shape} (trickle, typical, saturated), {@code
 * -Dbench.sessions}, {@code -Dbench.seconds}, {@code -Dbench.warmup}, {@code -Dbench.overrides} (the gateway's
 * {@code --key=value} property overrides, comma separated).
 */
public final class GatewayBench {

    private static final int INGRESS_PORT = 40_990;
    private static final long SLOT_BASE = 1_000;
    // The gateway lets this many sessions hello, whatever shape the client runs.
    private static final int MAX_SESSIONS = 30;
    private static final long PRICE = 500_000_000L;
    private static final long SIZE = 5_000_000L;

    private GatewayBench() {}

    public static void main(final String[] args) throws Exception {
        final String role = System.getProperty("bench.role", "local");
        switch (role) {
            case "tls" -> BenchTls.create(
                    Path.of(System.getProperty("bench.tls.dir")),
                    List.of(System.getProperty("bench.tls.names").split(",")));
            case "gateway" -> runGateway();
            case "client" -> runClient();
            case "venue" -> runVenue();
            default -> runLocal();
        }
        System.exit(0);
    }

    private static BenchGateway.Account account() {
        return BenchGateway.Account.of(
                System.getProperty("bench.venue", "polymarket_intl"),
                Integer.getInteger("bench.listings", 2),
                Long.getLong("bench.seed", 1));
    }

    private static String[] overrides() {
        final String overrides = System.getProperty("bench.overrides", "");
        return overrides.isBlank() ? new String[0] : overrides.split(",");
    }

    private static void runLocal() throws Exception {
        final Path root = Files.createTempDirectory("gateway-bench");
        final BenchTls tls = BenchTls.create(root.resolve("tls"), List.of("127.0.0.1", "localhost"));
        // Read once, when the native TLS factories first load: set before anything opens a socket.
        System.setProperty(NativeSSLSocketFactory.CA_FILE_PROPERTY, tls.caFile().toString());
        final BenchGateway.Account account = account();
        final BenchVenue venue = account.model().get();
        try (TlsVenueServer server = new TlsVenueServer(tls.serverContext(), "0.0.0.0", 443, venue);
                BenchGateway gateway = BenchGateway.start(
                        account, "127.0.0.1", "127.0.0.1", INGRESS_PORT, root.resolve("state"), overrides())) {
            gateway.registerSessions(MAX_SESSIONS, SLOT_BASE);
            runSessions(account, venue, "127.0.0.1", "127.0.0.1", gateway.hops());
        } finally {
            venue.close();
        }
    }

    private static void runGateway() throws Exception {
        final Path tlsDir = Path.of(System.getProperty("bench.tls.dir"));
        System.setProperty(
                NativeSSLSocketFactory.CA_FILE_PROPERTY,
                BenchTls.open(tlsDir).caFile().toString());
        final long intervalNanos = TimeUnit.SECONDS.toNanos(Long.getLong("bench.interval", 30));
        final long untilNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(Long.getLong("bench.minutes", 30));
        final BenchGateway.Account account = account();
        try (BenchGateway gateway = BenchGateway.start(
                account,
                System.getProperty("bench.venue.host"),
                System.getProperty("bench.ingress.host"),
                INGRESS_PORT,
                Files.createTempDirectory("gateway-bench-state"),
                overrides())) {
            gateway.registerSessions(MAX_SESSIONS, SLOT_BASE);
            System.out.println("bench gateway: " + account.gatewayKey() + " taking sessions");
            while (System.nanoTime() < untilNanos) {
                final Usage before = Usage.now();
                Thread.sleep(TimeUnit.NANOSECONDS.toMillis(intervalNanos));
                final Usage after = Usage.now();
                System.out.println("== gateway interval " + before.wall + " .. " + after.wall);
                hops(gateway.hops());
                gateway.hops().clear();
                cpu(before, after);
                System.out.flush();
            }
        }
    }

    private static void runVenue() throws Exception {
        final BenchTls tls = BenchTls.open(Path.of(System.getProperty("bench.tls.dir")));
        final BenchVenue venue = account().model().get();
        final long intervalMillis = TimeUnit.SECONDS.toMillis(Long.getLong("bench.interval", 30));
        try (TlsVenueServer server = new TlsVenueServer(tls.serverContext(), "0.0.0.0", 443, venue)) {
            System.out.println("bench venue: listening on " + server.port());
            while (true) {
                Thread.sleep(intervalMillis);
                System.out.println("== venue " + Instant.now() + " " + venue.counts());
                System.out.flush();
            }
        } finally {
            venue.close();
        }
    }

    private static void runClient() throws Exception {
        runSessions(
                account(),
                null,
                System.getProperty("bench.gateway.host"),
                System.getProperty("bench.reply.host"),
                null);
    }

    /**
     * Runs the sessions' shape against the gateway and reports; {@code venue} and {@code hops} only when they are in
     * this process.
     */
    private static void runSessions(
            final BenchGateway.Account account,
            final BenchVenue venue,
            final String gatewayHost,
            final String replyHost,
            final GatewayHops hops)
            throws Exception {
        final String shapeName = System.getProperty("bench.shape", "trickle");
        final BenchSessions.Shape shape;
        final int defaultSessions;
        if (shapeName.equals("saturated")) {
            shape = BenchSessions.Shape.saturated();
            defaultSessions = 30;
        } else if (shapeName.equals("typical")) {
            shape = BenchSessions.Shape.typical();
            defaultSessions = 10;
        } else {
            shape = BenchSessions.Shape.trickle();
            defaultSessions = 1;
        }
        final int sessionCount = Math.min(MAX_SESSIONS, Integer.getInteger("bench.sessions", defaultSessions));
        final int listings = Integer.getInteger("bench.listings", 2);
        final long[] securities = new long[listings];
        for (int i = 0; i < listings; i++) {
            securities[i] = BenchGateway.FIRST_SECURITY_ID + i;
        }
        try (AeronRuntime aeron =
                        AeronRuntime.launch("bench-sessions", AeronProfile.LATENCY, Throwable::printStackTrace);
                BenchSessions sessions = new BenchSessions(
                        aeron,
                        new BenchSessions.Spec(
                                "bench-",
                                SLOT_BASE,
                                BenchGateway.STRATEGY_ID,
                                account.gatewayKey(),
                                account.exchangeId(),
                                BenchGateway.ACCOUNT_ID,
                                securities,
                                PRICE,
                                SIZE,
                                shape,
                                gatewayHost,
                                INGRESS_PORT,
                                replyHost),
                        sessionCount)) {
            awaitReady(sessions, TimeUnit.MINUTES.toNanos(Long.getLong("bench.link.minutes", 1)));
            System.out.printf(
                    "bench: %s, %d session(s) linked; shape %s %s%n",
                    account.gatewayKey(), sessionCount, shapeName, shape);
            sessions.startQuoting();
            sleepSeconds(Long.getLong("bench.warmup", 10), sessions);
            sessions.reset();
            if (hops != null) {
                hops.clear();
            }
            final Usage before = Usage.now();
            sleepSeconds(Long.getLong("bench.seconds", 60), sessions);
            final Usage after = Usage.now();
            sessions.stopQuoting();
            System.out.println("== measured " + before.wall + " .. " + after.wall);
            sessions(sessions);
            if (hops != null) {
                System.out.println("== gateway hops (us)");
                hops(hops);
            }
            if (venue != null) {
                System.out.println("== venue " + venue.counts());
            }
            cpu(before, after);
            final long drainUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (sessions.working() > 0 && System.nanoTime() < drainUntil) {
                Thread.sleep(50);
            }
        }
    }

    private static void awaitReady(final BenchSessions sessions, final long waitNanos) throws InterruptedException {
        final long until = System.nanoTime() + waitNanos;
        while (!sessions.allReady()) {
            if (System.nanoTime() > until) {
                throw new IllegalStateException("Sessions never linked to the bench gateway");
            }
            Thread.sleep(100);
        }
    }

    private static void sleepSeconds(final long seconds, final BenchSessions sessions) throws InterruptedException {
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < until) {
            if (sessions.failure() != null) {
                throw new IllegalStateException("The bench sessions failed", sessions.failure());
            }
            Thread.sleep(100);
        }
    }

    private static void sessions(final BenchSessions sessions) {
        System.out.println("== sessions (us)            n      p10      p50      p90      p99      max");
        row("order -> NEW", sessions.orderToNew.sorted());
        row("cancel -> end", sessions.cancelToEnd.sorted());
        row("raced cancel -> end", sessions.racedCancelToEnd.sorted());
        System.out.println("   outcomes " + sessions.outcomes());
        System.out.println("   raced " + sessions.racedTraces());
    }

    private static void hops(final GatewayHops hops) {
        for (final Map.Entry<String, GatewayHops.Hop> hop : hops.hops().entrySet()) {
            row(hop.getKey(), hop.getValue().sorted());
        }
    }

    private static void row(final String name, final long[] sorted) {
        if (sorted.length == 0) {
            System.out.printf("   %-22s %6d%n", name, 0);
            return;
        }
        System.out.printf(
                "   %-22s %6d %8.1f %8.1f %8.1f %8.1f %8.1f%n",
                name,
                sorted.length,
                at(sorted, 0.10),
                at(sorted, 0.50),
                at(sorted, 0.90),
                at(sorted, 0.99),
                sorted[sorted.length - 1] / 1e3);
    }

    private static double at(final long[] sorted, final double quantile) {
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(quantile * sorted.length))] / 1e3;
    }

    /** The process's and each thread's CPU over an interval, and the host's where it can be read. */
    private static void cpu(final Usage before, final Usage after) {
        final double wall = after.nanos - before.nanos;
        System.out.printf(
                "== CPU over %.0f s: process %.1f%% of a core, host %s%n",
                wall / 1e9,
                100.0 * (after.processCpu - before.processCpu) / wall,
                after.hostBusy < 0
                        ? "n/a"
                        : String.format(
                                "%.1f%% of %d cpus",
                                100.0
                                        * (after.hostBusy - before.hostBusy)
                                        / Math.max(1, after.hostTotal - before.hostTotal),
                                Runtime.getRuntime().availableProcessors()));
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        final Map<String, Double> byName = new TreeMap<>();
        for (final Map.Entry<Long, Long> thread : after.threads.entrySet()) {
            final ThreadInfo info = threads.getThreadInfo(thread.getKey());
            final long used = thread.getValue() - before.threads.getOrDefault(thread.getKey(), 0L);
            if (info != null && used > 0) {
                byName.merge(info.getThreadName(), 100.0 * used / wall, Double::sum);
            }
        }
        byName.entrySet().stream()
                .filter(e -> e.getValue() >= 0.1)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> System.out.printf("   %-60s %6.2f%n", e.getKey(), e.getValue()));
    }

    /** What has been used so far: per thread, by the process and (on Linux) by the host. */
    private record Usage(
            Instant wall, long nanos, Map<Long, Long> threads, long processCpu, long hostBusy, long hostTotal) {

        static Usage now() {
            final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            final Map<Long, Long> threads = new HashMap<>();
            for (final long id : bean.getAllThreadIds()) {
                final long nanos = bean.getThreadCpuTime(id);
                if (nanos >= 0) {
                    threads.put(id, nanos);
                }
            }
            final long processCpu =
                    ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
            long busy = -1;
            long total = -1;
            try {
                // cpu  user nice system idle iowait irq softirq steal ...
                final String[] fields =
                        Files.readAllLines(Path.of("/proc/stat")).get(0).trim().split("\\s+");
                total = 0;
                for (int i = 1; i < fields.length; i++) {
                    total += Long.parseLong(fields[i]);
                }
                busy = total - Long.parseLong(fields[4]) - Long.parseLong(fields[5]);
            } catch (IOException | RuntimeException e) {
                // No /proc/stat off Linux: the host's share is left out.
            }
            return new Usage(Instant.now(), System.nanoTime(), threads, processCpu, busy, total);
        }
    }
}
