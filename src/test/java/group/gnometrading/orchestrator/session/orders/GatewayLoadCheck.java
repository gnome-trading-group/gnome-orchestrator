package group.gnometrading.orchestrator.session.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.gateway.GatewayFixture;
import group.gnometrading.orchestrator.gateway.SyntheticVenue;
import group.gnometrading.orchestrator.runtime.LatencyHistogram;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.gateway.AdmissionAction;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Thirty sessions saturating one account of an order gateway in this process, over Aeron, against a venue held to a
 * real venue's limits, with the gateway's journal written throughout; partway, the account's event socket drops. Not
 * part of the build (it runs for minutes): {@code mvn test -Dtest=<this class> [-Dload.seconds=300 -Dload.latency.ms=6 -Dload.tail.ms=0 -Dload.tail.share=0 -Dload.quotes=6 -Dload.share=0.9]}.
 *
 * <p>Passes when: each session's share of the orders the venue took is within 5% of its weight's share; cancels wait
 * for the budget at most 5 ms at p99 where the venue counts tokens (Kalshi: a cancel costs 2 of 300 a second), and at
 * most one request's worth of the budget where it counts requests (Polymarket US: 25 a second, so a cancel arriving
 * once the cancels' reserve is spent waits for the next, and goes with the others waiting); every fill the venue
 * pushed, during the socket's outage too, reaches its session once; and the venue never answers 429.
 */
class GatewayLoadCheck {

    private static final int SESSIONS = 30;
    private static final int PORT = 40985;
    private static final long WARM_UP_SECONDS = 10;
    private static final long SECONDS = Long.getLong("load.seconds", 300);
    private static final long LATENCY_MILLIS = Long.getLong("load.latency.ms", 6);
    private static final long TAIL_MILLIS = Long.getLong("load.tail.ms", 0);
    private static final double TAIL_SHARE = Double.parseDouble(System.getProperty("load.tail.share", "0"));
    private static final int QUOTES = Integer.getInteger("load.quotes", 6);
    private static final double SHARE = Double.parseDouble(System.getProperty("load.share", "0.9"));

    @TempDir
    Path state;

    @TempDir
    Path journal;

    @Test
    void thirtySessionsShareASaturatedKalshiAccountByWeight() throws Exception {
        run(SyntheticVenue.Model.KALSHI, 1);
    }

    @Test
    void thirtySessionsBatchOnAPolymarketUsAccount() throws Exception {
        run(SyntheticVenue.Model.POLYMARKET_US, 20);
    }

