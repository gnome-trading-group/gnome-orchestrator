package group.gnometrading.orchestrator.gateway.transport;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.SessionTable;

/**
 * The {@code gateway.drops} sample, every interval: since the last, what ingress and egress dropped (the gateway's
 * minute log line's numbers) and what the self-trade check did, and the orders its index holds now. Runs on the sample
 * poster's thread; allocates nothing once constructed.
 */
public final class DropsSampler {

    public static final String KIND = "gateway.drops";
    private static final String[] NAMES = {
        "unlinkedOrders",
        "fragmented",
        "controlFull",
        "unroutedReports",
        "unnumberedFull",
        "replayGaps",
        "untrackedOrders",
        "selfTradeNoRoom",
        "selfTradeEndsLost",
        "selfTradeRefused",
        "selfTradeShadowed",
    };

    private final SampleBuffer samples;
    private final GatewayIngress ingress;
    private final GatewayEgress egress;
    private final SessionTable sessions;
    private final CrossIndex cross;
    private final long intervalMs;
    private final long[] now = new long[NAMES.length];
    private final long[] previous = new long[NAMES.length];
    private boolean first = true;
    private long nextAtMs;

    public DropsSampler(
            final SampleBuffer samples,
            final GatewayIngress ingress,
            final GatewayEgress egress,
            final SessionTable sessions,
            final CrossIndex cross,
            final long intervalMs) {
        this.samples = samples;
        this.ingress = ingress;
        this.egress = egress;
        this.sessions = sessions;
        this.cross = cross;
        this.intervalMs = intervalMs;
    }

    /** @return whether it sampled; the first look only sets where changes count from */
    public boolean sampleIfDue(final long nowMs) {
        if (nowMs < nextAtMs) {
            return false;
        }
        nextAtMs = nowMs + intervalMs;
        read();
        if (first) {
            first = false;
            System.arraycopy(now, 0, previous, 0, now.length);
            return false;
        }
        final JsonEncoder json = samples.begin(KIND, nowMs);
        try {
            json.writeObjectEntry("intervalMs", intervalMs);
            for (int i = 0; i < NAMES.length; i++) {
                json.writeComma().writeObjectEntry(NAMES[i], now[i] - previous[i]);
            }
            json.writeComma().writeObjectEntry("selfTradeHeld", cross.size());
            samples.commit();
        } catch (RuntimeException e) {
            samples.abandon();
            throw e;
        } finally {
            System.arraycopy(now, 0, previous, 0, now.length);
        }
        return true;
    }

    private void read() {
        long untracked = 0;
        final GatewaySession[] all = sessions.all();
        for (int i = 0; i < all.length; i++) {
            untracked += all[i].totals.untracked();
        }
        now[0] = ingress.unlinked();
        now[1] = ingress.fragmented();
        now[2] = ingress.controlFull();
        now[3] = egress.unrouted();
        now[4] = egress.unnumberedFull();
        now[5] = egress.replayGaps();
        now[6] = untracked;
        now[7] = cross.noRoom();
        now[8] = cross.lost();
        now[9] = cross.refused();
        now[10] = cross.shadowed();
    }
}
