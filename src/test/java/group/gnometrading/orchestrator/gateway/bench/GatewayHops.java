package group.gnometrading.orchestrator.gateway.bench;

import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.RequestTiming;
import group.gnometrading.gateways.ratelimit.RequestClass;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Each request's hops inside the gateway, from the lane's own stamps ({@link RequestTiming}, all on the gateway's nano
 * clock): ingress to the lane taking it, the budget's decision, signing and writing, the venue's answer, and how late
 * the lane may have found that answer. Recorded on the lane's thread into preallocated arrays.
 */
public final class GatewayHops implements AccountLane.Admissions {

    /** Exact samples of one hop, nanoseconds. */
    public static final class Hop {
        private final long[] values;
        private int count;

        Hop(final int capacity) {
            this.values = new long[capacity];
        }

        synchronized void add(final long nanos) {
            if (nanos >= 0 && this.count < this.values.length) {
                this.values[this.count++] = nanos;
            }
        }

        public synchronized long[] sorted() {
            final long[] copy = Arrays.copyOf(this.values, this.count);
            Arrays.sort(copy);
            return copy;
        }

        synchronized void clear() {
            this.count = 0;
        }
    }

    private static final int CAPACITY = 1 << 20;
    private static final String[] HOPS = {"queue", "budget", "sign_send", "venue_answer", "answer_slack"};

    private final Map<String, Hop> hops = new LinkedHashMap<>();
    // By request (NEW, CANCEL), then hop in HOPS' order: looked up without building a key on the lane's thread.
    private final Hop[][] byRequest = new Hop[2][HOPS.length];

    public GatewayHops() {
        final String[] requests = {"NEW", "CANCEL"};
        for (int r = 0; r < requests.length; r++) {
            for (int h = 0; h < HOPS.length; h++) {
                this.byRequest[r][h] = new Hop(CAPACITY);
                this.hops.put(requests[r] + " " + HOPS[h], this.byRequest[r][h]);
            }
        }
    }

    @Override
    public void timed(final RequestTiming timing) {
        final Hop[] hop;
        if (timing.requestClass == RequestClass.NEW) {
            hop = this.byRequest[0];
        } else if (timing.requestClass == RequestClass.CANCEL) {
            hop = this.byRequest[1];
        } else {
            return;
        }
        if (timing.takenNanos > 0 && timing.ingressNanos > 0) {
            hop[0].add(timing.takenNanos - timing.ingressNanos);
        }
        if (timing.decidedNanos > 0 && timing.takenNanos > 0) {
            hop[1].add(timing.decidedNanos - timing.takenNanos);
        }
        if (timing.wireSentNanos > 0 && timing.decidedNanos > 0) {
            hop[2].add(timing.wireSentNanos - timing.decidedNanos);
        }
        if (timing.answeredNanos > 0 && timing.wireSentNanos > 0) {
            hop[3].add(timing.answeredNanos - timing.wireSentNanos);
            hop[4].add(timing.answerSlackNanos);
        }
    }

    public Map<String, Hop> hops() {
        return this.hops;
    }

    public void clear() {
        for (final Hop hop : this.hops.values()) {
            hop.clear();
        }
    }
}
