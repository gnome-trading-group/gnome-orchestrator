package group.gnometrading.collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.collector.CycleUploader;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.host.HostReadings;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.utils.CauseChain;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * What a shard is doing, logged every {@link #INTERVAL_SECONDS}: one {@code HEARTBEAT} line for the process, one per
 * connection, and one per stream whose state changed since the last (every stream on the first), each a JSON object
 * with a {@code kind}. A line each keeps every one under the logger's 4 KB cut however many streams the shard runs, and
 * lets a log query filter by stream or connection; logging only changes keeps a large shard's log volume flat. Every
 * stream's numbers are in the shard's status ({@link ShardStatusWriter}).
 *
 * <p>Runs on its own thread, so its allocation stays off the threads that read and record data.
 */
final class CollectorHeartbeat implements Runnable {

    static final long INTERVAL_SECONDS = 15;
    private static final long MB = 1024 * 1024;
    // A stream whose collector failed; its market's own states are SubjectState's.
    static final String FAILED = "FAILED";

    /** A connection as the heartbeat reports it. */
    /** @param account the market-data account it signs in with; null for a public feed */
    record Connection(String name, String source, String account, int markets, boolean reconnecting) {}

    /** What the heartbeat reads from the shard. */
    interface Shard {
        List<ShardStream> streams();

        List<Connection> connections();

        long generation();

        long probes();

        long probeMismatches();
    }

    private final Logger logger;
    private final ObjectMapper mapper;
    private final Shard shard;
    private final CycleUploader uploader;
    private final HostReadings host;
    private final SchedulingLatencyProbe probe;
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private Map<Integer, Long> previousRecords = new HashMap<>();
    private Map<Integer, String> previousStates = new HashMap<>();
    private long previousNanos;
    // The latest process line, which the status repeats; set on the heartbeat's thread, read by the status writer's.
    private volatile String processLine;

    CollectorHeartbeat(
            Logger logger,
            ObjectMapper mapper,
            Shard shard,
            CycleUploader uploader,
            HostReadings host,
            SchedulingLatencyProbe probe,
            long startNanos) {
        this.logger = logger;
        this.mapper = mapper;
        this.shard = shard;
        this.uploader = uploader;
        this.host = host;
        this.probe = probe;
        this.previousNanos = startNanos;
    }

    @Override
    public void run() {
        try {
            for (String line : lines(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()))) {
                logger.logf(LogMessage.DEBUG, "HEARTBEAT %s", line);
            }
        } catch (Throwable error) {
            // A scheduled task that throws is never run again; one bad sample mustn't end the heartbeat.
            logger.logf(LogMessage.UNKNOWN_ERROR, "Heartbeat failed: %s", CauseChain.of(error));
        }
    }

    /** This interval's lines, process first; {@code wallNanos} is wall-clock time, as event and receive times are. */
    List<String> lines(final long nowNanos, final long wallNanos) throws JsonProcessingException {
        final double seconds = Math.max(1, nowNanos - previousNanos) / 1e9;
        previousNanos = nowNanos;
        host.sample();

        final List<ShardStream> streams = shard.streams();
        final Map<String, Integer> byState = new TreeMap<>();
        final Map<Integer, Long> records = new HashMap<>();
        final Map<Integer, String> states = new HashMap<>();
        final List<String> streamLines = new ArrayList<>();
        for (ShardStream stream : streams) {
            final Subject subject = stream.subject();
            final String state = StreamTransitions.name(StreamTransitions.stateOf(stream));
            byState.merge(state, 1, Integer::sum);
            states.put(stream.streamId(), state);
            final long total = stream.records();
            records.put(stream.streamId(), total);
            final long delta = Math.max(0, total - previousRecords.getOrDefault(stream.streamId(), total));
            if (state.equals(previousStates.get(stream.streamId()))) {
                continue;
            }

            final Map<String, Object> line = new LinkedHashMap<>();
            line.put("kind", "stream");
            line.put("stream_id", stream.streamId());
            line.put("name", stream.name());
            line.put("state", state);
            line.put("last_event_age_s", age(wallNanos, stream.lastEventNanos()));
            line.put("msgs_per_s", Math.round(delta / seconds * 10) / 10.0);
            line.put("records", total);
            if (subject != null) {
                line.put("last_receive_age_s", age(wallNanos, subject.lastReceiveNanos()));
                line.put("last_verified_age_s", age(wallNanos, subject.lastVerifiedNanos()));
                line.put("resyncs", subject.resyncs());
            }
            if (stream.failure() != null) {
                line.put("failure", CauseChain.of(stream.failure()));
            }
            streamLines.add(mapper.writeValueAsString(line));
        }
        previousRecords = records;
        previousStates = states;

        final List<Connection> connections = shard.connections();
        final List<String> connectionLines = new ArrayList<>(connections.size());
        int reconnecting = 0;
        for (Connection connection : connections) {
            if (connection.reconnecting()) {
                reconnecting++;
            }
            connectionLines.add(mapper.writeValueAsString(connectionLine(connection)));
        }

        final Map<String, Object> process = new LinkedHashMap<>();
        process.put("kind", "process");
        process.put("generation", shard.generation());
        process.put("streams", streams.size());
        process.put("states", byState);
        process.put("connections", connections.size());
        process.put("reconnecting", reconnecting);
        process.put("probes", shard.probes());
        process.put("probe_mismatches", shard.probeMismatches());
        process.put("heap_used_mb", host.heapUsedBytes() / MB);
        process.put("heap_max_mb", host.heapMaxBytes() / MB);
        process.put("rss_mb", megabytes(host.processRssBytes()));
        process.put("cpu_busy_permille", host.cpuBusyPermille());
        process.put("cpu_steal_permille", host.cpuStealPermille());
        final Map<String, Object> gc = new LinkedHashMap<>();
        for (GarbageCollectorMXBean collector : collectors) {
            gc.put(
                    collector.getName(),
                    Map.of("count", collector.getCollectionCount(), "ms", collector.getCollectionTime()));
        }
        process.put("gc", gc);
        process.put("uploads_queued", uploader.queuedUploads());
        process.put("uploads_failed", uploader.failedUploads());
        process.put("sched_latency", latency(probe == null ? null : probe.latest()));

        processLine = mapper.writeValueAsString(process);
        final List<String> lines = new ArrayList<>(streamLines.size() + connectionLines.size() + 1);
        lines.add(processLine);
        lines.addAll(connectionLines);
        lines.addAll(streamLines);
        return lines;
    }

    /** The latest process line's JSON; null before the first heartbeat. */
    String processLine() {
        return processLine;
    }

    /** Seconds since {@code thenNanos}, to a tenth; null if it never happened. */
    private static Double age(final long wallNanos, final long thenNanos) {
        return thenNanos == 0 ? null : Math.round((wallNanos - thenNanos) / 1e8) / 10.0;
    }

    static Map<String, Object> latency(final SchedulingLatencyProbe.Window window) {
        if (window == null) {
            return null;
        }
        final Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("window_s", Math.round(window.windowSeconds() * 1000) / 1000.0);
        latency.put("samples", window.samples());
        latency.put("p50_us", window.p50Micros());
        latency.put("p99_us", window.p99Micros());
        latency.put("p999_us", window.p999Micros());
        latency.put("max_us", window.maxMicros());
        latency.put("over1ms", window.over1ms());
        latency.put("over5ms", window.over5ms());
        return latency;
    }

    private static long megabytes(final long bytes) {
        return bytes == HostReadings.UNAVAILABLE ? HostReadings.UNAVAILABLE : bytes / MB;
    }

    private static Map<String, Object> connectionLine(final Connection connection) {
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("kind", "connection");
        line.put("name", connection.name());
        line.put("source", connection.source());
        if (connection.account() != null) {
            line.put("account", connection.account());
        }
        line.put("markets", connection.markets());
        line.put("reconnecting", connection.reconnecting());
        return line;
    }
}
