package group.gnometrading.collectors;

import group.gnometrading.aeron.MarketDataPathAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectState;
import group.gnometrading.schemas.MessageHeaderEncoder;
import group.gnometrading.schemas.ShardStatusEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Sends a shard's leased streams to the sessions reading them over Aeron: reads every stream's ring a second time,
 * offering each record to the stream's publication when it has one, and every {@link #STATUS_EVERY_NANOS} sends the
 * shard's {@code ShardStatus} (core schema): that it is alive, and for each leased stream its state, its publication
 * and that publication's position (a session behind it has stalled), and the records sessions never got.
 *
 * <p>Never blocks collection: the reader doesn't gate the rings. A record its publication has no room for waits in the
 * ring for the next pass, and the stream's later records behind it; one the ring laps first is dropped and counted.
 * Every stream the runtime starts is read here, leased or not.
 */
final class StreamPublisherAgent implements MarketDataPathAgent {

    static final long STATUS_EVERY_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final long RETIRE_GRACE_NANOS = MarketDataCollectorAgent.RETIRE_GRACE_NANOS;
    /** Streams per status message: keeps one inside an Aeron frame (MTU 1408). */
    static final int MAX_STREAMS_PER_STATUS = 50;
    /** Subject state for a stream whose market isn't placed yet. */
    static final short NO_STATE = 255;

    static final int STATUS_CAPACITY = MessageHeaderEncoder.ENCODED_LENGTH
            + ShardStatusEncoder.BLOCK_LENGTH
            + ShardStatusEncoder.StreamsEncoder.HEADER_SIZE
            + MAX_STREAMS_PER_STATUS * ShardStatusEncoder.StreamsEncoder.sbeBlockLength();

    private record Join(ShardStream stream) {}

    private record Leave(ShardStream stream, long atNanos) {}

    private final StreamPublication status;
    private final LongSupplier generation;
    private final LongSupplier nanoTime;
    private final LongSupplier wallNanos;
    private final ConcurrentLinkedQueue<Object> commands = new ConcurrentLinkedQueue<>();
    private final List<ShardStream> streams = new ArrayList<>();
    private final List<Leave> leaving = new ArrayList<>();
    private final UnsafeBuffer frame = new UnsafeBuffer(new byte[STATUS_CAPACITY]);
    private final MessageHeaderEncoder header = new MessageHeaderEncoder();
    private final ShardStatusEncoder statusEncoder = new ShardStatusEncoder();
    private int[] published = new int[64];
    private long nextStatusNanos;

    /**
     * @param status the shard's status publication
     * @param generation the assignment generation the runtime last applied
     * @param nanoTime a monotonic clock, for pacing
     * @param wallNanos epoch nanoseconds, stamped on the status
     */
    StreamPublisherAgent(
            final StreamPublication status,
            final LongSupplier generation,
            final LongSupplier nanoTime,
            final LongSupplier wallNanos) {
        this.status = status;
        this.generation = generation;
        this.nanoTime = nanoTime;
        this.wallNanos = wallNanos;
    }

    void add(final ShardStream stream) {
        commands.add(new Join(stream));
    }

    void retire(final ShardStream stream) {
        commands.add(new Leave(stream, nanoTime.getAsLong() + RETIRE_GRACE_NANOS));
    }

    @Override
    public int doWork() throws Exception {
        Object command;
        while ((command = commands.poll()) != null) {
            if (command instanceof Join join) {
                streams.add(join.stream());
            } else if (command instanceof Leave leave) {
                streams.remove(leave.stream());
                leaving.add(leave);
            }
        }
        final long now = nanoTime.getAsLong();
        int work = 0;
        for (int i = 0; i < streams.size(); i++) {
            work += streams.get(i).publish(now);
        }
        for (int i = leaving.size() - 1; i >= 0; i--) {
            final Leave leave = leaving.get(i);
            final int drained = leave.stream().publish(now);
            work += drained;
            if (drained == 0 && now >= leave.atNanos()) {
                leaving.remove(i);
            }
        }
        if (now >= nextStatusNanos) {
            nextStatusNanos = now + STATUS_EVERY_NANOS;
            sendStatus();
            work++;
        }
        return work;
    }

    /** Every published stream's state and position, in as many messages as it takes; one naming none says alive. */
    private void sendStatus() {
        final long sentNanos = wallNanos.getAsLong();
        final long applied = generation.getAsLong();
        int count = 0;
        for (int i = 0; i < streams.size(); i++) {
            if (streams.get(i).publication() != null) {
                if (count == published.length) {
                    published = Arrays.copyOf(published, count * 2);
                }
                published[count++] = i;
            }
        }
        int from = 0;
        do {
            final int batch = Math.min(MAX_STREAMS_PER_STATUS, count - from);
            final ShardStatusEncoder.StreamsEncoder entries = statusEncoder
                    .wrapAndApplyHeader(frame, 0, header)
                    .sentNanos(sentNanos)
                    .generation(applied)
                    .streamsCount(batch);
            for (int k = from; k < from + batch; k++) {
                final ShardStream stream = streams.get(published[k]);
                final StreamPublication publication = stream.publication();
                entries.next()
                        .streamId(stream.streamId())
                        .state(stateOf(stream))
                        .position(publication == null ? 0 : publication.position())
                        .publicationSessionId(publication == null ? 0 : publication.sessionId())
                        .dropped(stream.dropped())
                        .reconnecting(reconnecting(stream) ? (short) 1 : (short) 0);
            }
            status.offer(frame, 0, MessageHeaderEncoder.ENCODED_LENGTH + statusEncoder.encodedLength());
            from += batch;
        } while (from < count);
    }

    /** As the session heartbeat reads a market: not delivering unless trusted, but a market with no book is fine. */
    private static boolean reconnecting(final ShardStream stream) {
        final Subject subject = stream.subject();
        if (subject == null) {
            return false;
        }
        final SubjectState state = subject.state();
        return !state.trusted() && state != SubjectState.EMPTY;
    }

    private static short stateOf(final ShardStream stream) {
        final Subject subject = stream.subject();
        return subject == null ? NO_STATE : (short) subject.state().ordinal();
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.HOT_PATH;
    }

    @Override
    public String roleName() {
        return "stream-publisher";
    }
}
