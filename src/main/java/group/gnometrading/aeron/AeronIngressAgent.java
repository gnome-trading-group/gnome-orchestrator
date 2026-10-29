package group.gnometrading.aeron;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import io.aeron.Subscription;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.Header;
import java.util.Arrays;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

/**
 * Polls every Aeron subscription a process reads (a session's shard streams, later its order-gateway replies) on one
 * thread, handing each message to its subscription's {@link Sink}. A sink that has no room leaves the message in
 * Aeron's log to be offered again on the next pass, so nothing is dropped or reordered here: a reader that stays behind
 * long enough falls off the end of the shard's term, which the session sees as a stalled stream (Phase 10, §3).
 *
 * <p>Subscriptions are added and removed from any thread; the change takes effect on the agent's next pass. Zero
 * allocation on the poll path once added.
 */
public final class AeronIngressAgent implements GnomeAgent {

    /** Where one subscription's messages go. */
    @FunctionalInterface
    public interface Sink {
        /** Takes a whole message, or returns false, taking nothing, to be offered it again later. */
        boolean accept(DirectBuffer buffer, int offset, int length);
    }

    private static final int FRAGMENT_LIMIT = 64;
    private static final int MAX_CHANGES = 1024;

    private final ManyToOneConcurrentArrayQueue<Change> changes = new ManyToOneConcurrentArrayQueue<>(MAX_CHANGES);
    private Entry[] entries = new Entry[0];
    private long refused;
    private long fragmented;

    public void add(final Subscription subscription, final Sink sink) {
        offer(new Change(new Entry(subscription, sink), true));
    }

    /** Stops polling {@code subscription}; the caller closes it once the agent has let go (or on its own thread). */
    public void remove(final Subscription subscription) {
        offer(new Change(new Entry(subscription, null), false));
    }

    @Override
    public int doWork() {
        int work = applyChanges();
        final Entry[] current = this.entries;
        for (final Entry entry : current) {
            work += entry.subscription.controlledPoll(entry, FRAGMENT_LIMIT);
        }
        return work;
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.HOT_PATH;
    }

    @Override
    public String roleName() {
        return "aeron-ingress";
    }

    /** Messages a sink had no room for, each counted every time it was offered again. */
    public long refused() {
        return this.refused;
    }

    /** Messages that arrived in fragments and were dropped: every record fits one frame, so these shouldn't exist. */
    public long fragmented() {
        return this.fragmented;
    }

    private void offer(final Change change) {
        if (!this.changes.offer(change)) {
            throw new IllegalStateException("Too many subscription changes waiting for the ingress agent");
        }
    }

    private int applyChanges() {
        Change change;
        int applied = 0;
        while ((change = this.changes.poll()) != null) {
            this.entries = change.add ? append(this.entries, change.entry) : without(this.entries, change.entry);
            applied++;
        }
        return applied;
    }

    private static Entry[] append(final Entry[] entries, final Entry entry) {
        final Entry[] grown = Arrays.copyOf(entries, entries.length + 1);
        grown[entries.length] = entry;
        return grown;
    }

    private static Entry[] without(final Entry[] entries, final Entry removed) {
        int kept = 0;
        final Entry[] shrunk = new Entry[entries.length];
        for (final Entry entry : entries) {
            if (entry.subscription != removed.subscription) {
                shrunk[kept++] = entry;
            }
        }
        return Arrays.copyOf(shrunk, kept);
    }

    private record Change(Entry entry, boolean add) {}

    private final class Entry implements ControlledFragmentHandler {
        private final Subscription subscription;
        private final Sink sink;

        Entry(final Subscription subscription, final Sink sink) {
            this.subscription = subscription;
            this.sink = sink;
        }

        @Override
        public Action onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header) {
            if ((header.flags() & FrameDescriptor.UNFRAGMENTED) != FrameDescriptor.UNFRAGMENTED) {
                AeronIngressAgent.this.fragmented++;
                return Action.CONTINUE;
            }
            if (this.sink.accept(buffer, offset, length)) {
                return Action.CONTINUE;
            }
            AeronIngressAgent.this.refused++;
            return Action.ABORT;
        }
    }
}
