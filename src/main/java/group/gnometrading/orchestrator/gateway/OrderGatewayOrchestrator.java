package group.gnometrading.orchestrator.gateway;

import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.account.GatewayAccounts;
import group.gnometrading.account.VenueAccount;
import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.logging.AsyncLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronPathIdle;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.AeronStreamIds;
import group.gnometrading.orchestrator.aeron.HostAddress;
import group.gnometrading.orchestrator.aeron.ServiceEndpoint;
import group.gnometrading.orchestrator.aeron.ServiceEndpoints;
import group.gnometrading.orchestrator.aws.AwsModule;
import group.gnometrading.orchestrator.aws.RegistryEndpoint;
import group.gnometrading.orchestrator.aws.SecretCache;
import group.gnometrading.orchestrator.aws.SecurityMasterModule;
import group.gnometrading.orchestrator.di.Named;
import group.gnometrading.orchestrator.di.Orchestrator;
import group.gnometrading.orchestrator.di.Provides;
import group.gnometrading.orchestrator.di.Singleton;
import group.gnometrading.orchestrator.gateway.accounts.GatewayAccount;
import group.gnometrading.orchestrator.gateway.accounts.GatewaySettings;
import group.gnometrading.orchestrator.gateway.accounts.LaneAdmissions;
import group.gnometrading.orchestrator.gateway.accounts.LaneLiveness;
import group.gnometrading.orchestrator.gateway.accounts.VenueAccounts;
import group.gnometrading.orchestrator.gateway.control.GatewayControl;
import group.gnometrading.orchestrator.gateway.control.GatewayKills;
import group.gnometrading.orchestrator.gateway.control.KillSource;
import group.gnometrading.orchestrator.gateway.control.SessionRegistry;
import group.gnometrading.orchestrator.gateway.core.AccountLink;
import group.gnometrading.orchestrator.gateway.core.CrossKeys;
import group.gnometrading.orchestrator.gateway.core.GatewayAlerts;
import group.gnometrading.orchestrator.gateway.core.GatewayMessage;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.GatewayStatus;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.orchestrator.gateway.core.Kills;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import group.gnometrading.orchestrator.gateway.journal.GatewayJournal;
import group.gnometrading.orchestrator.gateway.journal.OwnershipLog;
import group.gnometrading.orchestrator.gateway.transport.CrossIndex;
import group.gnometrading.orchestrator.gateway.transport.GatewayEgress;
import group.gnometrading.orchestrator.gateway.transport.GatewayIngress;
import group.gnometrading.orchestrator.gateway.transport.GatewayRejects;
import group.gnometrading.orchestrator.runtime.AgentRuntimeInstaller;
import group.gnometrading.orchestrator.runtime.AlertsApi;
import group.gnometrading.orchestrator.runtime.BoundedCalls;
import group.gnometrading.orchestrator.runtime.DaemonThreads;
import group.gnometrading.orchestrator.runtime.HealthCheckServer;
import group.gnometrading.orchestrator.runtime.ProcessExit;
import group.gnometrading.resources.Properties;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.fee.FeeTimeline;
import group.gnometrading.utils.CauseChain;
import io.aeron.Subscription;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * An order gateway ({@code GATEWAY_KEY}): one process per venue holding that venue's trading accounts for every live
 * session, so the accounts are traded from one place. Builds and starts each account the registry assigned it, then
 * takes sessions over Aeron on its ingress port: {@link GatewayIngress} in, {@link GatewayEgress} out, and
 * {@link GatewayControl} for hellos, liveness and its endpoint record. On SIGTERM it stops as planned: every session's
 * orders cancelled and a goodbye sent before anything closes.
 */
public class OrderGatewayOrchestrator extends Orchestrator {

