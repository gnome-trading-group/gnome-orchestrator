package group.gnometrading.orchestrator.gateway.simulated;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.gnometrading.orchestrator.gateway.core.GatewayAlerts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaperOverloadTest {

    private final List<String> raised = new ArrayList<>();
    private final PaperOverload overload = new PaperOverload(
            new GatewayAlerts() {
                @Override
                public void firing(
                        final String type, final String about, final String title, final Map<String, String> labels) {
                    PaperOverloadTest.this.raised.add("firing " + type);
                }

                @Override
                public void resolved(final String type, final String about) {
                    PaperOverloadTest.this.raised.add("resolved " + type);
                }
            },
            "paper-kalshi");

    @Test
    void fiveMinutesRunningPastTheLagAreAnOverloadClearedByTheFirstMinuteBackUnder() {
        for (int i = 0; i < PaperOverload.MINUTES - 1; i++) {
            this.overload.minute(minute(0.05));
        }
        assertEquals(List.of(), this.raised);

        this.overload.minute(minute(0.05));
        this.overload.minute(minute(0.05));
        assertEquals(List.of("firing PAPER_GATEWAY_OVERLOADED"), this.raised);

        this.overload.minute(minute(0.002));
        assertEquals(List.of("firing PAPER_GATEWAY_OVERLOADED", "resolved PAPER_GATEWAY_OVERLOADED"), this.raised);
    }

    @Test
    void aQuietMinuteStartsTheCountAgain() {
        for (int i = 0; i < PaperOverload.MINUTES - 1; i++) {
            this.overload.minute(minute(0.05));
        }
        this.overload.minute(minute(0));
        for (int i = 0; i < PaperOverload.MINUTES - 1; i++) {
            this.overload.minute(minute(0.05));
        }

        assertEquals(List.of(), this.raised);
    }

    private static SimulatedAccount.Minute minute(final double lateShare) {
        return new SimulatedAccount.Minute(0, 10, 12, 0, 100, 40, 3_000, lateShare, 0);
    }
}
