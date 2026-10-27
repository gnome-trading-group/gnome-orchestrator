package group.gnometrading.collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.SecurityMaster;
import group.gnometrading.collector.CycleUploader;
import group.gnometrading.collector.DirectBufferPool;
import group.gnometrading.collector.MarketDataCollector;
import group.gnometrading.collector.RawDataCollector;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.feeds.FeedSources;
import group.gnometrading.feeds.FeedVenues;
import group.gnometrading.feeds.S3Assignment;
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
import group.gnometrading.shared.AwsModule;
import group.gnometrading.shared.SecretCache;
import group.gnometrading.shared.SecurityMasterModule;
import group.gnometrading.trading.AgentRuntimeInstaller;
import group.gnometrading.utils.CauseChain;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * A market data shard ({@code SHARD_ID}): reads the streams its assignment names, each source's markets multiplexed
 * over a few connections, and records each stream's book into minute files and each connection's raw frames.
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
        AgentRuntimeInstaller.installStandard(logger);
        final SchedulingLatencyProbe latencyProbe =
                SchedulingLatencyProbe.start(getInstance(Long.class, "LATENCY_PROBE_INTERVAL_NANOS"));

        final String shard = getInstance(String.class, "SHARD_ID");
        final Clock clock = getInstance(Clock.class);
        final S3Client s3 = getInstance(S3Client.class);
        final CycleUploader uploader = new CycleUploader(logger, s3);
        final DirectBufferPool pool = new DirectBufferPool();
        final String outputBucket = getInstance(String.class, "OUTPUT_BUCKET");
        final String rawBucket = getInstance(String.class, "RAW_CAPTURE_BUCKET");

        final BookProber prober =
                new BookProber(logger, getInstance(EpochNanoClock.class), BookProber.Settings.DEFAULT);
        final Feeds feeds = new Feeds(logger, prober);
        final FeedSources sources = new FeedSources(
                feeds,
                new FeedVenues.Context(
                        logger,
                        getInstance(Properties.class),
                        getInstance(SecretCache.class),
                        getInstance(EpochNanoClock.class)),
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

        final MarketDataCollectorAgent agent = new MarketDataCollectorAgent(logger, System::nanoTime);
        final ShardRuntime runtime = new ShardRuntime(
                logger,
                getInstance(SecurityMaster.class),
                new S3Assignment(s3, outputBucket, shard),
                feeds,
                sources::ensure,
                agent,
                (streamId, name) -> new ShardStream(
                        streamId,
                        name,
                        // A market's producer changes thread when a roll moves it to a new connection.
                        new SequencedRingBuffer<>(
                                Mbp10Schema::new,
                                new GlobalSequence(),
                                SequencedRingBuffer.DEFAULT_SIZE,
                                SequencedRingBuffer.Producers.TAKING_TURNS),
                        new MarketDataCollector(
                                logger, clock, uploader, pool, streamId, RecordType.MBP_10, outputBucket)));

        final GnomeAgentRunner agentRunner = new GnomeAgentRunner(agent, exitOnError(logger, "Collector"));
        GnomeAgentRunner.startOnThread(agentRunner);
        final GnomeAgentRunner proberRunner = new GnomeAgentRunner(
                prober, error -> logger.logf(LogMessage.UNKNOWN_ERROR, "Book probe failed: %s", CauseChain.of(error)));
        GnomeAgentRunner.startOnThread(proberRunner);
        final ScheduledExecutorService runtimeThread = daemonScheduler("shard-runtime");
        runtimeThread.scheduleWithFixedDelay(
                () -> runtime.tick(System.nanoTime()), 0, TICK_MILLIS, TimeUnit.MILLISECONDS);

        final CollectorHeartbeat heartbeat = new CollectorHeartbeat(
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
        final ScheduledExecutorService heartbeats = daemonScheduler("collector-heartbeat");
        heartbeats.scheduleAtFixedRate(
                heartbeat, CollectorHeartbeat.INTERVAL_SECONDS, CollectorHeartbeat.INTERVAL_SECONDS, TimeUnit.SECONDS);

        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            shutdown(logger, runtime, runtimeThread, proberRunner, agentRunner, uploader);
                            // A last report, after any scheduled one finishes: the heartbeat's readings are for one
                            // thread at a time.
                            heartbeats.shutdown();
                            try {
                                if (heartbeats.awaitTermination(5, TimeUnit.SECONDS)) {
                                    heartbeat.run();
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

    private static ScheduledExecutorService daemonScheduler(final String name) {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * A stream's own collector failure is caught and the stream restarted, so one that gets here is the agent's: the
     * process can't record anything, so exit and let ECS replace the task.
     */
    private static ErrorHandler exitOnError(final Logger logger, final String what) {
        return error -> {
            logger.logf(LogMessage.FATAL_ERROR_EXITING, "%s error: %s", what, CauseChain.of(error));
            System.exit(1);
        };
    }
}
