package group.gnometrading.orchestrator.runtime;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.utils.CauseChain;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * A process without a registry heartbeat (an order gateway, a shard) sends its samples itself: on its own thread, every
 * {@link #TICK_MILLIS} it gives each sampler its turn ({@code sampleIfDue(nowMs)}), and every post interval it posts
 * what has collected, {@code {"subjectKind", "subjectId", "samples": [...]}}, to the samples API. Samples stay queued
 * until a post carrying them is accepted, so an outage loses only what the buffer's cap drops (oldest first, counted).
 *
 * <p>Allocates nothing once constructed, as the processes it runs in are GC-free; only logging an error does.
 */
public final class SamplePoster implements Runnable {

    /** How often samplers get their turn: their own intervals are coarser, and a ring's peak backlog is looked at each. */
    public static final long TICK_MILLIS = 1_000;

    /** The samples API's limits on one post. */
    static final int MAX_POST_BYTES = 128 * 1024;

    static final int MAX_SAMPLES_PER_POST = 300;
    // A backlog after an outage drains a few posts per tick rather than one per interval.
    private static final int MAX_POSTS_PER_TICK = 4;
    private static final int HTTP_OK = 200;
    private static final int CLOSING_BYTES = 2;

    /** Sends one post's body; the HTTP status, or a negative number when nothing answered. Never throws. */
    @FunctionalInterface
    public interface Sender {
        int post(byte[] body, int length);
    }

    private final Logger logger;
    private final String subjectKind;
    private final String subjectId;
    private final SampleBuffer samples;
    private final LongPredicate[] samplers;
    private final Sender sender;
    private final LongSupplier clockMs;
    private final long postIntervalMs;
    private final ByteBuffer body = ByteBuffer.allocate(MAX_POST_BYTES);
    private final JsonEncoder json = new JsonEncoder();

    private ScheduledExecutorService thread;
    private long nextPostMs;
    private boolean failing;
    private long posted;
    private long failedPosts;

    public SamplePoster(
            final Logger logger,
            final String subjectKind,
            final String subjectId,
            final SampleBuffer samples,
            final List<LongPredicate> samplers,
            final Sender sender,
            final LongSupplier clockMs,
            final long postIntervalMs) {
        this.logger = logger;
        this.subjectKind = subjectKind;
        this.subjectId = subjectId;
        this.samples = samples;
        this.samplers = samplers.toArray(new LongPredicate[0]);
        this.sender = sender;
        this.clockMs = clockMs;
        this.postIntervalMs = postIntervalMs;
    }

    /** Runs it on a daemon thread of its own, every {@link #TICK_MILLIS}. */
    public void start(final String threadName) {
        thread = DaemonThreads.scheduler(threadName);
        thread.scheduleWithFixedDelay(this, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * At shutdown: stops the thread, letting a tick in progress finish, then samples and posts once more (on the
     * caller's thread, the samplers' readings being for one thread at a time). Call it before what samplers read
     * (the media driver) closes.
     */
    public void stop(final long timeoutMs) {
        if (thread == null) {
            return;
        }
        thread.shutdown();
        try {
            if (thread.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        final long now = clockMs.getAsLong();
        for (final LongPredicate sampler : samplers) {
            // A scheduled task that throws is never run again: one sampler's failure mustn't end every sample.
            try {
                sampler.test(now);
            } catch (Throwable error) {
                logger.logf(LogMessage.UNKNOWN_ERROR, "Sampler failed: %s", CauseChain.of(error));
            }
        }
        if (now < nextPostMs) {
            return;
        }
        nextPostMs = now + postIntervalMs;
        try {
            for (int i = 0; i < MAX_POSTS_PER_TICK && samples.pending() > 0; i++) {
                if (!postOnce()) {
                    return;
                }
            }
        } catch (Throwable error) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Posting samples failed: %s", CauseChain.of(error));
        }
    }

    /** Samples every sampler once more and posts what waits, so a stop doesn't lose the minutes before it. */
    void flush() {
        nextPostMs = 0;
        run();
    }

    /** Posts the oldest waiting samples; whether the samples API took them. */
    boolean postOnce() {
        final int carried = encode();
        final int status = sender.post(body.array(), body.position());
        if (status == HTTP_OK) {
            samples.acknowledge(carried);
            posted += carried;
            if (failing) {
                logger.logf(
                        LogMessage.DEBUG,
                        "Samples for %s %s are posting again; %d dropped for room so far",
                        subjectKind,
                        subjectId,
                        samples.dropped());
            }
            failing = false;
            return true;
        }
        failedPosts++;
        if (!failing) {
            // Once per outage; the buffer holds what it can meanwhile.
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Samples for %s %s couldn't be posted: the samples API answered %d",
                    subjectKind,
                    subjectId,
                    status);
            failing = true;
        }
        return false;
    }

    /** Writes the body; returns how many samples it carries. Package-private for tests. */
    int encode() {
        body.clear();
        json.wrap(body);
        json.writeObjectStart()
                .writeObjectEntry("subjectKind", subjectKind)
                .writeComma()
                .writeObjectEntry("subjectId", subjectId)
                .writeComma()
                .writeString("samples")
                .writeColon()
                .writeArrayStart();
        body.limit(body.capacity() - CLOSING_BYTES);
        final int carried = samples.writeTo(body, MAX_SAMPLES_PER_POST);
        body.limit(body.capacity());
        json.writeArrayEnd().writeObjectEnd();
        return carried;
    }

    ByteBuffer body() {
        return body;
    }

    /** Samples the API has accepted since start. */
    public long posted() {
        return posted;
    }

    /** Posts that failed since start. */
    public long failedPosts() {
        return failedPosts;
    }
}
