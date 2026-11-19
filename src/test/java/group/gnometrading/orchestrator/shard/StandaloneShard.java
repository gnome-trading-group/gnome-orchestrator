package group.gnometrading.orchestrator.shard;

import group.gnometrading.SecurityMaster;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.feed.Feeds;
import group.gnometrading.gateways.feed.probe.BookProber;
import group.gnometrading.host.HostReadings;
import group.gnometrading.host.JvmReadings;
import group.gnometrading.logging.AsyncLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.md.Source;
import group.gnometrading.metrics.CounterSampler;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.networking.websockets.WebSocketClientBuilder;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronPathIdle;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.AeronStreamIds;
import group.gnometrading.orchestrator.aeron.HostAddress;
import group.gnometrading.orchestrator.aws.AwsModule;
import group.gnometrading.orchestrator.aws.SecretCache;
import group.gnometrading.orchestrator.aws.SecurityMasterModule;
import group.gnometrading.orchestrator.di.Orchestrator;
import group.gnometrading.orchestrator.di.Provides;
import group.gnometrading.orchestrator.di.Singleton;
import group.gnometrading.orchestrator.runtime.AgentRuntimeInstaller;
import group.gnometrading.orchestrator.runtime.ClockReadings;
import group.gnometrading.orchestrator.runtime.DaemonThreads;
import group.gnometrading.orchestrator.runtime.LatencySampler;
import group.gnometrading.orchestrator.runtime.RuntimeSampler;
import group.gnometrading.orchestrator.runtime.SchedulingLatencyProbe;
import group.gnometrading.orchestrator.shard.feeds.Assignment;
import group.gnometrading.orchestrator.shard.feeds.FeedSources;
import group.gnometrading.orchestrator.shard.feeds.FeedVenues;
import group.gnometrading.orchestrator.shard.feeds.MarketDataCredentials;
import group.gnometrading.orchestrator.shard.feeds.SpecChanges;
import group.gnometrading.orchestrator.shard.feeds.WebSocketFeedOpener;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.schemas.RecordType;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sequencer.StreamRecordEventAdapter;
import group.gnometrading.utils.CauseChain;
import group.gnometrading.utils.SampledEpochNanoClock;
import io.aeron.Aeron;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochClock;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * A shard outside the collection control plane, for latency studies on hosts of our choosing. It reads and publishes
 * a fixed list of streams through the same feeds, collector, publisher and Aeron path a shard runs, and prints each
 * minute's samples ({@code runtime}, {@code shard.agents}, {@code shard.streams}, {@code latency.shard.*}) as JSON
 * lines to stdout and {@code --standalone.out}, in the form shards post them.
 *
 * <p>It writes nothing outside the host: minute files and raw frames are still built and compressed, so their CPU cost
 * is the real one, but every upload is dropped; no status, endpoint item or samples post.
 *
 * <p>Run with {@code STAGE=prod}, from a release jar plus the test classes (as {@code ShardProbe} is), with property
 * overrides as program arguments:
 *
 * <ul>
 *   <li>{@code --standalone.streams=1,2,3} (required), {@code --standalone.minutes=60}, {@code --standalone.out=<file>}
 *   <li>{@code --standalone.streams=@<file>} reads the list from a file instead (an ECS override holds 8 KB at most),
 *       and {@code --standalone.collect=<n>} keeps its first {@code n}
 *   <li>{@code --standalone.publish=<n>}: publish only the first {@code n} streams, as a shard publishes only those
 *       sessions lease (default all, so a probe can read every one)
 *   <li>{@code --standalone.account=<label>}: the market-data account every source signs in with (default
 *       {@value MarketDataCredentials#DEFAULT_LABEL})
 *   <li>Unpinned by default, as shards run. {@code --latency.profile=low_latency --cpu.affinity.enabled=true
 *       --cpu.isolated=1 --cpu.housekeeping=0 --market.data.idle.max.park.micros=50} gives hot agents a core of their
 *       own, with the shard's Aeron parks. Readers block either way, as a shard's do.
 *   <li>{@code --standalone.aeron=standard|latency|invoker}: the media driver's {@link AeronProfile} (default
 *       standard, as shards run), or {@code invoker}: no driver threads, the publisher's thread runs it
 *       ({@link InvokedAeron})
 *   <li>{@code --kalshi.ws.url=ws://<host>:<port>/trade-api/ws/v2} reads Kalshi from a {@link KalshiTestVenue}. A plain
 *       {@code ws://} URL gets a plain socket, and the book prober doesn't run: its REST fetches from the real venue
 *       would find every stand-in book wrong.
 * </ul>
 */
public final class StandaloneShard extends Orchestrator {

    private static final String SHARD_ID = "standalone";
    private static final String DISCARDED = "standalone-discarded";
    private static final long TICK_MILLIS = 1_000;
    private static final long MINUTE_MILLIS = 60_000;
    private static final long STOP_SECONDS = 30;
    private static final int OUT_BYTES = 1 << 20;

    private SampleBuffer samples;
    private List<SampleSource> sources;
    private Runnable stop;

    /** Something that adds to the samples when its interval is up. */
    @FunctionalInterface
    interface SampleSource {
        boolean sampleIfDue(long nowMs);
    }

    @Provides
    public final Clock provideClock() {
        return Clock.systemUTC();
    }

    @Provides
    public final EpochNanoClock provideEpochNanoClock() {
        return SampledEpochNanoClock.INSTANCE;
    }

    @Provides
    @Singleton
    public final Logger provideLogger(EpochNanoClock epochClock) {
        return new AsyncLogger(epochClock);
    }

    public static void main(final String[] args) throws Exception {
        final StandaloneShard shard = (StandaloneShard) Orchestrator.start(StandaloneShard.class, args);
        shard.run();
        System.exit(0);
    }

    @Override
    public void configure() {
        install(new SecurityMasterModule(), new AwsModule());
        final Logger logger = getInstance(Logger.class);
        final Properties properties = getInstance(Properties.class);
        if (AgentRuntimeInstaller.pinsHotAgents(properties)) {
            AgentRuntimeInstaller.install(properties, logger);
        } else {
            AgentRuntimeInstaller.installStandard(logger, AeronPathIdle.forShard(properties));
        }
        final SchedulingLatencyProbe latencyProbe = SchedulingLatencyProbe.start(TimeUnit.MILLISECONDS.toNanos(1));

        final int[] listed = streamIds(listOrFile(properties.getStringProperty("standalone.streams")));
        final int[] streamIds = properties.hasProperty("standalone.collect")
                ? Arrays.copyOf(listed, Math.min(listed.length, properties.getIntProperty("standalone.collect")))
                : listed;
        final String account = properties.hasProperty("standalone.account")
                ? properties.getStringProperty("standalone.account")
                : MarketDataCredentials.DEFAULT_LABEL;
        final Clock clock = getInstance(Clock.class);
        final EpochNanoClock wallClock = getInstance(EpochNanoClock.class);
        final SecurityMaster securityMaster = getInstance(SecurityMaster.class);
        final CycleUploader uploader = new CycleUploader(logger, new DiscardingS3());
        final DirectBufferPool pool = new DirectBufferPool();
        final ShardAccounts accounts = new ShardAccounts();
        final BookProber prober = new BookProber(logger, wallClock, BookProber.Settings.DEFAULT);
        final Feeds feeds = new Feeds(logger, prober);
        final RawCaptureAgent rawCapture = ShardOrchestrator.rawCapture(logger, properties);
        final GnomeAgentRunner rawRunner = new GnomeAgentRunner(rawCapture, failed(logger, "Raw capture"));
        GnomeAgentRunner.startOnThread(rawRunner);
        final FeedSources feedSources = feedSources(
                logger, properties, securityMaster, accounts, wallClock, feeds, clock, uploader, pool, rawCapture);

        final ShardLatency latency = shardLatency(wallClock, securityMaster);
        final MarketDataCollectorAgent agent =
                new MarketDataCollectorAgent(logger, System::nanoTime, StandaloneShard::wallNanos, latency);
        final String privateIp = HostAddress.privateIp();
        if (privateIp == null) {
            throw new IllegalStateException("No private IP for the probe to reach this shard on");
        }
        final ErrorHandler aeronErrors =
                error -> logger.logf(LogMessage.UNKNOWN_ERROR, "Aeron error: %s", CauseChain.of(error));
        final InvokedAeron invoked = invokesDriver(properties) ? InvokedAeron.launch("shard", aeronErrors) : null;
        final AeronRuntime runtimeAeron = invoked != null
                ? null
                : AeronRuntime.launch(
                        "shard", aeronProfile(properties), AeronPathIdle.forShard(properties), aeronErrors);
        final Aeron aeron = invoked != null ? invoked.aeron() : runtimeAeron.aeron();
        final AutoCloseable aeronCloser = invoked != null ? invoked : runtimeAeron;
        final AtomicReference<ShardRuntime> runtimeRef = new AtomicReference<>();
        final StreamPublisherAgent publisher = new StreamPublisherAgent(
                StreamPublication.of(
                        aeron.addPublication(AeronChannels.shardPublication(privateIp), AeronStreamIds.SHARD_STATUS)),
                () -> {
                    final ShardRuntime started = runtimeRef.get();
                    return started == null ? -1 : started.generation();
                },
                System::nanoTime,
                wallClock::nanoTime,
                latency);
        final String channel = AeronChannels.shardPublication(privateIp);
        final StreamPublishing publishing = new StreamPublishing() {
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
                return StreamPublication.of(aeron.addPublication(channel, streamId));
            }
        };
        final ShardRuntime runtime = new ShardRuntime(
                logger,
                securityMaster,
                fixedAssignment(
                        streamIds,
                        properties.hasProperty("standalone.publish")
                                ? properties.getIntProperty("standalone.publish")
                                : streamIds.length,
                        sourceCodes(securityMaster),
                        account),
                feeds,
                feedSources::ensure,
                agent,
                streamFactory(logger, clock, uploader, pool, latency),
                source -> accounts.label(source),
                accounts,
                new StreamTransitions(),
                wallClock,
                publishing);
        runtimeRef.set(runtime);

        final GnomeAgentRunner agentRunner = new GnomeAgentRunner(agent, failed(logger, "Collector"));
        GnomeAgentRunner.startOnThread(agentRunner);
        final GnomeAgentRunner publisherRunner = new GnomeAgentRunner(
                invoked != null ? invoked.onPublisherThread(publisher) : publisher, failed(logger, "Publisher"));
        GnomeAgentRunner.startOnThread(publisherRunner);
        final GnomeAgentRunner proberRunner = new GnomeAgentRunner(prober, failed(logger, "Book probe"));
        if (!readsTestVenue(properties)) {
            GnomeAgentRunner.startOnThread(proberRunner);
        }
        final ScheduledExecutorService runtimeThread = DaemonThreads.scheduler("shard-runtime");
        runtimeThread.scheduleWithFixedDelay(
                () -> runtime.tick(System.nanoTime()), 0, TICK_MILLIS, TimeUnit.MILLISECONDS);

        samples = new SampleBuffer(4 << 20, 10_000, 256 << 10);
        final CollectorHeartbeat.Shard shard = shardView(runtime, prober);
        final RuntimeSampler runtimeSampler = new RuntimeSampler(
                samples,
                HostReadings.ofThisProcess(new File("/")),
                new JvmReadings(),
                ClockReadings.ofThisHost(SystemEpochClock.INSTANCE),
                latencyProbe,
                aeron.countersReader(),
                List.of(agentRunner, publisherRunner, proberRunner, rawRunner),
                properties.getLongProperty("samples.runtime.interval.ms"));
        final CounterSampler agents = new CounterSampler(
                samples,
                "shard.agents",
                List.of("collector", "publisher", "rawCapture"),
                List.of(agent.counters(), publisher.counters(), rawCapture.counters()),
                MINUTE_MILLIS);
        final ShardSampler streams =
                new ShardSampler(samples, shard, uploader::queuedUploads, uploader::failedUploads, MINUTE_MILLIS);
        final LatencySampler latencies = new LatencySampler(samples, latency.sets(), ShardLatency.kinds());
        sources =
                List.of(runtimeSampler::sampleIfDue, agents::sampleIfDue, streams::sampleIfDue, latencies::sampleIfDue);

        logger.logf(
                LogMessage.DEBUG,
                "Standalone shard reading %d streams on %s as account %s: %s",
                streamIds.length,
                privateIp,
                account,
                Arrays.toString(streamIds));

        stop = () -> {
            close(logger, proberRunner, "book prober");
            try {
                final CountDownLatch flushed = runtimeThread
                        .submit(() -> {
                            final CountDownLatch done = new CountDownLatch(runtime.size());
                            runtime.stopAll(done);
                            return done;
                        })
                        .get(STOP_SECONDS, TimeUnit.SECONDS);
                flushed.await(STOP_SECONDS, TimeUnit.SECONDS);
            } catch (Exception error) {
                logger.logf(LogMessage.UNKNOWN_ERROR, "Streams didn't stop cleanly: %s", CauseChain.of(error));
            }
            runtimeThread.shutdownNow();
            close(logger, agentRunner, "collector");
            close(logger, publisherRunner, "publisher");
            close(logger, rawRunner, "raw capture");
            uploader.close();
            try {
                aeronCloser.close();
            } catch (Exception error) {
                logger.logf(LogMessage.UNKNOWN_ERROR, "Aeron didn't close cleanly: %s", CauseChain.of(error));
            }
        };
    }

    /** Prints samples each second for {@code --standalone.minutes}, then stops everything. */
    private void run() throws Exception {
        final Properties properties = getInstance(Properties.class);
        final long minutes =
                properties.hasProperty("standalone.minutes") ? properties.getLongProperty("standalone.minutes") : 60;
        final String outFile =
                properties.hasProperty("standalone.out") ? properties.getStringProperty("standalone.out") : null;
        final ByteBuffer json = ByteBuffer.allocate(OUT_BYTES);
        try (PrintStream out = outFile == null ? null : new PrintStream(new FileOutputStream(outFile, true))) {
            final long endMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes);
            while (System.currentTimeMillis() < endMs) {
                Thread.sleep(1_000);
                final long nowMs = System.currentTimeMillis();
                for (SampleSource source : sources) {
                    source.sampleIfDue(nowMs);
                }
                print(samples, json, out);
            }
        }
        stop.run();
    }

    static boolean invokesDriver(final Properties properties) {
        return properties.hasProperty("standalone.aeron")
                && properties.getStringProperty("standalone.aeron").equalsIgnoreCase("invoker");
    }

    static boolean readsTestVenue(final Properties properties) {
        return properties.hasProperty("kalshi.ws.url")
                && properties.getStringProperty("kalshi.ws.url").startsWith("ws://");
    }

    /** A plain socket for a {@code ws://} URL (a {@link KalshiTestVenue}), TLS otherwise, as shards connect. */
    static final WebSocketFeedOpener.Clients CLIENTS = (uri, readBufferBytes) -> "ws".equalsIgnoreCase(uri.getScheme())
            ? new WebSocketClientBuilder()
                    .withURI(uri)
                    .withReadBufferSize(readBufferBytes)
                    .build()
            : WebSocketFeedOpener.Clients.TLS.create(uri, readBufferBytes);

    static AeronProfile aeronProfile(final Properties properties) {
        return properties.hasProperty("standalone.aeron")
                ? AeronProfile.valueOf(
                        properties.getStringProperty("standalone.aeron").toUpperCase(Locale.ROOT))
                : AeronProfile.STANDARD;
    }

    static String listOrFile(final String value) {
        if (!value.startsWith("@")) {
            return value;
        }
        try {
            return Files.readString(Path.of(value.substring(1)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int[] streamIds(final String list) {
        final int[] ids = Arrays.stream(list.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .mapToInt(Integer::parseInt)
                .toArray();
        if (ids.length == 0) {
            throw new IllegalArgumentException("--standalone.streams names no streams");
        }
        return ids;
    }

    /** Every stream read and published, every source signed in with {@code account}. */
    static Assignment fixedAssignment(
            final int[] streamIds, final int published, final List<String> sourceCodes, final String account) {
        final Map<String, String> accounts = new HashMap<>();
        for (String code : sourceCodes) {
            accounts.put(code, account);
        }
        final Assignment.Streams streams = new Assignment.Streams(
                1,
                streamIds.clone(),
                Map.copyOf(accounts),
                Map.of(),
                Map.of(),
                Arrays.copyOf(streamIds, Math.min(published, streamIds.length)));
        return () -> streams;
    }

    private static List<String> sourceCodes(final SecurityMaster securityMaster) {
        return Arrays.stream(securityMaster.getSources()).map(Source::code).toList();
    }

    private FeedSources feedSources(
            final Logger logger,
            final Properties properties,
            final SecurityMaster securityMaster,
            final ShardAccounts accounts,
            final EpochNanoClock wallClock,
            final Feeds feeds,
            final Clock clock,
            final CycleUploader uploader,
            final DirectBufferPool pool,
            final RawCaptureAgent rawCapture) {
        return new FeedSources(
                feeds,
                new FeedVenues.Context(
                        logger,
                        properties,
                        getInstance(SecretCache.class),
                        MarketDataCredentials.forShard(securityMaster, accounts::label),
                        wallClock,
                        SpecChanges.NONE),
                false,
                (status, cause) -> {},
                source -> (connection, maxFrameBytes) -> rawCapture.open(
                        connection,
                        new RawDataCollector(logger, clock, uploader, pool, source, SHARD_ID, connection, DISCARDED),
                        maxFrameBytes),
                CLIENTS);
    }

    private static ShardLatency shardLatency(final EpochNanoClock wallClock, final SecurityMaster securityMaster) {
        final Source[] all = securityMaster.getSources();
        final int[] ids = new int[all.length];
        final String[] codes = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            ids[i] = all[i].sourceId();
            codes[i] = all[i].code();
        }
        return new ShardLatency(wallClock, ids, codes);
    }

    private static ShardRuntime.StreamFactory streamFactory(
            final Logger logger,
            final Clock clock,
            final CycleUploader uploader,
            final DirectBufferPool pool,
            final ShardLatency latency) {
        return (streamId, sourceId, name) -> {
            final MarketDataCollector collector =
                    new MarketDataCollector(logger, clock, uploader, pool, streamId, RecordType.MBP_10, DISCARDED);
            return new ShardStream(
                    streamId,
                    sourceId,
                    name,
                    new SequencedRingBuffer<>(
                            Mbp10Schema::new,
                            new GlobalSequence(),
                            SequencedRingBuffer.DEFAULT_SIZE,
                            SequencedRingBuffer.Producers.TAKING_TURNS),
                    collector,
                    new StreamRecordEventAdapter(collector),
                    true,
                    latency);
        };
    }

    private static CollectorHeartbeat.Shard shardView(final ShardRuntime runtime, final BookProber prober) {
        return new CollectorHeartbeat.Shard() {
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
        };
    }

    private static void print(final SampleBuffer samples, final ByteBuffer json, final PrintStream out) {
        while (samples.pending() > 0) {
            json.clear();
            final int written = samples.writeTo(json, 1);
            if (written == 0) {
                samples.dropLargerThan(json.capacity());
                continue;
            }
            final String line = new String(json.array(), 0, json.position(), StandardCharsets.UTF_8);
            System.out.println(line);
            if (out != null) {
                out.println(line);
                out.flush();
            }
            samples.acknowledge(written);
        }
    }

    // A failed agent would leave the measurement wrong without saying so, so the whole run stops.
    private static ErrorHandler failed(final Logger logger, final String what) {
        return error -> {
            logger.logf(LogMessage.FATAL_ERROR_EXITING, "%s error: %s", what, CauseChain.of(error));
            new Thread(() -> System.exit(1), "standalone-exit").start();
        };
    }

    private static void close(final Logger logger, final GnomeAgentRunner runner, final String what) {
        try {
            runner.close();
        } catch (Exception error) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Failed to stop the %s: %s", what, error);
        }
    }

    private static long wallNanos() {
        return TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
    }

    /** Takes every upload and keeps none, so a study run never writes into the collection's buckets. */
    static final class DiscardingS3 implements S3Client {
        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {}

        @Override
        public PutObjectResponse putObject(final PutObjectRequest request, final RequestBody body) {
            return PutObjectResponse.builder().build();
        }
    }
}
