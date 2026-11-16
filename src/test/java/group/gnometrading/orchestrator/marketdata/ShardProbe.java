package group.gnometrading.orchestrator.marketdata;

import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.orchestrator.aeron.AeronPathIdle;
import group.gnometrading.orchestrator.aeron.AeronPorts;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.HostAddress;
import group.gnometrading.orchestrator.runtime.AgentRuntimeInstaller;
import group.gnometrading.orchestrator.runtime.LatencySampler;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedPoller;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.utils.SampledEpochNanoClock;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SystemEpochNanoClock;

/**
 * Reads streams from one shard, as a session or paper gateway does, on a host of its own, and prints each minute's
 * {@code latency.feed} ({@code feed.network}: arrival minus the shard's send stamp; {@code feed.shard_wait}: send
 * stamp minus the shard's receipt) and {@code feed.shards} as JSON lines, so a shard's latency can be measured without
 * a consumer's own work mixed in. Read-only: it joins publications a lease already holds open (the shard publishes only
 * leased streams), adding nothing to the shard.
 *
 * <p>{@code -Dshard=<private ip>} (its control port is {@link AeronPorts#SHARD_CONTROL}, or {@code ip:port}),
 * {@code -Dstreams=1,2,3}, {@code -Dminutes=60}, {@code -Dpark.micros=50} (the most its Aeron-path threads park when
 * idle), {@code -Dprofile=standard|latency} (the media driver's), {@code -Ddrain=spin|park} (whether the thread
 * emptying its ring spins or backs off like the Aeron-path agents; a spinning drain takes a core the drivers may need),
 * {@code -Dout=<file>} (also appended to).
 *
 * <p>Runs from a release jar plus this class: {@code java -cp gnome-orchestrator-<v>.jar:probe-classes
 * group.gnometrading.orchestrator.marketdata.ShardProbe}, with the jar's module access ({@code Add-Opens} in its
 * manifest) given as {@code --add-opens} flags, since {@code -cp} skips the manifest.
 */
public final class ShardProbe {

    private static final int RING_SIZE = 1 << 14;
    private static final int OUT_BYTES = 1 << 20;

    private ShardProbe() {}

    public static void main(final String[] args) throws Exception {
        final String shard = required("shard");
        final String hostPort = shard.contains(":") ? shard : shard + ":" + AeronPorts.SHARD_CONTROL;
        final int[] streams = Arrays.stream(required("streams").split(","))
                .map(String::trim)
                .mapToInt(Integer::parseInt)
                .toArray();
        final long minutes = Long.getLong("minutes", 60);
        final AeronPathIdle idle = new AeronPathIdle(TimeUnit.MICROSECONDS.toNanos(Long.getLong("park.micros", 50)));
        final AeronProfile profile =
                "latency".equals(System.getProperty("profile")) ? AeronProfile.LATENCY : AeronProfile.STANDARD;
        final String outFile = System.getProperty("out");
        final IdleStrategy drainIdle =
                "park".equals(System.getProperty("drain")) ? idle.agentStrategy() : new BusySpinIdleStrategy();

        final Logger logger = new ConsoleLogger(new SystemEpochNanoClock());
        AgentRuntimeInstaller.installStandard(logger, idle);
        final SampleBuffer samples = new SampleBuffer(4 << 20, 10_000, 256 << 10);
        final FeedMetrics metrics = new FeedMetrics();
        final SequencedRingBuffer<Mbp10Schema> ring =
                new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence(), RING_SIZE);
        final AtomicBoolean running = new AtomicBoolean(true);
        final long[] received = {0};
        final SequencedPoller drain = ring.createPoller((sequence, templateId, buffer, length) -> received[0]++);
        final Thread drainer = new Thread(
                () -> {
                    while (running.get()) {
                        try {
                            drainIdle.idle(drain.poll());
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    }
                },
                "probe-drain");
        drainer.setDaemon(true);

        try (AeronRuntime aeron = AeronRuntime.launch(
                        "probe",
                        profile,
                        idle,
                        error -> logger.logf(LogMessage.UNKNOWN_ERROR, "Aeron error: %s", error));
                PrintStream out = outFile == null ? null : new PrintStream(new FileOutputStream(outFile, true))) {
            final ShardMarketData marketData = ShardMarketData.shared(
                    logger,
                    aeron,
                    HostAddress.privateIp(),
                    ring,
                    System::nanoTime,
                    metrics,
                    SampledEpochNanoClock.INSTANCE);
            final LatencySampler latency = new LatencySampler(samples, metrics.sets(), FeedMetrics.kinds());
            final FeedSampler feed = marketData.sampler(samples, TimeUnit.MINUTES.toMillis(1));
            drainer.start();
            marketData.start(error -> logger.logf(LogMessage.UNKNOWN_ERROR, "Feed error: %s", error));
            marketData.addSession("probe", () -> MarketDataLeases.local(hostPort, streams), streams);
            logger.logf(
                    LogMessage.DEBUG,
                    "Probe reading %d streams from %s for %d min (park %d µs, %s driver, %s drain)",
                    streams.length,
                    hostPort,
                    minutes,
                    idle.maxParkNanos() / 1_000,
                    profile,
                    drainIdle.alias());

            final ByteBuffer json = ByteBuffer.allocate(OUT_BYTES);
            final long endMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes);
            while (System.currentTimeMillis() < endMs) {
                Thread.sleep(1_000);
                final long nowMs = System.currentTimeMillis();
                latency.sampleIfDue(nowMs);
                feed.sampleIfDue(nowMs);
                print(samples, json, out);
            }
            logger.logf(LogMessage.DEBUG, "Probe done: %d records", received[0]);
            running.set(false);
            marketData.close();
        }
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

    private static String required(final String property) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("-D" + property + " is required");
        }
        return value;
    }
}
