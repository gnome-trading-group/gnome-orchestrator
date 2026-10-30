package group.gnometrading.collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.SecurityMaster;
import group.gnometrading.aeron.AeronChannels;
import group.gnometrading.aeron.AeronPorts;
import group.gnometrading.aeron.AeronProfile;
import group.gnometrading.aeron.AeronRuntime;
import group.gnometrading.aeron.AeronStreamIds;
import group.gnometrading.aeron.MarketDataIdle;
import group.gnometrading.aeron.ServiceEndpoint;
import group.gnometrading.aeron.ServiceEndpoints;
import group.gnometrading.collector.CycleUploader;
import group.gnometrading.collector.DirectBufferPool;
import group.gnometrading.collector.MarketDataCollector;
import group.gnometrading.collector.RawDataCollector;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.feeds.DynamoAssignment;
import group.gnometrading.feeds.FeedSources;
import group.gnometrading.feeds.FeedVenues;
import group.gnometrading.feeds.MarketDataCredentials;
import group.gnometrading.feeds.WebSocketFeedOpener;
import group.gnometrading.gateways.feed.Feeds;
import group.gnometrading.gateways.feed.probe.BookProber;
import group.gnometrading.health.HealthCheckServer;
import group.gnometrading.host.HostReadings;
import group.gnometrading.logging.AsyncLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.RecordType;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sequencer.StreamRecordEventAdapter;
import group.gnometrading.shared.AwsModule;
import group.gnometrading.shared.SecretCache;
import group.gnometrading.shared.SecurityMasterModule;
import group.gnometrading.trading.AgentRuntimeInstaller;
import group.gnometrading.utils.CauseChain;
import group.gnometrading.utils.DaemonThreads;
import group.gnometrading.utils.SchedulingLatencyProbe;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * A market data shard ({@code SHARD_ID}): reads the streams its assignment (its item in the reconciler's
 * {@code collection-shards} table) names, each source's markets multiplexed over a few connections, records each
 * stream's book into minute files and each connection's raw frames, and writes its status for the reconciler.
 */
public class ShardOrchestrator extends Orchestrator {