    private static final int HEALTH_PORT = 8080;
    private static final int CONTROL_QUEUE = 1024;
    private static final int REJECT_QUEUE = 4096;
    // Of ECS's stop timeout: this long for the lanes to empty after every order is cancelled.
    private static final long STOP_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    // How long a failing gateway waits for its alert to be taken before it halts.
    private static final long FATAL_ALERT_WAIT_MILLIS = 2_000;
    private static final long CONTROL_STALL_NANOS = TimeUnit.MINUTES.toNanos(1);
    // How long the control thread waits on the registry for one record or listing.
    private static final long REGISTRY_TIMEOUT_MILLIS = 5_000;
    // How long sessions have to come back and claim, through their hellos, orders an earlier process left.
    private static final long SWEEP_WAIT_MILLIS = 30_000;

    @Provides
    public final EpochNanoClock provideEpochNanoClock() {
        return new SystemEpochNanoClock();
    }

    @Provides
    public final EpochClock provideEpochClock() {
        return SystemEpochClock.INSTANCE;
    }

    @Provides
    @Singleton
    public final Logger provideLogger(final EpochNanoClock clock) {
        return new AsyncLogger(clock);
    }

    /** The service-endpoints table, in the control plane's one region wherever the gateway runs. */
    @Provides
    @Singleton
    @Named("ENDPOINTS")
    public final DynamoDbClient provideEndpointsDynamo(@Named("AWS_PROFILE") final String awsProfile) {
        final var builder = DynamoDbClient.builder()
                .region(Region.of(ServiceEndpoints.REGION))
                // A write that can't land soon is overtaken by the next anyway.
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(5))
                        .apiCallAttemptTimeout(Duration.ofSeconds(2))
                        .build());
        if (awsProfile != null && !awsProfile.isEmpty()) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(awsProfile));
        }
        return builder.build();
    }

    @Override
    public final void configure() {
        install(new SecurityMasterModule(), new AwsModule());
        final Logger logger = getInstance(Logger.class);
        final Properties properties = getInstance(Properties.class);
        final AeronPathIdle idle = AeronPathIdle.forGateway(properties);
        AgentRuntimeInstaller.installStandard(logger, idle);

        // Before any account can send: the startup sweep tells an earlier process's orders by being placed before this.
        final long startedMillis = System.currentTimeMillis();
        final GatewaySettings settings = GatewaySettings.of(properties);
        final IngestGatewayAlerts alerts = alerts(logger, properties, settings);
        final ProcessExit exit = exit(logger, alerts);
        final CrossKeys crossKeys = new CrossKeys();
        final List<GatewayAccount> accounts = buildAccounts(settings, properties, logger, exit, crossKeys);
        final int crossCapacity = settings.maxOrders() * Math.max(1, accounts.size());
        final CrossIndex cross = new CrossIndex(
                crossKeys,
                CrossIndex.Mode.valueOf(
                        properties.getStringProperty("gateway.self.trade.mode").toUpperCase(Locale.ROOT)),
                crossCapacity,
                2 * crossCapacity);
        final String ip = HostAddress.privateIp();
        if (ip == null) {
            throw new IllegalStateException("No private IP for sessions to reach this gateway on");
        }
        final int port = properties.getIntProperty("gateway.ingress.port");
        final AeronRuntime aeron = AeronRuntime.launch(
                "gateway",
                AeronProfile.GATEWAY,
                idle,
                error -> logger.logf(LogMessage.UNKNOWN_ERROR, "Aeron error: %s", CauseChain.of(error)));
        final Subscription ingressSubscription =
                aeron.aeron().addSubscription(AeronChannels.gatewayIngress(ip, port), AeronStreamIds.GATEWAY_INGRESS);

        final EpochNanoClock wallClock = getInstance(EpochNanoClock.class);
        final GatewayStatus status = new GatewayStatus(wallClock.nanoTime());
        final SessionTable sessions = new SessionTable();
        final ManyToOneRingBuffer<GatewayMessage> toControl =
                new ManyToOneRingBuffer<>(GatewayMessage[]::new, GatewayMessage::new, CONTROL_QUEUE);
        final ManyToOneRingBuffer<GatewayMessage> toEgress =
                new ManyToOneRingBuffer<>(GatewayMessage[]::new, GatewayMessage::new, CONTROL_QUEUE);
        final ManyToOneRingBuffer<AccountReport> rejects =
                new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, REJECT_QUEUE);
        final List<ManyToOneRingBuffer<AccountReport>> reportRings = new ArrayList<>();
        final Map<Integer, AccountLink> links = new HashMap<>();
        for (final GatewayAccount account : accounts) {
            reportRings.add(account.reports());
            links.put(account.accountId(), account);
        }
        reportRings.add(rejects);

        final GatewayJournal journal = journal(logger, properties, settings, wallClock, accounts.size());
        final Kills kills = new Kills();
        final List<LaneAdmissions> admissions = admissions(accounts, journal);
        final GatewayIngress ingress = new GatewayIngress(
                ingressSubscription,
                sessions,
                status,
                new GatewayRejects(rejects, wallClock),
                toControl,
                System::nanoTime,
                journal == null ? JournalRecorder.NONE : journal.ingress(),
                cross,
                kills);
        final GatewayEgress egress = new GatewayEgress(
                sessions,
                status,
                reportRings,
                toEgress,
                System::nanoTime,
                wallClock,
                journal == null ? JournalRecorder.NONE : journal.egress(),
                cross);
        final BoundedCalls registryCalls = new BoundedCalls("gateway-registry");
        final SessionRegistry registry = sessionRegistry(registryCalls);
        final GatewayControl.Listings listings = listings(registryCalls, getInstance(SecurityMaster.class));
        final GatewayRestart.Result restart =
                restart(logger, properties, settings, accounts, listings, registry, cross);
        final GatewayControl control = new GatewayControl(
                logger,
                alerts,
                sessions,
                status,
                links,
                toControl,
                toEgress,
                registry,
                listings,
                GatewayControl.Replies.over(aeron.aeron()),
                advertiser(logger, settings, properties, ip, port),
                System::nanoTime,
                properties.getIntProperty("gateway.replay.reports"),
                System::currentTimeMillis,
                new GatewayControl.Restart(
                        restart.sessionsFile(),
                        restart.sessions(),
                        GatewayControl.Restart.HELLO_GRACE_NANOS,
                        restart.loaded()));

        for (final GatewayAccount account : accounts) {
            account.start();
            logger.logf(
                    LogMessage.DEBUG,
                    "Gateway %s: started %s (%s)",
                    settings.key(),
                    account.name(),
                    account.account().label());
        }
        final GnomeAgentRunner journalRunner = journal == null
                ? null
                : start(
                        journal,
                        error -> logger.logf(
                                LogMessage.UNKNOWN_ERROR, "Gateway journal failed: %s", CauseChain.of(error)));
        final GnomeAgentRunner egressRunner = start(egress, fatal(logger, exit, "egress"));
        final GnomeAgentRunner ingressRunner = start(ingress, fatal(logger, exit, "ingress"));
        final GnomeAgentRunner controlRunner = start(
                control,
                error -> logger.logf(LogMessage.UNKNOWN_ERROR, "Gateway control failed: %s", CauseChain.of(error)));
        logger.logf(LogMessage.DEBUG, "Gateway %s: taking sessions on %s:%d", settings.key(), ip, port);
        reportAdmissions(logger, alerts, accounts, admissions);
        pollKills(logger, alerts, properties, kills, sessions, getInstance(SecurityMaster.class));
        reportDrops(logger, ingress, egress, sessions, cross);
        reportSelfTrades(logger, cross);
        sweepAtStartup(logger, alerts, accounts, restart, startedMillis);
        final Thread watchdog = watchdog(logger, properties, exit, ingressRunner, egressRunner, control, accounts)
                .start();

        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            // Threads are about to stop on purpose.
                            watchdog.interrupt();
                            stop(logger, control, controlRunner, ingressRunner, egressRunner, accounts, aeron);
                            restart.logs().forEach(OwnershipLog::close);
                            if (journalRunner != null) {
                                close(logger, journalRunner);
                                try {
                                    journal.close();
                                } catch (Exception e) {
                                    logger.logf(
                                            LogMessage.UNKNOWN_ERROR,
                                            "Failed to close the journal: %s",
                                            CauseChain.of(e));
                                }
                            }
                        },
                        "gateway-shutdown"));
        serveHealth(accounts);
    }

    /** Where the gateway raises its alerts: the alerts API the reconciler names, with the registry's key. */
    private IngestGatewayAlerts alerts(
            final Logger logger, final Properties properties, final GatewaySettings settings) {
        final String url =
                properties.hasProperty("alerts.api.url") ? properties.getStringProperty("alerts.api.url") : null;
        return new IngestGatewayAlerts(
                logger, AlertsApi.of(url, getInstance(RegistryEndpoint.class).apiKey()), settings.key());
    }

    /** Each lane's decisions, journaled when the journal is on; set before any lane starts. */
    private static List<LaneAdmissions> admissions(final List<GatewayAccount> accounts, final GatewayJournal journal) {
        final List<LaneAdmissions> admissions = new ArrayList<>();
        for (int i = 0; i < accounts.size(); i++) {
            final GatewayAccount account = accounts.get(i);
            final JournalRecorder recorder = journal == null ? JournalRecorder.NONE : journal.lane(i);
            final LaneAdmissions lane = new LaneAdmissions(account.accountId(), recorder, System::nanoTime);
            account.lane().admissions(lane);
            admissions.add(lane);
        }
        return admissions;
    }

    /**
     * Each minute, per account: what its lane sent and how long requests waited for the budget, and the venue's 429s,
     * which the budget exists to prevent. A minute with 429s, or with requests the venue never answered, raises an
     * alert for the account, resolved by the next minute without.
     */
    private static void reportAdmissions(
            final Logger logger,
            final GatewayAlerts alerts,
            final List<GatewayAccount> accounts,
            final List<LaneAdmissions> admissions) {
        final long[] refusalsSeen = new long[accounts.size()];
        final AccountAlert rateLimited = new AccountAlert(alerts, "GATEWAY_VENUE_RATE_LIMITED", accounts.size());
        final AccountAlert unanswered = new AccountAlert(alerts, "GATEWAY_ORDERS_UNANSWERED", accounts.size());
        DaemonThreads.scheduler("gateway-admissions")
                .scheduleAtFixedRate(
                        () -> {
                            final long now = System.nanoTime();
                            for (int i = 0; i < accounts.size(); i++) {
                                final GatewayAccount account = accounts.get(i);
                                final String minute = admissions.get(i).lastMinute(now);
                                final long refusals = account.venueRefusals();
                                final long newRefusals = refusals - refusalsSeen[i];
                                if (newRefusals > 0) {
                                    logger.logf(
                                            LogMessage.VENUE_RATE_LIMITED,
                                            "Gateway %s: the venue answered 429 %d times in the last minute (%d in all)",
                                            account.name(),
                                            newRefusals,
                                            refusals);
                                    refusalsSeen[i] = refusals;
                                }
                                rateLimited.minute(
                                        i,
                                        account,
                                        newRefusals,
                                        "the venue answered " + newRefusals + " requests with 429 in the last minute");
                                final long never = admissions.get(i).lastUnanswered(now);
                                unanswered.minute(
                                        i,
                                        account,
                                        never,
                                        never
                                                + " orders, amends or cancels the venue never answered in the last minute");
                                if (minute != null) {
                                    logger.logf(LogMessage.DEBUG, "Gateway %s: %s", account.name(), minute);
                                }
                            }
                        },
                        1,
                        1,
                        TimeUnit.MINUTES);
    }

    /**
     * Each minute, anything the gateway dropped since the last: none of it should happen, and each is invisible to
     * the sessions it would have reached.
     */
    private static void reportDrops(
            final Logger logger,
            final GatewayIngress ingress,
            final GatewayEgress egress,
            final SessionTable sessions,
            final CrossIndex cross) {
        final long[] seen = new long[8];
        DaemonThreads.scheduler("gateway-drops")
                .scheduleAtFixedRate(
                        () -> {
                            long untracked = 0;
                            for (final GatewaySession session : sessions.all()) {
                                untracked += session.totals.untracked();
                            }
                            final long[] now = {
                                ingress.unlinked(),
                                ingress.fragmented(),
                                ingress.controlFull(),
                                egress.unrouted(),
                                egress.unnumberedFull(),
                                untracked,
                                cross.noRoom(),
                                cross.lost()
                            };
                            if (!Arrays.equals(now, seen)) {
                                logger.logf(
                                        LogMessage.UNKNOWN_ERROR,
                                        "Gateway dropped, since the last minute: %d orders from unlinked images, %d"
                                                + " fragmented messages, %d session messages for a full control ring,"
                                                + " %d reports for no session, %d control messages for a full"
                                                + " session queue, %d orders untracked, so missing from a resync,"
                                                + " %d orders the self-trade index had no room for, %d order ends"
                                                + " lost to it, so those orders block others until restart",
                                        now[0] - seen[0],
                                        now[1] - seen[1],
                                        now[2] - seen[2],
                                        now[3] - seen[3],
                                        now[4] - seen[4],
                                        now[5] - seen[5],
                                        now[6] - seen[6],
                                        now[7] - seen[7]);
                                System.arraycopy(now, 0, seen, 0, seen.length);
                            }
                        },
                        1,
                        1,
                        TimeUnit.MINUTES);
    }

    /**
     * Reads the kill switches every second and enforces them ({@link GatewayKills}): from the registry, or from
     * {@code gateway.kills.file} when it is set, for runs against real venues that mustn't touch the registry's kills.
     */
    private void pollKills(
            final Logger logger,
            final GatewayAlerts alerts,
            final Properties properties,
            final Kills kills,
            final SessionTable sessions,
            final SecurityMaster securityMaster) {
        final KillSource source = properties.hasProperty("gateway.kills.file")
                ? KillSource.file(Path.of(properties.getStringProperty("gateway.kills.file")))
                : KillSource.over(getInstance(RegistryConnection.class));
        final GatewayKills gatewayKills = new GatewayKills(
                logger,
                alerts,
                source,
                kills,
                sessions,
                listingId -> {
                    try {
                        final Listing listing = securityMaster.getListing(listingId);
                        return listing == null
                                ? 0
                                : PublishedMap.listingKey(
                                        listing.exchange().exchangeId(),
                                        listing.security().securityId());
                    } catch (RuntimeException e) {
                        return 0;
                    }
                },
                System::nanoTime);
        DaemonThreads.scheduler("gateway-kills").scheduleWithFixedDelay(gatewayKills, 0, 1, TimeUnit.SECONDS);
    }

    /** Each minute, how many orders the self-trade check refused, or in shadow mode would have. */
    private static void reportSelfTrades(final Logger logger, final CrossIndex cross) {
        final long[] seen = new long[2];
        DaemonThreads.scheduler("gateway-self-trades")
                .scheduleAtFixedRate(
                        () -> {
                            final long refused = cross.refused();
                            final long shadowed = cross.shadowed();
                            if (refused != seen[0] || shadowed != seen[1]) {
                                logger.logf(
                                        LogMessage.DEBUG,
                                        "Gateway self-trade check, since the last minute: %d orders refused, %d"
                                                + " sent that would have been (shadow mode); %d orders held",
                                        refused - seen[0],
                                        shadowed - seen[1],
                                        cross.size());
                                seen[0] = refused;
                                seen[1] = shadowed;
                            }
                        },
                        1,
                        1,
                        TimeUnit.MINUTES);
    }

    private List<GatewayAccount> buildAccounts(
            final GatewaySettings settings,
            final Properties properties,
            final Logger logger,
            final ProcessExit exit,
            final CrossKeys crossKeys) {
        final VenueAccounts builder = new VenueAccounts(new VenueAccounts.Context(
                logger,
                properties,
                getInstance(SecretCache.class)::get,
                settings,
                getInstance(EpochClock.class),
                getInstance(EpochNanoClock.class),
                exit,
                crossKeys));
        final List<GatewayAccount> accounts = new ArrayList<>();
        for (final VenueAccount account :
                GatewayAccounts.fetch(getInstance(RegistryConnection.class), settings.key())) {
            try {
                accounts.add(builder.build(settings.venue(), account));
            } catch (Exception e) {
                throw new IllegalStateException("Can't build account " + account.label() + ": " + e.getMessage(), e);
            }
        }
        if (accounts.isEmpty()) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Gateway %s holds no accounts", settings.key());
        }
        return accounts;
    }

    /**
     * Cancels, once sessions have had time to come back, orders resting on the accounts that this process has no record
     * of ({@link StartupSweep}): 30 s, plus the longest cancel-on-disconnect timeout of the sessions the restart holds.
     */
    private static void sweepAtStartup(
            final Logger logger,
            final GatewayAlerts alerts,
            final List<GatewayAccount> accounts,
            final GatewayRestart.Result restart,
            final long startedMillis) {
        final List<StartupSweep.Account> swept = new ArrayList<>();
        for (final GatewayAccount account : accounts) {
            if (account.orderQuery() != null) {
                swept.add(new StartupSweep.Account(
                        account.name(),
                        account.orderQuery(),
                        restart.held().getOrDefault(account.accountId(), Set.of())));
            }
        }
        long longestCodNanos = 0;
        for (final GatewayControl.Recovered recovered : restart.sessions()) {
            longestCodNanos = Math.max(longestCodNanos, recovered.codTimeoutNanos());
        }
        final long waitMillis = SWEEP_WAIT_MILLIS + TimeUnit.NANOSECONDS.toMillis(longestCodNanos);
        final Thread thread = new Thread(
                new StartupSweep(
                        logger, alerts, swept, startedMillis, waitMillis, System::currentTimeMillis, Thread::sleep),
                "gateway-startup-sweep");
        thread.setDaemon(true);
        thread.start();
    }

    /** Takes back the previous process's sessions and orders, before any thread starts. */
    private static GatewayRestart.Result restart(
            final Logger logger,
            final Properties properties,
            final GatewaySettings settings,
            final List<GatewayAccount> accounts,
            final GatewayControl.Listings listings,
            final SessionRegistry registry,
            final CrossIndex cross) {
        final Path directory = Path.of(
                properties.getStringProperty("gateway.state.directory"),
                settings.key().replace(':', '-'));
        try {
            return GatewayRestart.recover(
                    logger,
                    directory,
                    (long) properties.getIntProperty("gateway.ownership.log.mb") * 1024L * 1024L,
                    accounts,
                    listings,
                    registry,
                    System.nanoTime(),
                    System.currentTimeMillis(),
                    cross);
        } catch (IOException e) {
            throw new IllegalStateException("Can't read the gateway's state in " + directory, e);
        }
    }

    /** Null when the journal is off. */
    private static GatewayJournal journal(
            final Logger logger,
            final Properties properties,
            final GatewaySettings settings,
            final EpochNanoClock wallClock,
            final int lanes) {
        if (!properties.getBooleanProperty("journal.enabled")) {
            return null;
        }
        // One directory per start, so a restart never writes over the segments of the run before.
        final Path directory = Path.of(
                properties.getStringProperty("journal.directory"),
                "gateway-" + settings.key().replace(':', '-'),
                Long.toString(TimeUnit.NANOSECONDS.toMillis(wallClock.nanoTime())));
        try {
            return new GatewayJournal(
                    logger,
                    directory,
                    (long) properties.getIntProperty("journal.segment.size.mb") * 1024L * 1024L,
                    TimeUnit.SECONDS.toNanos(properties.getIntProperty("journal.segment.interval.seconds")),
                    System::nanoTime,
                    lanes);
        } catch (IOException e) {
            throw new IllegalStateException("Can't open the gateway journal in " + directory, e);
        }
    }

    private static Watchdog watchdog(
            final Logger logger,
            final Properties properties,
            final ProcessExit exit,
            final GnomeAgentRunner ingress,
            final GnomeAgentRunner egress,
            final GatewayControl gatewayControl,
            final List<GatewayAccount> accounts) {
        final long stall = TimeUnit.MILLISECONDS.toNanos(properties.getLongProperty("gateway.watchdog.stall.millis"));
        final List<Watchdog.CheckIn> checkIns = new ArrayList<>();
        checkIns.add(new Watchdog.CheckIn("ingress", "gateway-ingress", ingress::cycles, () -> false, stall));
        checkIns.add(new Watchdog.CheckIn("egress", "gateway-egress", egress::cycles, () -> false, stall));
        // The control thread waits on the registry and the venue now and then, each within a deadline; stuck for a
        // minute, liveness and hellos have stopped.
        checkIns.add(new Watchdog.CheckIn(
                "control", "gateway-control", gatewayControl::progress, () -> false, CONTROL_STALL_NANOS));
        for (final GatewayAccount account : accounts) {
            checkIns.add(new Watchdog.CheckIn(
                    account.name() + " lane", account.name() + "-lane", account::lanePasses, () -> false, stall));
            // Every account's reader thread has the same name, so none is picked out for its stack.
            checkIns.add(new Watchdog.CheckIn(
                    account.name() + " reader", null, account::readerPasses, account::readerPaused, stall));
        }
        return new Watchdog(logger, checkIns, System::nanoTime, exit);
    }

    /** The sessions' registry records, asked on the control thread, so with a deadline (a miss falls back to its cache). */
    private SessionRegistry sessionRegistry(final BoundedCalls registryCalls) {
        final SessionRegistry.Source source = SessionRegistry.over(getInstance(RegistryConnection.class));
        return new SessionRegistry(
                sessionId -> registryCalls.call(() -> source.fetch(sessionId), REGISTRY_TIMEOUT_MILLIS));
    }

    /**
     * Listings from the security master, read on the control thread, so with a deadline. One that runs out throws rather
     * than answering none: no listing would read as a market that doesn't exist.
     */
    private static GatewayControl.Listings listings(
            final BoundedCalls registryCalls, final SecurityMaster securityMaster) {
        return new GatewayControl.Listings() {
            @Override
            public Listing listing(final int exchangeId, final long securityId) {
                return bounded(() -> securityMaster.getListing(exchangeId, (int) securityId));
            }

            @Override
            public FeeTimeline fees(final Listing listing) {
                return bounded(() -> securityMaster.getFeeTimeline(listing.listingId()));
            }

            private <T> T bounded(final Callable<T> call) {
                try {
                    return registryCalls.call(call, REGISTRY_TIMEOUT_MILLIS);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException("security master: " + e.getMessage(), e);
                }
            }
        };
    }

    /** Writes where sessions reach the gateway and its state, on a thread of its own; a failed write waits for the next. */
    private GatewayControl.Advertiser advertiser(
            final Logger logger,
            final GatewaySettings settings,
            final Properties properties,
            final String ip,
            final int port) {
        final ServiceEndpoints endpoints = new ServiceEndpoints(getInstance(DynamoDbClient.class, "ENDPOINTS"));
        final EpochNanoClock wallClock = getInstance(EpochNanoClock.class);
        final String region = System.getenv().getOrDefault("AWS_REGION", "unknown");
        final String instance = instance();
        final String version = properties.getStringProperty("orchestrator.version");
        return new EndpointWriter(
                        logger,
                        (state, linkedSessions, workingOrders) -> endpoints.put(new ServiceEndpoint(
                                ServiceEndpoint.Kind.ORDER_GATEWAY,
                                settings.key(),
                                instance,
                                region,
                                ip,
                                port,
                                state.name(),
                                version,
                                wallClock.nanoTime(),
                                linkedSessions,
                                workingOrders)))
                .start();
    }

    private static String instance() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * The planned stop: control's thread stopped, then every session's orders cancelled and a goodbye sent while
     * ingress and egress still run (cancels reach the lanes, reports and goodbyes the sessions), then everything closed,
     * the accounts last before the media driver.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private static void stop(
            final Logger logger,
            final GatewayControl control,
            final GnomeAgentRunner controlRunner,
            final GnomeAgentRunner ingressRunner,
            final GnomeAgentRunner egressRunner,
            final List<GatewayAccount> accounts,
            final AeronRuntime aeron) {
        close(logger, controlRunner);
        try {
            if (!control.stop(STOP_TIMEOUT_NANOS)) {
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Gateway stopping with orders still working or sessions not yet told");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        close(logger, ingressRunner);
        close(logger, egressRunner);
        for (final GatewayAccount account : accounts) {
            account.close();
        }
        aeron.close();
        logger.logf(LogMessage.DEBUG, "Gateway stopped");
    }

    private static GnomeAgentRunner start(final GnomeAgent agent, final ErrorHandler errors) {
        final GnomeAgentRunner runner = new GnomeAgentRunner(agent, errors);
        GnomeAgentRunner.startOnThread(runner);
        return runner;
    }

    private static void close(final Logger logger, final GnomeAgentRunner runner) {
        try {
            runner.close();
        } catch (Exception e) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Failed to stop %s: %s",
                    runner.getAgent().roleName(),
                    e);
        }
    }

    private static ErrorHandler fatal(final Logger logger, final ProcessExit exit, final String what) {
        return error -> {
            logger.logf(LogMessage.FATAL_ERROR_EXITING, "Gateway %s failed: %s", what, CauseChain.of(error));
            exit.exit(1, error);
        };
    }

    private static void serveHealth(final List<GatewayAccount> accounts) {
        final List<LaneLiveness> lanes = new ArrayList<>();
        for (final GatewayAccount account : accounts) {
            lanes.add(new LaneLiveness(account::lanePasses, System::nanoTime, LaneLiveness.STALL_NANOS));
        }
        try {
            new HealthCheckServer(HEALTH_PORT, () -> lanes.stream().allMatch(LaneLiveness::getAsBoolean)).start();
        } catch (IOException e) {
            throw new IllegalStateException("Can't serve health checks on " + HEALTH_PORT, e);
        }
    }

    /**
     * Logs why and halts, once, without the shutdown hook: its planned stop would cancel every order, while a gateway
     * that failed should leave them resting for its restart to take back (and a stuck thread could keep the planned stop
     * from ever finishing). On a thread of its own, so the failing agent's thread isn't the one halting.
     */
    private static ProcessExit exit(final Logger logger, final IngestGatewayAlerts alerts) {
        final AtomicBoolean exiting = new AtomicBoolean();
        // Each process's exit is its own alert, so one that keeps failing after restarts says so every time.
        final String process = Long.toString(System.currentTimeMillis());
        return (status, cause) -> {
            logger.logf(LogMessage.FATAL_ERROR_EXITING, "Gateway exiting: %s", CauseChain.of(cause));
            if (exiting.compareAndSet(false, true)) {
                new Thread(
                                () -> {
                                    final String reason = String.valueOf(CauseChain.of(cause));
                                    alerts.firingAndWait(
                                            "GATEWAY_FATAL",
                                            process,
                                            "Gateway exiting on an error: " + reason,
                                            Map.of("reason", reason),
                                            FATAL_ALERT_WAIT_MILLIS);
                                    if (logger instanceof AsyncLogger async) {
                                        async.close();
                                    }
                                    Runtime.getRuntime().halt(status);
                                },
                                "gateway-exit")
                        .start();
            }
        };
    }

    /** One alert type per account, raised for a minute that saw any and resolved by the next that saw none. */
    private static final class AccountAlert {
        private final GatewayAlerts alerts;
        private final String type;
        private final boolean[] firing;

        AccountAlert(final GatewayAlerts alerts, final String type, final int accounts) {
            this.alerts = alerts;
            this.type = type;
            this.firing = new boolean[accounts];
        }

        void minute(final int index, final GatewayAccount account, final long count, final String what) {
            if (count > 0) {
                this.firing[index] = true;
                this.alerts.firing(
                        this.type,
                        account.name(),
                        "Gateway account " + account.name() + ": " + what,
                        Map.of(
                                "account",
                                account.name(),
                                "accountId",
                                Integer.toString(account.accountId()),
                                "count",
                                Long.toString(count)));
            } else if (this.firing[index]) {
                this.firing[index] = false;
                this.alerts.resolved(this.type, account.name());
            }
        }
    }
}