    private void run(final SyntheticVenue.Model model, final int maxBatch) throws Exception {
        final SyntheticVenue venue = new SyntheticVenue(this.state, model, LATENCY_MILLIS, TAIL_MILLIS, TAIL_SHARE);
        final int[] weights = new int[SESSIONS];
        for (int i = 0; i < SESSIONS; i++) {
            weights[i] = i % 3 + 1;
            venue.venue
                    .registry()
                    .register(
                            "load-" + i,
                            1_000 + i,
                            1,
                            GatewayFixture.EXCHANGE_ID,
                            GatewayFixture.LISTING_ID,
                            GatewayFixture.ACCOUNT_ID,
                            weights[i]);
        }
        final LatencyHistogram cancelWaits = new LatencyHistogram();
        final AccountLane.Admissions tap = new AccountLane.Admissions() {
            @Override
            public void decided(
                    final long sessionSlot,
                    final long clientOidCounter,
                    final RequestClass type,
                    final AdmissionAction action,
                    final long queuedNanos,
                    final long tokensLeft) {
                if (type == RequestClass.CANCEL && action == AdmissionAction.SENT) {
                    cancelWaits.record(TimeUnit.NANOSECONDS.toMicros(queuedNanos));
                }
            }
        };
        final GatewayFixture gateway = GatewayFixture.start(
                "load-gateway-" + model,
                PORT,
                new GatewayFixture.Setup(
                        GatewayFixture.account(venue.venue, SyntheticVenue.limits(model, SHARE), 4096, 16, maxBatch),
                        List.of(GatewayFixture.listing()),
                        venue.venue.registry(),
                        this.state,
                        this.journal,
                        1 << 16,
                        false,
                        tap));
        final AeronRuntime aeron =
                AeronRuntime.launch("load-sessions-" + model, AeronProfile.GATEWAY, Throwable::printStackTrace);
        final LoadSessions load = new LoadSessions(
                aeron,
                new LoadSessions.Spec(
                        "load-",
                        1_000,
                        1,
                        "live:load:main",
                        GatewayFixture.EXCHANGE_ID,
                        GatewayFixture.SECURITY_ID,
                        GatewayFixture.ACCOUNT_ID,
                        400_000_000L,
                        1_000_000L,
                        QUOTES,
                        TimeUnit.MILLISECONDS.toNanos(50),
                        50,
                        "127.0.0.1",
                        PORT),
                SESSIONS);
        try {
            await(load::allReady, 30, "every session welcomed");
            load.startQuoting();
            sleepSeconds(WARM_UP_SECONDS);
            final long[] before = acks(load);
            final long requestsBefore = venue.requests();
            final long acceptedBefore = venue.accepted();
            sleepSeconds(SECONDS / 2);
            venue.dropSocket(2_000);
            sleepSeconds(SECONDS - SECONDS / 2);
            final long[] after = acks(load);
            final double requestRate = (venue.requests() - requestsBefore) / (double) SECONDS;
            final double orderRate = (venue.accepted() - acceptedBefore) / (double) SECONDS;
            load.stopQuoting();
            await(
                    () -> load.working() == 0,
                    60,
                    () -> "every order ended; still working " + load.describeWorking() + "; lane holds "
                            + gateway.account().lane().workingOrders() + "; " + gateway.lastMinute());
            await(() -> fills(load) == venue.fillsPushed(), 30, "every fill reported");

            final List<String> shares = new ArrayList<>();
            long total = 0;
            long weightSum = 0;
            for (int i = 0; i < SESSIONS; i++) {
                total += after[i] - before[i];
                weightSum += weights[i];
            }
            double worst = 0;
            for (int i = 0; i < SESSIONS; i++) {
                final double expected = (double) total * weights[i] / weightSum;
                final double off = Math.abs((after[i] - before[i]) / expected - 1);
                worst = Math.max(worst, off);
                shares.add(String.format("w%d:%d(%+.1f%%)", weights[i], after[i] - before[i], 100 * off));
            }
            System.out.printf(
                    "%s load: %d s, %d sessions; venue took %.1f orders/s on %.1f requests/s, 429s %d; "
                            + "cancel wait p99 %d us max %d us (%d cancels); worst share off %.1f%%; "
                            + "%d events missed during the socket drop; fills %d; refusals %s; lane %s%n  shares %s%n",
                    model,
                    SECONDS,
                    SESSIONS,
                    orderRate,
                    requestRate,
                    venue.refusals(),
                    cancelWaits.percentileMicros(0.99),
                    cancelWaits.maxMicros(),
                    cancelWaits.count(),
                    100 * worst,
                    venue.missed().size(),
                    venue.fillsPushed(),
                    refusals(load),
                    gateway.lastMinute(),
                    shares);

            assertEquals(0, venue.refusals(), "the venue never answered 429");
            assertTrue(worst <= 0.05, "every share within 5% of its weight's: worst " + 100 * worst + "%");
            final long cancelBoundMicros = model == SyntheticVenue.Model.KALSHI
                    ? 5_000
                    : (long) (1_000_000 / (model.perSecond() * SHARE)) + 5_000;
            assertTrue(
                    cancelWaits.percentileMicros(0.99) < cancelBoundMicros,
                    "cancels waited under " + cancelBoundMicros + " us at p99");
            assertTrue(!venue.missed().isEmpty(), "the socket's outage missed events for catch-up to find");
            assertEquals(venue.fillsPushed(), fills(load), "every fill reached its session once");
            assertTrue(load.failure() == null, "the sessions' driver failed: " + load.failure());
        } finally {
            load.close();
            gateway.close();
            aeron.close();
            venue.venue.server().close();
        }
    }

    private static long[] acks(final LoadSessions load) {
        final long[] acks = new long[SESSIONS];
        for (int i = 0; i < SESSIONS; i++) {
            acks[i] = load.sessions().get(i).acks;
        }
        return acks;
    }

    private static long fills(final LoadSessions load) {
        long fills = 0;
        for (final LoadSessions.Session session : load.sessions()) {
            fills += session.fills;
        }
        return fills;
    }

    private static String refusals(final LoadSessions load) {
        final StringBuilder text = new StringBuilder();
        for (final RejectReason reason : RejectReason.values()) {
            long count = 0;
            for (final LoadSessions.Session session : load.sessions()) {
                count += session.rejects(reason);
            }
            if (count > 0) {
                text.append(reason).append('=').append(count).append(' ');
            }
        }
        return text.toString().trim();
    }

    private static void sleepSeconds(final long seconds) throws InterruptedException {
        Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
    }

    private static void await(final BooleanSupplier condition, final long seconds, final String what)
            throws InterruptedException {
        await(condition, seconds, () -> what);
    }

    private static void await(
            final BooleanSupplier condition, final long seconds, final java.util.function.Supplier<String> what)
            throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what.get());
            }
            Thread.sleep(10);
        }
    }
}
