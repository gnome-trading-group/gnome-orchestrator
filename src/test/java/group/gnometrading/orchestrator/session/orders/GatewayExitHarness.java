package group.gnometrading.orchestrator.session.orders;

import group.gnometrading.account.VenueAccount;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.account.admission.FairShareTrace;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.gateways.ratelimit.RequestClass;
import group.gnometrading.gateways.venues.kalshi.KalshiAuthSigner;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.credentials.KalshiCredentials;
import group.gnometrading.orchestrator.gateway.GatewayFixture;
import group.gnometrading.orchestrator.gateway.accounts.GatewayAccount;
import group.gnometrading.orchestrator.gateway.accounts.GatewaySettings;
import group.gnometrading.orchestrator.gateway.accounts.GatewayVenue;
import group.gnometrading.orchestrator.gateway.accounts.VenueAccounts;
import group.gnometrading.orchestrator.gateway.core.CrossKeys;
import group.gnometrading.orchestrator.runtime.LatencyHistogram;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.OrderExecutionReportEncoder;
import group.gnometrading.schemas.OrderType;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.Statics;
import group.gnometrading.schemas.TimeInForce;
import group.gnometrading.schemas.gateway.AdmissionAction;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.JournalReader;
import group.gnometrading.sequencer.JournalRecordHandler;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.Security;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * The order gateway's exits against a real venue: a gateway on one real account in this process, sessions as gateway
 * clients with their OMS's buffers, each scenario on a fresh gateway, printing pass or fail. Places real orders:
 * post-only bids far from the market, cancelled by the end of each scenario whatever happens.
 *
 * <pre>
 * -Dconfirm=yes                      required: real orders
 * -Dvenue=kalshi|pmus|pmintl         -Dmarket=&lt;ticker | slug | condition:token&gt;
 * -Dstage=dev|prod                   which venue URLs (orchestrator.&lt;stage&gt;.properties) and AWS profile; Kalshi
 *                                    dev is Kalshi's demo exchange
 * -Daws.profile=&lt;name&gt;|instance       default: the stage's name; instance on EC2
 * -Dsecret=gnome/accounts/&lt;label&gt;/trading   -Dpm.funder=0x... (Polymarket Intl: the account's recorded funder)
 * -Dsecret2=gnome/accounts/&lt;label&gt;/trading  optional: a second account on the same venue (-Dpm.funder2), for stp's
 *                                    check across accounts
 * -Dprice=0.01 -Dsize=1              -Dscenario=all|trade|restart|evict|lap|orphan|blocked|cod|stp|kill|load|
 *                                    emergency, several comma-separated (all is every exit but load and emergency),
 *                                    or list (the account's resting orders on the market) or sweep (cancels them all)
 *                                    emergency starts the deployed EmergencyCancel in -Demergency.region (the
 *                                    exchange's region) with this run's AWS credentials, which need
 *                                    lambda:InvokeFunction (a person's profile; not the live instance role)
 * -Dload.sessions=30 -Dload.seconds=120   load: that many sessions, weights 1, 2 and 3 in turn, each keeping
 * -Dload.quotes=12 -Dload.hold.ms=50  load.quotes bids working, each waiting load.hold.ms at most for the budget and
 *                                    cancelled 50 ms after it rests, faster than the account's budget allows; passes
 *                                    if the venue never answers 429 and every session's share of the orders
 *                                    acknowledged is within 10% of its weight's
 * </pre>
 */
public final class GatewayExitHarness {

    private static final int PORT = 40979;
    private static final int DEAF_PORT = 40996;
    private static final int EXCHANGE_ID = 1;
    private static final long SECURITY_ID = 1;
    private static final int LISTING_ID = 1;
    private static final int ACCOUNT_ID = 1;
    private static final int SECOND_ACCOUNT_ID = 2;
    private static final int STRATEGY_ID = 7;
    // A second strategy on the same listing, for what one strategy's orders do to another's.
    private static final int OTHER_STRATEGY_ID = 8;
    private static final int COD_MILLIS = 5_000;
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);
    // The journal's sources, as GatewayJournal numbers them.
    private static final int INGRESS = 1;
    private static final int EGRESS = 2;

    private final String stage = System.getProperty("stage", "dev");
    private final Properties properties;
    private final GatewayVenue venue;
    private final VenueAccount account;
    // Null without -Dsecret2.
    private final VenueAccount secondAccount;
    private final Listing listing;
    private final SecretsManagerClient secrets;
    private final AwsCredentialsProvider credentials;
    private final GatewayFixture.Registry registry = new GatewayFixture.Registry();
    private final String run = Long.toString(System.currentTimeMillis() / 1000 % 1_000_000, Character.MAX_RADIX);
    private final long price;
    private final long size;
    private final List<String> failures = new ArrayList<>();
    // Every order this run placed, by the id its reports carry, for the cleanup.
    private final Set<String> placed = ConcurrentHashMap.newKeySet();
    private final AeronRuntime sessionAeron =
            AeronRuntime.launch("exit-sessions", AeronProfile.GATEWAY, Throwable::printStackTrace);
    private final ScheduledExecutorService linkThread = Executors.newSingleThreadScheduledExecutor();
    private GatewayAccount queries;
    private GatewayAccount secondQueries;
    private long nextSlot;

    private GatewayExitHarness() throws Exception {
        this.properties = new Properties("orchestrator." + this.stage + ".properties");
        this.venue = switch (required("venue")) {
            case "kalshi" -> GatewayVenue.KALSHI;
            case "pmus" -> GatewayVenue.POLYMARKET_US;
            case "pmintl" -> GatewayVenue.POLYMARKET_INTL;
            default -> throw new IllegalArgumentException("-Dvenue is kalshi, pmus or pmintl");};
        this.account = new VenueAccount(
                ACCOUNT_ID, EXCHANGE_ID, "exit-account", required("secret"), System.getProperty("pm.funder"));
        this.secondAccount = System.getProperty("secret2") == null
                ? null
                : new VenueAccount(
                        SECOND_ACCOUNT_ID,
                        EXCHANGE_ID,
                        "exit-account-2",
                        System.getProperty("secret2"),
                        System.getProperty("pm.funder2"));
        this.listing = listing(this.venue, required("market"));
        final String profile = System.getProperty("aws.profile", this.stage);
        // "instance": on EC2, the instance role's credentials.
        final AwsCredentialsProvider credentials = profile.equals("instance")
                ? DefaultCredentialsProvider.create()
                : ProfileCredentialsProvider.create(profile);
        try {
            credentials.resolveCredentials();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "No AWS credentials for profile " + profile + " (run: aws sso login --profile " + profile + ")", e);
        }
        this.credentials = credentials;
        this.secrets = SecretsManagerClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .build();
        this.price = scaled(System.getProperty("price", "0.01"), Statics.PRICE_SCALING_FACTOR);
        this.size = scaled(System.getProperty("size", "1"), Statics.SIZE_SCALING_FACTOR);
        // Polymarket US names orders by stamps made from the slot: a fresh range per run keeps them apart.
        this.nextSlot = System.currentTimeMillis() / 1000 % 90_000;
    }

    public static void main(final String[] args) throws Exception {
        if (!"yes".equals(System.getProperty("confirm"))) {
            throw new IllegalArgumentException("Places real orders: -Dconfirm=yes is required");
        }
        final GatewayExitHarness harness = new GatewayExitHarness();
        boolean ok = false;
        try {
            ok = harness.runAll(System.getProperty("scenario", "all"));
        } catch (Throwable e) {
            // Aeron's threads would keep the process alive past an error.
            e.printStackTrace();
        } finally {
            harness.cleanUp();
            harness.linkThread.shutdownNow();
            harness.sessionAeron.close();
        }
        System.out.println(ok ? "\nPASS" : "\nFAIL");
        System.exit(ok ? 0 : 1);
    }

    private static final class CheckFailed extends RuntimeException {
        CheckFailed() {
            super(null, null, false, false);
        }
    }

    @FunctionalInterface
    private interface Scenario {
        void run() throws Exception;
    }

    private boolean runAll(final String scenario) throws Exception {
        if (scenario.equals("list") || scenario.equals("sweep")) {
            return listOrSweep(scenario.equals("sweep"));
        }
        final Map<String, Scenario> scenarios = new LinkedHashMap<>();
        scenarios.put("trade", this::trade);
        scenarios.put("restart", this::restart);
        scenarios.put("evict", this::evict);
        scenarios.put("lap", this::lap);
        scenarios.put("orphan", this::orphan);
        scenarios.put("blocked", this::blocked);
        scenarios.put("cod", this::cod);
        scenarios.put("stp", this::stp);
        scenarios.put("kill", this::kill);
        scenarios.put("load", this::load);
        scenarios.put("emergency", this::emergency);
        final Set<String> wanted = Set.of(scenario.split(","));
        for (final Map.Entry<String, Scenario> entry : scenarios.entrySet()) {
            final boolean inAll = wanted.contains("all")
                    && !entry.getKey().equals("load")
                    && !entry.getKey().equals("emergency");
            if (!inAll && !wanted.contains(entry.getKey())) {
                continue;
            }
            System.out.printf("%n== %s%n", entry.getKey());
            final int before = this.failures.size();
            try {
                entry.getValue().run();
            } catch (CheckFailed e) {
                // Already recorded and printed.
            } catch (Exception | AssertionError e) {
                this.failures.add(entry.getKey() + ": " + e);
                e.printStackTrace();
            }
            System.out.println(this.failures.size() == before ? "   ok" : "   FAILED");
            cleanUp();
        }
        this.failures.forEach(failure -> System.out.println("FAIL " + failure));
        return this.failures.isEmpty();
    }

    // ----- the scenarios -----

    /** Orders out and reports back through the gateway; its journal then holds both, with no gap. */
    private void trade() throws Exception {
        final Path journal = Files.createTempDirectory("exit-journal");
        try (Gateway gateway = new Gateway(journal, 1024, false);
                TestSession session = gateway.session("a")) {
            for (int counter = 1; counter <= 3; counter++) {
                session.order(counter);
            }
            check(await(() -> session.count(ExecType.NEW) == 3), "three orders acknowledged");
            for (int counter = 1; counter <= 3; counter++) {
                session.cancel(counter);
            }
            check(await(() -> session.count(ExecType.CANCEL) == 3), "three cancels reported");
            gateway.close();
            final int[] counts = readJournal(journal);
            System.out.printf("   journal: %d in, %d out, %d gaps%n", counts[0], counts[1], counts[2]);
            check(counts[0] >= 6, "the journal holds the orders and cancels sent in");
            check(counts[1] >= 6, "the journal holds the reports sent out");
            check(counts[2] == 0, "the journal has no gap");
        }
    }

    /** Killed with resting orders, one cancelled at the venue meanwhile: the session hears it once; the other rests. */
    private void restart() throws Exception {
        final Path state = Files.createTempDirectory("exit-state");
        final Gateway first = new Gateway(state, null, 0, false);
        try (first;
                TestSession session = first.session("r")) {
            session.order(1);
            session.order(2);
            check(await(() -> session.count(ExecType.NEW) == 2), "both orders acknowledged");
            first.close();
            final VenueOrderQuery query = queries().orderQuery();
            final Optional<VenueOrder> order =
                    query.getOrder(this.listing, session.exchangeOrderId(1), System.currentTimeMillis() - 600_000);
            check(
                    order.isPresent() && query.cancel(this.listing, order.get()),
                    "order 1 cancelled at the venue meanwhile");
            try (Gateway second = new Gateway(state, null, 0, false)) {
                check(await(() -> session.count(ExecType.CANCEL, 1) == 1), "the session heard of the cancel");
                check(session.count(ExecType.CANCEL, 2) == 0, "order 2 still rests");
                session.cancel(2);
                check(await(() -> session.count(ExecType.CANCEL, 2) == 1), "order 2 cancelled through the new gateway");
                Thread.sleep(500);
                check(session.count(ExecType.CANCEL, 1) == 1, "nothing heard twice");
            }
        }
    }

    /** A session that sends something unreadable is cut off; another goes on trading. */
    private void evict() throws Exception {
        try (Gateway gateway = new Gateway(null, 0, false);
                TestSession good = gateway.session("g");
                TestSession poison = gateway.session("p")) {
            final UnsafeBuffer garbage = new UnsafeBuffer(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9});
            check(await(() -> poison.link.publication.offer(garbage, 0, 9) > 0), "the garbage sent");
            check(await(() -> poison.link.state != GatewayLink.READY), "the poison session cut off");
            good.order(1);
            check(await(() -> good.count(ExecType.NEW) == 1), "the other session's order acknowledged");
            good.cancel(1);
            check(await(() -> good.count(ExecType.CANCEL) == 1), "and cancelled");
        }
    }

    /** A journal held back until it laps: orders keep flowing, and the journal records the gap. */
    private void lap() throws Exception {
        final Path journal = Files.createTempDirectory("exit-journal");
        final int orders = 12;
        try (Gateway gateway = new Gateway(journal, 16, true);
                TestSession session = gateway.session("l")) {
            for (int counter = 1; counter <= orders; counter++) {
                session.order(counter);
            }
            check(await(() -> session.answered() >= orders), "every order answered with the journal held back");
            for (int counter = 1; counter <= orders; counter++) {
                session.cancel(counter);
            }
            check(await(() -> session.answered() >= 2 * orders), "every cancel answered");
            gateway.fixture.startJournal();
            check(await(() -> gateway.fixture.journalLapped() > 0), "the journal lapped");
            gateway.close();
            final int[] counts = readJournal(journal);
            System.out.printf("   journal: %d in, %d out, %d gaps%n", counts[0], counts[1], counts[2]);
            check(counts[2] > 0, "the gap is in the journal");
        }
    }

    /** Session and gateway both killed: the restarted gateway cancels the orders once the hello grace has passed. */
    private void orphan() throws Exception {
        final Path state = Files.createTempDirectory("exit-state");
        final List<String> ids;
        try (Gateway first = new Gateway(state, null, 0, false);
                TestSession session = first.session("o")) {
            session.order(1);
            session.order(2);
            check(await(() -> session.count(ExecType.NEW) == 2), "both orders acknowledged");
            ids = List.of(session.exchangeOrderId(1), session.exchangeOrderId(2));
        }
        // The venue's reads lag its writes: an order acknowledged a moment ago may not be listed yet.
        check(awaitFor(() -> resting(ids) == 2, WAIT_NANOS), "both rest at the venue with nobody left");
        try (Gateway second = new Gateway(state, null, 0, false)) {
            final long start = System.nanoTime();
            check(awaitGone(ids, TimeUnit.SECONDS.toNanos(60)), "the orphans cancelled");
            System.out.printf(
                    "   cancelled %d s after the restart (hello grace 10 s + cod %d s)%n",
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start), COD_MILLIS / 1000);
        }
    }

    /**
     * A session that can send but not hear the gateway, however often it reconnects and says hello, has its orders
     * cancelled within its timeout.
     */
    private void blocked() throws Exception {
        try (Gateway gateway = new Gateway(null, 0, false);
                TestSession session = gateway.session("b")) {
            session.order(1);
            check(await(() -> session.count(ExecType.NEW) == 1), "the order acknowledged");
            final String id = session.exchangeOrderId(1);
            check(awaitFor(() -> resting(List.of(id)) == 1, WAIT_NANOS), "the order rests at the venue");
            final long start = System.nanoTime();
            // Its hellos from now on give a port nothing listens on, so no reconnect hears the gateway either.
            session.links.advertisedReplyPort = port -> DEAF_PORT;
            session.link.replies = null;
            check(awaitGone(List.of(id), TimeUnit.SECONDS.toNanos(30)), "its order cancelled");
            System.out.printf(
                    "   cancelled %d ms after it went deaf (cod %d ms)%n",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), COD_MILLIS);
        }
    }

    /**
     * A session's process killed with orders resting: the gateway hears nothing from it and cancels them within its
     * timeout. The venue's own read of them can lag a second or two behind, so that much is allowed on top.
     */
    private void cod() throws Exception {
        try (Gateway gateway = new Gateway(null, 0, false)) {
            final TestSession session = gateway.session("c");
            try {
                session.order(1);
                session.order(2);
                check(await(() -> session.count(ExecType.NEW) == 2), "both orders acknowledged");
                final List<String> ids = List.of(session.exchangeOrderId(1), session.exchangeOrderId(2));
                check(awaitFor(() -> resting(ids) == 2, WAIT_NANOS), "both rest at the venue");
                final long start = System.nanoTime();
                session.close();
                check(
                        awaitGone(ids, TimeUnit.MILLISECONDS.toNanos(COD_MILLIS + 1_000 + 2_000)),
                        "both cancelled within the timeout + 1 s (+ 2 s for the venue's reads)");
                System.out.printf(
                        "   gone from the venue %d ms after the session died (cod %d ms)%n",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), COD_MILLIS);
            } finally {
                // Closing twice is harmless; a check that throws above still leaves no session running.
                session.close();
            }
        }
    }

    /**
     * Two strategies on one listing: an order that would trade against the other's is refused SELF_TRADE before it is
     * sent, whether the other's order rests or was sent in the same moment. Every crossing order is post-only, so if
     * the check let one through the venue would refuse it rather than fill it.
     */
    private void stp() throws Exception {
        try (Gateway gateway = new Gateway(null, 0, false);
                TestSession first = gateway.session("s1", STRATEGY_ID);
                TestSession second = gateway.session("s2", OTHER_STRATEGY_ID)) {
            first.order(1);
            check(await(() -> first.count(ExecType.NEW) == 1), "the first strategy's bid acknowledged");
            second.order(1, Side.Ask, price);
            check(await(() -> second.answered() == 1), "the crossing ask answered");
            check(selfTrade(second, 1), "the ask against a resting bid refused SELF_TRADE");

            first.order(2);
            second.order(2, Side.Ask, price);
            check(
                    await(() -> refusedSelfTrade(first, 2) || refusedSelfTrade(second, 2)),
                    "of two crossing orders sent together, one refused SELF_TRADE");
            check(
                    !(refusedSelfTrade(first, 2) && refusedSelfTrade(second, 2)),
                    "and only one: the other went to the venue");
        }
        if (this.secondAccount == null) {
            System.out.println("   (no -Dsecret2: the check across two accounts not run)");
            return;
        }
        cleanUp();
        try (Gateway gateway = new Gateway(this.account, this.secondAccount);
                TestSession first = gateway.session("x1", STRATEGY_ID, ACCOUNT_ID);
                TestSession second = gateway.session("x2", OTHER_STRATEGY_ID, SECOND_ACCOUNT_ID)) {
            first.order(1);
            check(await(() -> first.count(ExecType.NEW) == 1), "a bid on the first account acknowledged");
            second.order(1, Side.Ask, price);
            check(await(() -> second.answered() == 1), "the crossing ask on the second account answered");
            check(selfTrade(second, 1), "an ask on another account against the resting bid refused SELF_TRADE");
        }
    }

    private static boolean selfTrade(final TestSession session, final long counter) {
        return refusedSelfTrade(session, counter) && session.count(ExecType.NEW, counter) == 0;
    }

    private static boolean refusedSelfTrade(final TestSession session, final long counter) {
        return session.reports().stream()
                .anyMatch(r -> r.getClientOidCounter() == counter
                        && r.decoder.execType() == ExecType.REJECT
                        && r.decoder.rejectReason() == RejectReason.SELF_TRADE);
    }

    /**
     * Kills behind the OMS, from a kills file as the registry would return them: one strategy's kill cancels and
     * refuses only its orders, leaving another strategy's on the same listing; an account kill then clears the rest.
     */
    private void kill() throws Exception {
        try (Gateway gateway = new Gateway(null, 0, false);
                TestSession killed = gateway.session("k1", STRATEGY_ID);
                TestSession other = gateway.session("k2", OTHER_STRATEGY_ID)) {
            killed.order(1);
            other.order(1);
            check(await(() -> killed.count(ExecType.NEW) == 1 && other.count(ExecType.NEW) == 1), "both acknowledged");
            final String killedId = killed.exchangeOrderId(1);
            final String otherId = other.exchangeOrderId(1);
            check(awaitFor(() -> resting(List.of(killedId, otherId)) == 2, WAIT_NANOS), "both rest at the venue");

            gateway.fixture.kills("[{\"policy_type\":\"KILL_SWITCH\",\"strategy_id\":" + STRATEGY_ID + "}]");
            check(awaitGone(List.of(killedId), WAIT_NANOS), "the killed strategy's order cancelled");
            check(resting(List.of(otherId)) == 1, "the other strategy's order on the same listing untouched");
            killed.order(2);
            check(
                    await(() -> killed.reports().stream()
                            .anyMatch(r ->
                                    r.getClientOidCounter() == 2 && r.decoder.rejectReason() == RejectReason.HALTED)),
                    "the killed strategy's new order refused HALTED");

            gateway.fixture.kills("[{\"policy_type\":\"KILL_SWITCH\",\"account_id\":" + ACCOUNT_ID + "}]");
            check(awaitGone(List.of(otherId), WAIT_NANOS), "an account kill clears every order on it");
            gateway.fixture.kills("[]");
        }
    }

    /**
     * The gateway gone with orders resting: the deployed EmergencyCancel, with this run's AWS credentials, clears the
     * account on the venue with nothing of the gateway's.
     */
    private void emergency() throws Exception {
        final List<String> ids;
        try (Gateway gateway = new Gateway(null, 0, false);
                TestSession session = gateway.session("e")) {
            session.order(1);
            session.order(2);
            check(await(() -> session.count(ExecType.NEW) == 2), "both orders acknowledged");
            ids = List.of(session.exchangeOrderId(1), session.exchangeOrderId(2));
            check(awaitFor(() -> resting(ids) == 2, WAIT_NANOS), "both rest at the venue");
            // Gone as a crash would take it: nothing cancelled. The session is closed with it, before its timeout.
        }
        final String exchange =
                switch (this.venue) {
                    case KALSHI -> "KALSHI";
                    case POLYMARKET_US -> "POLYMARKET_US";
                    case POLYMARKET_INTL -> "POLYMARKET_INTL";
                };
        // Where the exchange trades from (the registry's sm.exchange.region), as the registry's route would start it.
        final Region region = Region.of(required("emergency.region"));
        final String payload = "{\"exchange\":\"" + exchange + "\",\"secretName\":\"" + this.account.tradingSecretName()
                + "\",\"reason\":\"exit harness " + this.run + "\",\"actor\":\"exit-harness\"}";
        try (LambdaClient lambda = LambdaClient.builder()
                .region(region)
                .credentialsProvider(this.credentials)
                .build()) {
            final InvokeResponse answer = lambda.invoke(InvokeRequest.builder()
                    .functionName("EmergencyCancel")
                    .payload(SdkBytes.fromUtf8String(payload))
                    .build());
            final String result = answer.payload().asUtf8String();
            System.out.println("   EmergencyCancel: " + result);
            check(answer.functionError() == null, "EmergencyCancel ran");
        }
        check(awaitGone(ids, WAIT_NANOS), "the account's orders gone from the venue");
    }

    /**
     * Many sessions saturating the account: the venue never answers 429 at the gateway's share of its limits, and each
     * session's share of what the venue took follows its weight.
     */
    private void load() throws Exception {
        final int count = Integer.getInteger("load.sessions", 30);
        final long seconds = Long.getLong("load.seconds", 120);
        // Enough that a session of the heaviest weight always has one waiting despite the venue's round trips: fair
        // share can only divide the budget among sessions with something queued.
        final int quotes = Integer.getInteger("load.quotes", 12);
        // How long each session's orders may wait for the budget. Where the venue's slots are further apart than this,
        // a session's queued orders all expire between them and it has nothing queued while it sends them again: its
        // share then follows how often it is queued at a slot, not its weight.
        final int holdMs = Integer.getInteger("load.hold.ms", 50);
        if (this.venue == GatewayVenue.KALSHI) {
            checkKalshiTier();
        }
        final LatencyHistogram cancelWaits = new LatencyHistogram();
        final long[] sent = new long[RequestClass.values().length];
        // By session, from its slot less the first: new orders sent, and refused for waiting too long (so it had them
        // queued), to tell a session starved while queued from one with nothing waiting.
        final long[] firstSlotOf = {Long.MIN_VALUE};
        final AtomicLongArray newSent = new AtomicLongArray(count);
        final AtomicLongArray newExpired = new AtomicLongArray(count);
        final AccountLane.Admissions tap = new AccountLane.Admissions() {
            @Override
            public void decided(
                    final long sessionSlot,
                    final long clientOidCounter,
                    final RequestClass type,
                    final AdmissionAction action,
                    final long queuedNanos,
                    final long tokensLeft) {
                final long session = sessionSlot - firstSlotOf[0];
                if (type == RequestClass.NEW && session >= 0 && session < count) {
                    if (action == AdmissionAction.SENT) {
                        newSent.incrementAndGet((int) session);
                    } else if (action == AdmissionAction.REJECTED_RATE_LIMITED) {
                        newExpired.incrementAndGet((int) session);
                    }
                }
                if (action == AdmissionAction.SENT) {
                    sent[type.ordinal()]++;
                    if (type == RequestClass.CANCEL) {
                        cancelWaits.record(TimeUnit.NANOSECONDS.toMicros(queuedNanos));
                    }
                }
            }
        };
        final GatewayAccount account = buildAccount(new GatewaySettings("live:exit:main", 4096, 12));
        // -Dload.trace=<file>: every step of the lane's fair share, written there after the run.
        final String traceFile = System.getProperty("load.trace");
        final FairShareTrace trace = traceFile == null ? null : account.lane().traceFairShare(1_500_000);
        final long firstSlot = this.nextSlot;
        firstSlotOf[0] = firstSlot;
        this.nextSlot += count;
        final int[] weights = new int[count];
        for (int i = 0; i < count; i++) {
            weights[i] = i % 3 + 1;
            this.registry.register(
                    "x" + this.run + "load" + i,
                    firstSlot + i,
                    STRATEGY_ID,
                    EXCHANGE_ID,
                    LISTING_ID,
                    ACCOUNT_ID,
                    weights[i]);
        }
        try (Gateway gateway = new Gateway(account, tap)) {
            final LoadSessions load = new LoadSessions(
                    this.sessionAeron,
                    new LoadSessions.Spec(
                            "x" + this.run + "load",
                            firstSlot,
                            STRATEGY_ID,
                            "live:exit:main",
                            EXCHANGE_ID,
                            SECURITY_ID,
                            ACCOUNT_ID,
                            this.price,
                            this.size,
                            quotes,
                            TimeUnit.MILLISECONDS.toNanos(50),
                            holdMs,
                            "127.0.0.1",
                            PORT),
                    count,
                    report -> {
                        final String id = exchangeOrderId(report);
                        if (!id.isEmpty()) {
                            this.placed.add(id);
                        }
                    });
            try {
                check(await(load::allReady), "every session welcomed");
                load.startQuoting();
                Thread.sleep(TimeUnit.SECONDS.toMillis(10));
                final long[] before = acks(load);
                final long[] sentBefore = sent.clone();
                Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
                final long[] after = acks(load);
                final long[] sentAfter = sent.clone();
                load.stopQuoting();
                final boolean ended = awaitFor(() -> load.working() == 0, TimeUnit.SECONDS.toNanos(60));
                if (!ended) {
                    // By session slot, each order still working by counter: 0 unacknowledged, -1 cancel sent.
                    System.out.println("   still working: " + load.describeWorking());
                    System.out.println(
                            "   lane holds " + account.lane().workingOrders() + "; " + gateway.fixture.lastMinute());
                    System.out.print("   lane:\n" + account.lane().describeWorking());
                    System.out.print("   reader holds:\n" + account.reader().describeHeld());
                }
                check(ended, "every order ended");
                double worst = 0;
                long total = 0;
                long weightSum = 0;
                for (int i = 0; i < count; i++) {
                    total += after[i] - before[i];
                    weightSum += weights[i];
                }
                final double[] offByWeight = new double[4];
                for (int i = 0; i < count; i++) {
                    final double expected = (double) total * weights[i] / weightSum;
                    final double off = (after[i] - before[i]) / expected - 1;
                    worst = Math.max(worst, Math.abs(off));
                    offByWeight[weights[i]] += off / ((count + 3 - weights[i]) / 3);
                }
                System.out.println("   sessions heard: " + load.describeHearing());
                for (int w = 1; w <= 3; w++) {
                    long weightSent = 0;
                    long weightExpired = 0;
                    int sessions = 0;
                    for (int i = 0; i < count; i++) {
                        if (weights[i] == w) {
                            weightSent += newSent.get(i);
                            weightExpired += newExpired.get(i);
                            sessions++;
                        }
                    }
                    System.out.printf(
                            "   weight %d, per session over the run: %d new orders sent, %d refused for waiting%n",
                            w, weightSent / Math.max(1, sessions), weightExpired / Math.max(1, sessions));
                }
                System.out.printf(
                        "   share off by weight, on average: w1 %+.1f%%, w2 %+.1f%%, w3 %+.1f%%%n",
                        100 * offByWeight[1], 100 * offByWeight[2], 100 * offByWeight[3]);
                System.out.printf(
                        "   %d s: %.1f orders acknowledged/s; sent/s new %.1f, amend %.1f, cancel %.1f, catch-up %.1f;"
                                + " cancel wait p99 %d us, max %d us; worst share off %.1f%%; venue 429s %d%n"
                                + "   refusals %s%n   lane: %s%n",
                        seconds,
                        total / (double) seconds,
                        (sentAfter[RequestClass.NEW.ordinal()] - sentBefore[RequestClass.NEW.ordinal()])
                                / (double) seconds,
                        (sentAfter[RequestClass.AMEND.ordinal()] - sentBefore[RequestClass.AMEND.ordinal()])
                                / (double) seconds,
                        (sentAfter[RequestClass.CANCEL.ordinal()] - sentBefore[RequestClass.CANCEL.ordinal()])
                                / (double) seconds,
                        (sentAfter[RequestClass.CATCH_UP.ordinal()] - sentBefore[RequestClass.CATCH_UP.ordinal()])
                                / (double) seconds,
                        cancelWaits.percentileMicros(0.99),
                        cancelWaits.maxMicros(),
                        100 * worst,
                        account.venueRefusals(),
                        refusals(load),
                        gateway.fixture.lastMinute());
                if (account.venueRefusals() > 0) {
                    System.out.print("   " + account.describeRefusals().replace("\n", "\n   "));
                    System.out.println();
                }
                check(account.venueRefusals() == 0, "the venue never answered 429");
                check(worst <= 0.10, "every session's share within 10% of its weight's");
            } finally {
                load.close();
                if (trace != null) {
                    trace.writeTo(Path.of(traceFile));
                    System.out.printf("   fair-share trace: %d steps to %s%n", trace.size(), traceFile);
                }
            }
        }
    }

    private static long[] acks(final LoadSessions load) {
        final long[] acks = new long[load.sessions().size()];
        for (int i = 0; i < acks.length; i++) {
            acks[i] = load.sessions().get(i).acks;
        }
        return acks;
    }

    private static String refusals(final LoadSessions load) {
        final StringBuilder text = new StringBuilder();
        for (final RejectReason reason : RejectReason.values()) {
            long total = 0;
            for (final LoadSessions.Session session : load.sessions()) {
                total += session.rejects(reason);
            }
            if (total > 0) {
                text.append(reason).append('=').append(total).append(' ');
            }
        }
        return text.toString().trim();
    }

    /**
     * Kalshi's limits are the account's tier (Kalshi's demo puts accounts on Basic), so the gateway's configured limits
     * must not be above what the account says it may send, or the venue would answer 429 for want of the tier, not of
     * the gateway's budget.
     */
    private void checkKalshiTier() throws Exception {
        final KalshiCredentials creds = KalshiCredentials.fromJson(secret(this.account.tradingSecretName()));
        final KalshiAuthSigner signer = new KalshiAuthSigner(creds.apiKey(), creds.privateKey());
        final String path = "/trade-api/v2/account/limits";
        signer.sign(System.currentTimeMillis(), "GET", path);
        final java.net.http.HttpResponse<String> answer = java.net.http.HttpClient.newHttpClient()
                .send(
                        java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create(this.properties.getStringProperty("kalshi.api.url") + path))
                                .header("KALSHI-ACCESS-KEY", signer.apiKey())
                                .header("KALSHI-ACCESS-TIMESTAMP", signer.timestamp())
                                .header("KALSHI-ACCESS-SIGNATURE", signer.signature())
                                .GET()
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
        System.out.printf("   account limits: %d %s%n", answer.statusCode(), answer.body());
        check(answer.statusCode() == 200, "the account's limits read");
        final com.fasterxml.jackson.databind.JsonNode write = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(answer.body())
                .path("write");
        final Map<String, String> configured = this.properties.getPropertiesByPrefix("kalshi.ratelimit.");
        final double rate = Double.parseDouble(configured.getOrDefault("write.per.second", "300"));
        final double burst = Double.parseDouble(configured.getOrDefault("write.burst", "900"));
        System.out.printf("   gateway's write limits: %.0f a second, %.0f at once%n", rate, burst);
        check(
                write.path("refill_rate").asDouble() >= rate
                        && write.path("bucket_capacity").asDouble() >= burst,
                "the gateway's write limits are within the account's tier");
    }

    // ----- the gateway and its sessions -----

    /** A gateway on the real account, on the harness's port. */
    private final class Gateway implements AutoCloseable {
        final GatewayFixture fixture;
        private boolean closed;

        Gateway(final Path journal, final int ring, final boolean paused) throws Exception {
            this(Files.createTempDirectory("exit-state"), journal, ring, paused);
        }

        /** On {@code account}, built for the scenario, with {@code tap} hearing every decision its lane makes. */
        Gateway(final GatewayAccount account, final AccountLane.Admissions tap) throws Exception {
            this.fixture = GatewayFixture.start(
                    "exit-gateway",
                    PORT,
                    new GatewayFixture.Setup(
                            List.of(account),
                            List.of(listing),
                            registry,
                            Files.createTempDirectory("exit-state"),
                            Files.createTempDirectory("exit-journal"),
                            1 << 16,
                            false,
                            tap));
        }

        /** On both of the run's accounts (-Dsecret and -Dsecret2), one cross index over them. */
        Gateway(final VenueAccount first, final VenueAccount second) throws Exception {
            final CrossKeys crossKeys = new CrossKeys();
            final GatewaySettings settings = new GatewaySettings("live:exit:main", 256, 4);
            this.fixture = GatewayFixture.start(
                    "exit-gateway",
                    PORT,
                    new GatewayFixture.Setup(
                            List.of(
                                    buildAccount(first, settings, crossKeys),
                                    buildAccount(second, settings, crossKeys)),
                            List.of(listing),
                            registry,
                            Files.createTempDirectory("exit-state"),
                            null,
                            0,
                            false,
                            AccountLane.Admissions.NONE));
        }

        Gateway(final Path state, final Path journal, final int ring, final boolean paused) throws Exception {
            this.fixture = GatewayFixture.start(
                    "exit-gateway",
                    PORT,
                    new GatewayFixture.Setup(
                            List.of(buildAccount()),
                            List.of(listing),
                            registry,
                            state,
                            journal,
                            ring,
                            paused,
                            AccountLane.Admissions.NONE));
        }

        TestSession session(final String name) throws InterruptedException {
            return session(name, STRATEGY_ID);
        }

        TestSession session(final String name, final int strategyId) throws InterruptedException {
            return session(name, strategyId, ACCOUNT_ID);
        }

        TestSession session(final String name, final int strategyId, final int accountId) throws InterruptedException {
            final TestSession session = new TestSession("x" + run + name, nextSlot++, strategyId, accountId);
            registry.register(session.sessionId, session.slot, strategyId, EXCHANGE_ID, LISTING_ID, accountId);
            session.start();
            if (!await(() -> session.link.state == GatewayLink.READY)) {
                try {
                    session.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                throw new AssertionError("session " + name + " never welcomed: " + session.link.refusal);
            }
            return session;
        }

        /** Stops it as a crash would: nothing cancelled, nobody told. */
        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                this.fixture.kill();
            }
        }
    }

    /** One session's OMS buffers and its gateway client, on threads of their own. */
    private final class TestSession implements AutoCloseable {
        final String sessionId;
        final long slot;
        final int strategyId;
        final GatewayLink link;
        private final GatewayClient client;
        private final GatewayLinks links;
        private final SequencedRingBuffer<Intent> omsOrders;
        private final List<OrderExecutionReport> received = new CopyOnWriteArrayList<>();
        private final SequencedPoller omsReader;
        private GnomeAgentRunner runner;
        private java.util.concurrent.ScheduledFuture<?> tending;

        TestSession(final String sessionId, final long slot, final int strategyId, final int accountId) {
            this.sessionId = sessionId;
            this.slot = slot;
            this.strategyId = strategyId;
            final GlobalSequence sequence = new GlobalSequence();
            this.omsOrders = new SequencedRingBuffer<>(Intent::new, sequence);
            final SequencedRingBuffer<OrderExecutionReport> toOms =
                    new SequencedRingBuffer<>(OrderExecutionReport::new, sequence);
            this.omsReader = toOms.createPoller((seq, templateId, buffer, length) -> {
                final OrderExecutionReport report = new OrderExecutionReport();
                report.buffer.putBytes(0, buffer, 0, length);
                this.received.add(report);
                final String id = GatewayExitHarness.exchangeOrderId(report);
                if (!id.isEmpty()) {
                    placed.add(id);
                }
            });
            this.link = new GatewayLink(
                    "live:exit:main",
                    EXCHANGE_ID,
                    accountId,
                    List.<long[]>of(new long[] {EXCHANGE_ID, SECURITY_ID}),
                    64);
            this.client = new GatewayClient(
                    List.of(this.link),
                    this.omsOrders,
                    toOms,
                    new GatewayClient.Identity(slot, sessionId, strategyId, COD_MILLIS, 2_000),
                    64,
                    System::nanoTime,
                    new SystemEpochNanoClock());
            this.links = new GatewayLinks(
                    new NullLogger(),
                    sessionAeron.aeron(),
                    "127.0.0.1",
                    List.of(this.link),
                    key -> new GatewayLinks.Endpoint("127.0.0.1", PORT),
                    System::nanoTime);
        }

        void start() {
            this.tending = linkThread.scheduleWithFixedDelay(this.links, 0, 50, TimeUnit.MILLISECONDS);
            this.runner = new GnomeAgentRunner(this.client, Throwable::printStackTrace);
            GnomeAgentRunner.startOnThread(this.runner);
        }

        void order(final long counter) {
            order(counter, Side.Bid, price);
        }

        /** A post-only order: one that would take is refused by the venue, so a test of crossing fills nothing. */
        void order(final long counter, final Side side, final long at) {
            final Order order = new Order();
            order.encoder
                    .exchangeId(EXCHANGE_ID)
                    .securityId(SECURITY_ID)
                    .price(at)
                    .size(size)
                    .side(side)
                    .orderType(OrderType.LIMIT)
                    .timeInForce(TimeInForce.GOOD_TILL_CANCELED);
            order.encoder.flags().postOnly(true);
            order.encodeClientOid(counter, this.strategyId);
            this.omsOrders.publishRaw(order.buffer, OrderDecoder.TEMPLATE_ID, order.totalMessageSize());
        }

        void cancel(final long counter) {
            final CancelOrder cancel = new CancelOrder();
            cancel.encoder.exchangeId(EXCHANGE_ID).securityId(SECURITY_ID);
            cancel.encodeClientOid(counter, this.strategyId);
            this.omsOrders.publishRaw(cancel.buffer, CancelOrderDecoder.TEMPLATE_ID, cancel.totalMessageSize());
        }

        List<OrderExecutionReport> reports() {
            try {
                this.omsReader.poll();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return this.received;
        }

        long count(final ExecType type) {
            return reports().stream().filter(r -> r.decoder.execType() == type).count();
        }

        long count(final ExecType type, final long counter) {
            return reports().stream()
                    .filter(r -> r.decoder.execType() == type && r.getClientOidCounter() == counter)
                    .count();
        }

        /** Reports that settle a request: acknowledgements, cancels and refusals. */
        long answered() {
            return reports().stream()
                    .filter(r -> r.decoder.execType() != ExecType.FILL && r.decoder.execType() != ExecType.PARTIAL_FILL)
                    .count();
        }

        String exchangeOrderId(final long counter) {
            return reports().stream()
                    .filter(r -> r.getClientOidCounter() == counter)
                    .map(GatewayExitHarness::exchangeOrderId)
                    .filter(id -> !id.isEmpty())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no venue id for order " + counter));
        }

        /** Stops as a killed session would: no goodbye. */
        @Override
        public void close() throws Exception {
            if (this.tending != null) {
                this.tending.cancel(false);
            }
            if (this.runner != null) {
                this.runner.close();
                this.runner = null;
                this.links.close();
            }
        }
    }

    // ----- the venue -----

    private GatewayAccount buildAccount() throws Exception {
        return buildAccount(new GatewaySettings("live:exit:main", 256, 4));
    }

    private GatewayAccount buildAccount(final GatewaySettings settings) throws Exception {
        return buildAccount(this.account, settings, new CrossKeys());
    }

    /** One account of a gateway; every account of one gateway shares its cross keys, so the index spans them. */
    private GatewayAccount buildAccount(
            final VenueAccount which, final GatewaySettings settings, final CrossKeys crossKeys) throws Exception {
        return new VenueAccounts(new VenueAccounts.Context(
                        new ConsoleLogger(new SystemEpochNanoClock()),
                        this.properties,
                        this::secret,
                        settings,
                        SystemEpochClock.INSTANCE,
                        new SystemEpochNanoClock(),
                        (status, cause) -> {
                            throw new IllegalStateException("The gateway gave up: " + cause, cause);
                        },
                        crossKeys))
                .build(this.venue, which);
    }

    /** An account never started, for its venue order lookups only. */
    private GatewayAccount queries() throws Exception {
        if (this.queries == null) {
            this.queries = buildAccount();
        }
        return this.queries;
    }

    /** Each account this run trades on, to read and clean up its orders. */
    private List<GatewayAccount> allQueries() throws Exception {
        if (this.secondAccount == null) {
            return List.of(queries());
        }
        if (this.secondQueries == null) {
            this.secondQueries =
                    buildAccount(this.secondAccount, new GatewaySettings("live:exit:main", 256, 4), new CrossKeys());
        }
        return List.of(queries(), this.secondQueries);
    }

    private String secret(final String name) {
        return this.secrets
                .getSecretValue(GetSecretValueRequest.builder().secretId(name).build())
                .secretString();
    }

    /**
     * Whether none of {@code ids} rests at the venue on two reads a second apart, within {@code nanos}: a venue's
     * listing can miss an order that still rests.
     */
    private boolean awaitGone(final List<String> ids, final long nanos) throws InterruptedException {
        final long deadline = System.nanoTime() + nanos;
        while (System.nanoTime() < deadline) {
            if (resting(ids) == 0) {
                Thread.sleep(1_000);
                if (resting(ids) == 0) {
                    return true;
                }
            }
            Thread.sleep(200);
        }
        return false;
    }

    /** How many of {@code ids} rest at the venue. */
    private long resting(final List<String> ids) {
        try {
            long resting = 0;
            for (final GatewayAccount account : allQueries()) {
                resting += account.orderQuery().listOpenOrders(this.listing).stream()
                        .filter(order -> ids.contains(order.exchangeOrderId()))
                        .count();
            }
            return resting;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Prints how many of the account's orders rest on the market and, when sweeping, cancels them all, whoever placed
     * them: for a run stopped before its cleanup.
     */
    private boolean listOrSweep(final boolean sweep) throws Exception {
        final VenueOrderQuery query = queries().orderQuery();
        final List<VenueOrder> open = query.listOpenOrders(this.listing);
        System.out.printf("   %d of the account's orders rest on this market%n", open.size());
        if (!sweep) {
            return true;
        }
        int cancelled = 0;
        for (final VenueOrder order : open) {
            if (query.cancel(this.listing, order)) {
                cancelled++;
            }
        }
        final int left = query.listOpenOrders(this.listing).size();
        System.out.printf("   cancelled %d; %d still rest%n", cancelled, left);
        return left == 0;
    }

    /** Cancels whatever this run left resting. */
    private void cleanUp() {
        int cancelled = 0;
        try {
            for (final GatewayAccount account : allQueries()) {
                final VenueOrderQuery query = account.orderQuery();
                for (final VenueOrder order : query.listOpenOrders(this.listing)) {
                    if (this.placed.contains(order.exchangeOrderId()) && query.cancel(this.listing, order)) {
                        cancelled++;
                    }
                }
            }
        } catch (Exception | LinkageError e) {
            System.out.println("   cleanup failed: " + e + "; check the venue for orders starting x" + this.run);
            return;
        }
        if (cancelled > 0) {
            System.out.printf("   cleanup cancelled %d left resting%n", cancelled);
        }
    }

    private static Listing listing(final GatewayVenue venue, final String market) {
        return switch (venue) {
            case KALSHI -> listing("KALSHI", market + ":yes", market);
            case POLYMARKET_US -> listing("POLYMARKET_US", market + ":long", market);
            case POLYMARKET_INTL -> listing("POLYMARKET", market, market.substring(0, market.indexOf(':')));
        };
    }

    private static Listing listing(final String exchange, final String exchangeSecurityId, final String symbol) {
        return new Listing(
                LISTING_ID,
                new Exchange(EXCHANGE_ID, exchange, exchange, "global"),
                new Security(
                        (int) SECURITY_ID, symbol, null, null, null, null, null, null, false, false, 0L, 0L, true, 0),
                exchangeSecurityId,
                symbol);
    }

    // ----- the journal -----

    /** Records in, records out and gaps, across every segment of the journal under {@code directory}. */
    private static int[] readJournal(final Path directory) throws Exception {
        final int[] counts = new int[3];
        final List<Path> segments;
        try (Stream<Path> files = Files.list(directory)) {
            segments = files.filter(file -> file.getFileName().toString().startsWith("segment-"))
                    .sorted()
                    .toList();
        }
        long previous = 0;
        for (final Path segment : segments) {
            final JournalReader reader = new JournalReader(segment, previous);
            reader.readAll(new JournalRecordHandler() {
                @Override
                public void onRecord(
                        final long sequence,
                        final int source,
                        final int templateId,
                        final UnsafeBuffer buffer,
                        final int length) {
                    counts[source == INGRESS ? 0 : 1]++;
                }

                @Override
                public void onGap(final long firstLost, final long lastLost) {
                    counts[2]++;
                }
            });
            previous = reader.lastReadSequence();
        }
        return counts;
    }

    // ----- plumbing -----

    private static String exchangeOrderId(final OrderExecutionReport report) {
        final byte[] bytes = new byte[OrderExecutionReportEncoder.exchangeOrderIdLength()];
        int length = 0;
        while (length < bytes.length && report.decoder.exchangeOrderId(length) != 0) {
            bytes[length] = report.decoder.exchangeOrderId(length);
            length++;
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }

    private void check(final boolean ok, final String what) {
        System.out.printf("   %s %s%n", ok ? "ok  " : "FAIL", what);
        if (!ok) {
            this.failures.add(what);
            // Later checks would build on this one, and could pass with nothing having happened.
            throw new CheckFailed();
        }
    }

    private static boolean await(final BooleanSupplier condition) throws InterruptedException {
        return awaitFor(condition, WAIT_NANOS);
    }

    private static boolean awaitFor(final BooleanSupplier condition, final long nanos) throws InterruptedException {
        final long deadline = System.nanoTime() + nanos;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }

    private static long scaled(final String value, final long scale) {
        return new BigDecimal(value).multiply(BigDecimal.valueOf(scale)).longValueExact();
    }

    private static String required(final String property) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("-D" + property + " is required");
        }
        return value;
    }
}