    static {
        instanceClass = ShardOrchestrator.class;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Of the ECS stop timeout's 120 seconds: this long for connections to close and streams to flush, the uploader's
    // 45 after.
    private static final long STOP_TIMEOUT_SECONDS = 60;
    private static final long TICK_MILLIS = 1_000;

    @Provides
    public final Clock provideClock() {
        return Clock.systemUTC();
    }

    @Provides
    public final EpochNanoClock provideEpochNanoClock() {
        return new SystemEpochNanoClock();
    }

    @Provides
    @Singleton
    public final Logger provideLogger(EpochNanoClock epochClock) {
        return new AsyncLogger(epochClock);
    }

    @Provides
    @Named("SHARD_ID")
    public final String provideShardId(Properties properties) {
        return properties.getStringProperty("shard.id");
    }

    @Provides
    @Named("OUTPUT_BUCKET")
    public final String provideOutputBucket(Properties properties) {
        return properties.getStringProperty("output.bucket");
    }

    @Provides
    @Named("RAW_CAPTURE_BUCKET")
    public final String provideRawCaptureBucket(Properties properties) {
        return properties.getStringProperty("raw.capture.bucket");
    }

    @Provides
    @Named("STATUS_BUCKET")
    public final String provideStatusBucket(Properties properties) {
        return properties.getStringProperty("status.bucket");
    }

    /** The control plane's table, in its one region (us-east-1) wherever the shard runs. */
    @Provides
    @Singleton
    @Named("COLLECTION")
    public final DynamoDbClient provideCollectionDynamo(
            Properties properties, @Named("AWS_PROFILE") String awsProfile) {
        var builder = DynamoDbClient.builder().region(Region.of(properties.getStringProperty("collection.region")));
        if (awsProfile != null && !awsProfile.isEmpty()) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(awsProfile));
        }
        return builder.build();
    }

    @Provides
    @Named("LATENCY_PROBE_INTERVAL_NANOS")
    public final Long provideLatencyProbeIntervalNanos(Properties properties) {
        if (properties.hasProperty("latency.probe.interval.micros")) {
            return TimeUnit.MICROSECONDS.toNanos(properties.getIntProperty("latency.probe.interval.micros"));
        }
        return TimeUnit.MILLISECONDS.toNanos(1);
    }

    @Override
    public final void configure() {
        install(new SecurityMasterModule(), new AwsModule());
        final Logger logger = getInstance(Logger.class);
        // Before any agent thread starts. Shards run on fractional Fargate CPUs, where anything spinning burns the
        // quota and the throttled task delays every socket read, inflating receive timestamps.
        AgentRuntimeInstaller.installStandard(logger, MarketDataIdle.forShard(getInstance(Properties.class)));
        final SchedulingLatencyProbe latencyProbe =
                SchedulingLatencyProbe.start(getInstance(Long.class, "LATENCY_PROBE_INTERVAL_NANOS"));

        final String shard = getInstance(String.class, "SHARD_ID");
        final Clock clock = getInstance(Clock.class);
        final S3Client s3 = getInstance(S3Client.class);
        final CycleUploader uploader = new CycleUploader(logger, s3);
        final DirectBufferPool pool = new DirectBufferPool();
        final String outputBucket = getInstance(String.class, "OUTPUT_BUCKET");
        final String rawBucket = getInstance(String.class, "RAW_CAPTURE_BUCKET");

        final SecurityMaster securityMaster = getInstance(SecurityMaster.class);
        final Properties properties = getInstance(Properties.class);
        final EpochNanoClock wallClock = getInstance(EpochNanoClock.class);
        final ShardAccounts accounts = new ShardAccounts();
        final StreamTransitions transitions = new StreamTransitions();
        final BookProber prober =
                new BookProber(logger, getInstance(EpochNanoClock.class), BookProber.Settings.DEFAULT);
        final Feeds feeds = new Feeds(logger, prober);
        final FeedSources sources = new FeedSources(
                feeds,
                new FeedVenues.Context(
                        logger,
                        properties,
                        getInstance(SecretCache.class),
                        MarketDataCredentials.forShard(securityMaster, accounts::label),
                        wallClock),
                false,
                // GatewayErrorHandler has logged it; the connection's supervisor keeps trying, its markets flagged
                // meanwhile, and replacing the task would cut every other connection.
                (status, cause) -> {},
                source -> connection -> {
                    final RawDataCollector raw =
                            new RawDataCollector(logger, clock, uploader, pool, source, shard, connection, rawBucket);
                    return new WebSocketFeedOpener.RawCapture() {
                        @Override
                        public void capture(final long recvTimestamp, final ByteBuffer buffer) {
                            raw.capture(recvTimestamp, buffer);
                        }

                        @Override
                        public void flush() {
                            raw.flush();
                        }
                    };
                },
                WebSocketFeedOpener.Clients.TLS);

        final MarketDataCollectorAgent agent =
                new MarketDataCollectorAgent(logger, System::nanoTime, ShardOrchestrator::wallNanos);
        final ShardStatusWriter.Identity identity = ShardIdentity.resolve(
                MAPPER, shard, properties.getStringProperty("orchestrator.version"), wallClock.nanoTime());
        if (identity.privateIp() == null) {
            throw new IllegalStateException("No private IP for sessions to reach this shard on");
        }
        // Sessions read leased streams from here over Aeron, on the shard's private IP.
        final AeronRuntime aeron = AeronRuntime.launch(
                "shard",
                AeronProfile.STANDARD,
                MarketDataIdle.forShard(properties),
                error -> logger.logf(LogMessage.UNKNOWN_ERROR, "Aeron error: %s", CauseChain.of(error)));
        final AtomicReference<ShardRuntime> runtimeRef = new AtomicReference<>();
        final StreamPublisherAgent publisher = publisher(aeron, identity, runtimeRef);
        final StreamPublishing publishing = publishing(aeron, identity, publisher);
        final ShardRuntime runtime = new ShardRuntime(
                logger,
                securityMaster,
                new DynamoAssignment(
                        getInstance(DynamoDbClient.class, "COLLECTION"),
                        properties.getStringProperty("collection.shards.table"),
                        shard),
                feeds,
                sources::ensure,
                agent,
                streamFactory(logger, clock, uploader, pool, outputBucket),
                accountOf(securityMaster, accounts),
                accounts,
                transitions,
                wallClock,
                publishing);
        runtimeRef.set(runtime);

        final GnomeAgentRunner agentRunner = new GnomeAgentRunner(agent, exitOnError(logger, "Collector"));
        GnomeAgentRunner.startOnThread(agentRunner);
        // Its poller gates every ring as the collector's does, so a publisher that stopped would stop collection: exit.
        final GnomeAgentRunner publisherRunner = new GnomeAgentRunner(publisher, exitOnError(logger, "Publisher"));
        GnomeAgentRunner.startOnThread(publisherRunner);
        final GnomeAgentRunner proberRunner = new GnomeAgentRunner(
                prober, error -> logger.logf(LogMessage.UNKNOWN_ERROR, "Book probe failed: %s", CauseChain.of(error)));
        GnomeAgentRunner.startOnThread(proberRunner);
        final ScheduledExecutorService runtimeThread = DaemonThreads.scheduler("shard-runtime");
        runtimeThread.scheduleWithFixedDelay(
                () -> runtime.tick(System.nanoTime()), 0, TICK_MILLIS, TimeUnit.MILLISECONDS);

        final CollectorHeartbeat heartbeat = heartbeat(logger, runtime, prober, uploader, latencyProbe);
        final ShardStatusWriter status = statusWriter(logger, s3, identity, wallClock, runtime, heartbeat, transitions);
        final ServiceEndpoints endpoints = new ServiceEndpoints(getInstance(DynamoDbClient.class, "COLLECTION"));
        final String region = System.getenv().getOrDefault("AWS_REGION", "unknown");
        final AtomicReference<String> advertised = new AtomicReference<>("ACTIVE");
        // The status repeats the heartbeat's process line, so it is written right after it, on the same thread.
        final Runnable report = () -> {
            heartbeat.run();
            status.run();
            advertise(logger, endpoints, identity, region, advertised.get(), wallClock);
        };
        final ScheduledExecutorService heartbeats = DaemonThreads.scheduler("collector-heartbeat");
        heartbeats.scheduleAtFixedRate(
                report, CollectorHeartbeat.INTERVAL_SECONDS, CollectorHeartbeat.INTERVAL_SECONDS, TimeUnit.SECONDS);

        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            // First, so sessions stop joining a shard that is going; a replacement task overwrites it.
                            advertised.set("STOPPING");
                            advertise(logger, endpoints, identity, region, advertised.get(), wallClock);
                            shutdown(logger, runtime, runtimeThread, proberRunner, agentRunner, uploader);
                            close(logger, publisherRunner, "stream publisher");
                            aeron.close();
                            // A last report, after any scheduled one finishes: the heartbeat's readings are for one
                            // thread at a time.
                            heartbeats.shutdown();
                            try {
                                if (heartbeats.awaitTermination(5, TimeUnit.SECONDS)) {
                                    report.run();
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        "shard-shutdown"));

        try {
            new HealthCheckServer(
                            8080,
                            new CollectorHealth(
                                    agentRunner::cycles,
                                    runtime::lastTickNanos,
                                    now -> uploader.isStuck(now, CollectorHealth.UPLOAD_STALL_NANOS),
                                    System::nanoTime))
                    .start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static CollectorHeartbeat heartbeat(
            final Logger logger,
            final ShardRuntime runtime,
            final BookProber prober,
            final CycleUploader uploader,
            final SchedulingLatencyProbe latencyProbe) {
        return new CollectorHeartbeat(
                logger,
                MAPPER,
                new CollectorHeartbeat.Shard() {
                    @Override
                    public List<ShardStream> streams() {
                        return runtime.streams();
                    }

                    @Override
                    public List<CollectorHeartbeat.Connection> connections() {
                        return runtime.connections();
                    }

                    @Override
                    public long generation() {
                        return runtime.generation();
                    }

                    @Override
                    public long probes() {
                        return prober.probes();
                    }

                    @Override
                    public long probeMismatches() {
                        return prober.mismatches();
                    }
                },
                uploader,
                HostReadings.ofThisProcess(new File("/")),
                latencyProbe,
                System.nanoTime());
    }

    private static StreamPublisherAgent publisher(
            final AeronRuntime aeron,
            final ShardStatusWriter.Identity identity,
            final AtomicReference<ShardRuntime> runtime) {
        return new StreamPublisherAgent(
                StreamPublication.of(aeron.aeron()
                        .addPublication(
                                AeronChannels.shardPublication(identity.privateIp()), AeronStreamIds.SHARD_STATUS)),
                () -> {
                    final ShardRuntime started = runtime.get();
                    return started == null ? -1 : started.generation();
                },
                System::nanoTime,
                ShardOrchestrator::wallNanos);
    }

    private static StreamPublishing publishing(
            final AeronRuntime aeron, final ShardStatusWriter.Identity identity, final StreamPublisherAgent publisher) {
        final String channel = AeronChannels.shardPublication(identity.privateIp());
        return new StreamPublishing() {
            @Override
            public void add(final ShardStream stream) {
                publisher.add(stream);
            }

            @Override
            public void retire(final ShardStream stream) {
                publisher.retire(stream);
            }

            @Override
            public StreamPublication open(final int streamId) {
                return StreamPublication.of(aeron.aeron().addPublication(channel, streamId));
            }
        };
    }

    private static ShardRuntime.StreamFactory streamFactory(
            final Logger logger,
            final Clock clock,
            final CycleUploader uploader,
            final DirectBufferPool pool,
            final String outputBucket) {
        return (streamId, name) -> {
            final MarketDataCollector collector =
                    new MarketDataCollector(logger, clock, uploader, pool, streamId, RecordType.MBP_10, outputBucket);
            return new ShardStream(
                    streamId,
                    name,
                    // A market's producer changes thread when a roll moves it to a new connection.
                    new SequencedRingBuffer<>(
                            Mbp10Schema::new,
                            new GlobalSequence(),
                            SequencedRingBuffer.DEFAULT_SIZE,
                            SequencedRingBuffer.Producers.TAKING_TURNS),
                    collector,
                    new StreamRecordEventAdapter(collector),
                    true);
        };
    }

    /** Where sessions reach this shard over Aeron; the item expires if the shard stops writing it. Never throws. */
    private static void advertise(
            final Logger logger,
            final ServiceEndpoints endpoints,
            final ShardStatusWriter.Identity identity,
            final String region,
            final String state,
            final EpochNanoClock wallClock) {
        try {
            endpoints.put(new ServiceEndpoint(
                    ServiceEndpoint.Kind.SHARD,
                    identity.shard(),
                    identity.task(),
                    region,
                    identity.privateIp(),
                    AeronPorts.SHARD_CONTROL,
                    state,
                    identity.version(),
                    wallClock.nanoTime()));
        } catch (Exception error) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Failed to write the shard's endpoint: %s", CauseChain.of(error));
        }
    }

    private ShardStatusWriter statusWriter(
            final Logger logger,
            final S3Client s3,
            final ShardStatusWriter.Identity identity,
            final EpochNanoClock wallClock,
            final ShardRuntime runtime,
            final CollectorHeartbeat heartbeat,
            final StreamTransitions transitions) {
        return new ShardStatusWriter(
                logger,
                s3,
                getInstance(String.class, "STATUS_BUCKET"),
                identity,
                new ShardStatusWriter.Shard() {
                    @Override
                    public List<ShardStream> streams() {
                        return runtime.streams();
                    }

                    @Override
                    public List<CollectorHeartbeat.Connection> connections() {
                        return runtime.connections();
                    }

                    @Override
                    public long generation() {
                        return runtime.generation();
                    }

                    @Override
                    public String processLine() {
                        return heartbeat.processLine();
                    }
                },
                transitions,
                wallClock::nanoTime);
    }

    /** A source's market-data account label, for the heartbeat; null for a public feed, which signs in with none. */
    private static Function<String, String> accountOf(
            final SecurityMaster securityMaster, final ShardAccounts accounts) {
        return source -> FeedVenues.forAdapter(securityMaster.getSource(source).adapter())
                        .signed()
                ? accounts.label(source)
                : null;
    }

    /**
     * On SIGTERM: on the runtime's thread, every connection closes, flushing its raw minutes, and every stream's ring
     * is drained into its minute files and flushed, partial ones included; then the uploader gets what is left of the
     * stop timeout to send them.
     */
    private static void shutdown(
            final Logger logger,
            final ShardRuntime runtime,
            final ScheduledExecutorService runtimeThread,
            final GnomeAgentRunner prober,
            final GnomeAgentRunner agent,
            final CycleUploader uploader) {
        logger.logf(LogMessage.DEBUG, "Stopping %d streams", runtime.size());
        close(logger, prober, "book prober");
        try {
            final CountDownLatch flushed = runtimeThread
                    .submit(() -> {
                        final CountDownLatch done = new CountDownLatch(runtime.size());
                        runtime.stopAll(done);
                        return done;
                    })
                    .get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!flushed.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.logf(LogMessage.UNKNOWN_ERROR, "Streams still flushing; uploading what was flushed");
            }
        } catch (TimeoutException e) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Connections still closing after %d seconds; uploading what was flushed",
                    STOP_TIMEOUT_SECONDS);
        } catch (ExecutionException e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Failed to stop streams: %s", CauseChain.of(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        runtimeThread.shutdownNow();
        close(logger, agent, "collector agent");
        uploader.close();
        logger.logf(LogMessage.DEBUG, "Shard stopped; %d uploads failed", uploader.failedUploads());
    }

    private static void close(final Logger logger, final GnomeAgentRunner runner, final String what) {
        try {
            runner.close();
        } catch (Exception e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Failed to stop the %s: %s", what, e);
        }
    }

    /**
     * A stream's own collector failure is caught and the stream restarted, so one that gets here is the agent's: the
     * process can't record anything, so exit and let ECS replace the task. The exit runs on a thread of its own: the
     * shutdown hook joins the agent's thread, which would never finish if it were the one waiting in exit.
     */
    private static ErrorHandler exitOnError(final Logger logger, final String what) {
        final AtomicBoolean exiting = new AtomicBoolean();
        return error -> {
            logger.logf(LogMessage.FATAL_ERROR_EXITING, "%s error: %s", what, CauseChain.of(error));
            if (exiting.compareAndSet(false, true)) {
                new Thread(() -> System.exit(1), "shard-exit").start();
            }
        };
    }

    private static long wallNanos() {
        return TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
    }
}
