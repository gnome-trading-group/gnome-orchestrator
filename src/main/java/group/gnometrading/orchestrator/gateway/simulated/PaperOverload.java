package group.gnometrading.orchestrator.gateway.simulated;

import group.gnometrading.orchestrator.gateway.core.GatewayAlerts;
import java.util.Map;

/**
 * Raises PAPER_GATEWAY_OVERLOADED when the books' lag p99 has been past {@link SimulatedLane#LATE_NANOS} for
 * {@link #MINUTES} minutes running, and clears it after the first minute back under. A full gateway already refuses new
 * sessions; this is what takes a gateway past its limits after they joined: their markets getting busier.
 */
public final class PaperOverload {

    /** Minutes running a gateway must be late before it is called overloaded. */
    static final int MINUTES = 5;

    // Over this share of records new to their stream late, the lag p99 is past LATE_NANOS.
    private static final double LATE_SHARE = 0.01;

    private final GatewayAlerts alerts;
    private final String name;
    private int lateMinutes;
    private boolean firing;

    public PaperOverload(final GatewayAlerts alerts, final String name) {
        this.alerts = alerts;
        this.name = name;
    }

    /** Takes each minute in turn, from one thread. */
    public void minute(final SimulatedAccount.Minute minute) {
        if (minute.lateShare() <= LATE_SHARE) {
            this.lateMinutes = 0;
            if (this.firing) {
                this.firing = false;
                this.alerts.resolved("PAPER_GATEWAY_OVERLOADED", "");
            }
            return;
        }
        this.lateMinutes++;
        if (this.lateMinutes >= MINUTES && !this.firing) {
            this.firing = true;
            this.alerts.firing(
                    "PAPER_GATEWAY_OVERLOADED",
                    "",
                    String.format(
                            "Paper gateway %s: books' lag p99 over %d ms for %d minutes (%.1f%% late, %d records/s on"
                                    + " %d streams, %d books)",
                            this.name,
                            SimulatedLane.LATE_NANOS / 1_000_000,
                            this.lateMinutes,
                            100 * minute.lateShare(),
                            minute.recordsPerSecond(),
                            minute.streams(),
                            minute.books()),
                    Map.of(
                            "recordsPerSecond", Long.toString(minute.recordsPerSecond()),
                            "streams", Integer.toString(minute.streams()),
                            "books", Integer.toString(minute.books())));
        }
    }
}
