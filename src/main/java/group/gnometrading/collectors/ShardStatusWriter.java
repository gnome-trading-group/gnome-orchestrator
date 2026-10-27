package group.gnometrading.collectors;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.utils.CauseChain;
import java.io.ByteArrayInputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.LongSupplier;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Writes what the shard is doing to {@code s3://<bucket>/collection/shards/<shard>/status/<task>.json} every
 * {@link CollectorHeartbeat#INTERVAL_SECONDS}, overwriting it: the reconciler reads it to see which streams are live,
 * extend and close their coverage, and find the shard's private IP. One object per task, so a replacement task never
 * overwrites the last word of the one it replaced.
 *
 * <p>Every stream is in it, with wall-clock nanos for its latest event, receive and verification, and the latest
 * {@link StreamTransitions} (each {@code [stream, from, to, at_ns]}). Encoded with gnome-core's {@link JsonEncoder} into
 * a reused buffer and sent from it, so only the upload allocates; the buffer doubles if a status outgrows it.
 */
final class ShardStatusWriter implements Runnable {

    private static final int INITIAL_CAPACITY = 64 * 1024;

    /** Who is writing: fixed for the task's life. */
    record Identity(String shard, String task, String privateIp, String version, long startedAtNanos) {}

    /** What the status reads from the shard. */
    interface Shard {
        List<ShardStream> streams();

        List<CollectorHeartbeat.Connection> connections();

        long generation();

        /** The heartbeat's latest process line, as JSON; null before the first. */
        String processLine();
    }

    private final Logger logger;
    private final S3Client s3;
    private final String bucket;
    private final String key;
    private final Identity identity;
    private final Shard shard;
    private final StreamTransitions transitions;
    private final LongSupplier wallClock;
    private final JsonEncoder json = new JsonEncoder();
    private ByteBuffer buffer = ByteBuffer.allocate(INITIAL_CAPACITY);
    private final int[] transitionIds = new int[StreamTransitions.CAPACITY];
    private final byte[] transitionFrom = new byte[StreamTransitions.CAPACITY];
    private final byte[] transitionTo = new byte[StreamTransitions.CAPACITY];
    private final long[] transitionAt = new long[StreamTransitions.CAPACITY];

    ShardStatusWriter(
            final Logger logger,
            final S3Client s3,
            final String bucket,
            final Identity identity,
            final Shard shard,
            final StreamTransitions transitions,
            final LongSupplier wallClock) {
        this.logger = logger;
        this.s3 = s3;
        this.bucket = bucket;
        this.key = keyFor(identity.shard(), identity.task());
        this.identity = identity;
        this.shard = shard;
        this.transitions = transitions;
        this.wallClock = wallClock;
    }

    static String keyFor(final String shard, final String task) {
        return "collection/shards/" + shard + "/status/" + task + ".json";
    }

    @Override
    public void run() {
        try {
            final int length = build();
            final byte[] body = buffer.array();
            s3.putObject(
                    request -> request.bucket(bucket).key(key).contentType("application/json"),
                    RequestBody.fromContentProvider(
                            () -> new ByteArrayInputStream(body, 0, length), length, "application/json"));
        } catch (Throwable error) {
            // Runs on a schedule that a throw would end; the next interval tries again.
            logger.logf(LogMessage.UNKNOWN_ERROR, "Shard status failed: %s", CauseChain.of(error));
        }
    }

    /** Encodes the status at the start of {@link #bytes()}; returns its length. */
    int build() {
        while (true) {
            buffer.clear();
            json.wrap(buffer);
            try {
                encode();
                return buffer.position();
            } catch (BufferOverflowException tooSmall) {
                // A shard's streams only grow its status now and then, so this rarely runs again.
                buffer = ByteBuffer.allocate(buffer.capacity() * 2);
            }
        }
    }

    byte[] bytes() {
        return buffer.array();
    }

    private void encode() {
        json.writeObjectStart();
        json.writeObjectEntry("shard_id", identity.shard()).writeComma();
        json.writeObjectEntry("task_id", identity.task()).writeComma();
        stringOrNull("private_ip", identity.privateIp());
        json.writeComma().writeObjectEntry("version", identity.version()).writeComma();
        json.writeObjectEntry("started_at_ns", identity.startedAtNanos()).writeComma();
        json.writeObjectEntry("written_at_ns", wallClock.getAsLong()).writeComma();
        json.writeObjectEntry("applied_generation", shard.generation()).writeComma();
        json.writeString("process").writeColon();
        final String process = shard.processLine();
        if (process == null) {
            json.writeNull();
        } else {
            // Already JSON (the heartbeat's own line, all ASCII): copied as it is.
            for (int i = 0; i < process.length(); i++) {
                buffer.put((byte) process.charAt(i));
            }
        }
        json.writeComma();
        encodeConnections();
        json.writeComma();
        encodeStreams();
        json.writeComma();
        encodeTransitions();
        json.writeObjectEnd();
    }

    private void encodeConnections() {
        json.writeString("connections").writeColon().writeArrayStart();
        final List<CollectorHeartbeat.Connection> connections = shard.connections();
        for (int i = 0; i < connections.size(); i++) {
            final CollectorHeartbeat.Connection connection = connections.get(i);
            if (i > 0) {
                json.writeComma();
            }
            json.writeObjectStart();
            json.writeObjectEntry("name", connection.name()).writeComma();
            json.writeObjectEntry("source", connection.source()).writeComma();
            stringOrNull("account", connection.account());
            json.writeComma().writeObjectEntry("markets", connection.markets()).writeComma();
            json.writeObjectEntry("reconnecting", connection.reconnecting());
            json.writeObjectEnd();
        }
        json.writeArrayEnd();
    }

    private void encodeStreams() {
        json.writeString("streams").writeColon().writeArrayStart();
        final List<ShardStream> streams = shard.streams();
        for (int i = 0; i < streams.size(); i++) {
            final ShardStream stream = streams.get(i);
            final Subject subject = stream.subject();
            if (i > 0) {
                json.writeComma();
            }
            json.writeObjectStart();
            json.writeObjectEntry("id", stream.streamId()).writeComma();
            json.writeObjectEntry("state", StreamTransitions.name(StreamTransitions.stateOf(stream)))
                    .writeComma();
            json.writeObjectEntry("last_event_ns", stream.lastEventNanos()).writeComma();
            json.writeObjectEntry("last_receive_ns", subject == null ? 0 : subject.lastReceiveNanos())
                    .writeComma();
            json.writeObjectEntry("last_verified_ns", subject == null ? 0 : subject.lastVerifiedNanos())
                    .writeComma();
            json.writeObjectEntry("resyncs", subject == null ? 0 : subject.resyncs())
                    .writeComma();
            json.writeObjectEntry("records", stream.records());
            final Throwable failure = stream.failure();
            if (failure != null) {
                json.writeComma().writeObjectEntry("failure", CauseChain.of(failure));
            }
            json.writeObjectEnd();
        }
        json.writeArrayEnd();
    }

    private void encodeTransitions() {
        final long recorded = transitions.recorded();
        final int kept = transitions.copyTo(transitionIds, transitionFrom, transitionTo, transitionAt);
        json.writeObjectEntry("transitions_recorded", recorded).writeComma();
        json.writeString("transitions").writeColon().writeArrayStart();
        for (int i = 0; i < kept; i++) {
            if (i > 0) {
                json.writeComma();
            }
            json.writeArrayStart().writeNumber(transitionIds[i]).writeComma();
            json.writeString(StreamTransitions.name(transitionFrom[i])).writeComma();
            json.writeString(StreamTransitions.name(transitionTo[i])).writeComma();
            json.writeNumber(transitionAt[i]).writeArrayEnd();
        }
        json.writeArrayEnd();
    }

    private void stringOrNull(final String name, final String value) {
        if (value == null) {
            json.writeString(name).writeColon().writeNull();
        } else {
            json.writeObjectEntry(name, value);
        }
    }
}
