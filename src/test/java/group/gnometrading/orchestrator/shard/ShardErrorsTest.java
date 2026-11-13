package group.gnometrading.orchestrator.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.NullLogger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShardErrorsTest {

    private final List<String> passedOn = new ArrayList<>();
    private long now = 1_000;
    private final ShardErrors errors = new ShardErrors(
            new NullLogger() {
                @Override
                public void log(final LogMessage msg) {
                    passedOn.add(msg.name());
                }

                @Override
                public void logf(final LogMessage msg, final String format, final Object... args) {
                    passedOn.add(msg + " " + String.format(format, args));
                }
            },
            () -> now);

    @Test
    void onlyUnexpectedErrorsCountAndEveryLineIsPassedOn() {
        errors.logf(LogMessage.DEBUG, "rolled %s", "A");
        errors.logf(LogMessage.SOCKET_DISCONNECTED, "%s: closed", "A");
        errors.logf(LogMessage.UNKNOWN_ERROR, "Shard tick failed: %s", "boom");
        errors.log(LogMessage.UNKNOWN_ERROR);

        ShardErrors.Reading reading = errors.read();
        assertEquals(2, reading.total());
        assertEquals(2, reading.lastHour());
        assertEquals("UNKNOWN_ERROR", reading.latest());
        assertEquals(4, passedOn.size());
    }

    @Test
    void theLatestIsTheFormattedLineAndWhenItCame() {
        assertNull(errors.read().latest(), "none yet");

        now = 5_000;
        errors.logf(LogMessage.UNKNOWN_ERROR, "Error occurred in %s: %s", "KALSHI-0", "IllegalStateException");

        assertEquals(
                "Error occurred in KALSHI-0: IllegalStateException",
                errors.read().latest());
        assertEquals(5_000, errors.read().latestAtNanos());
    }

    @Test
    void theLastHourForgetsOlderErrorsAndTheTotalDoesnt() {
        errors.logf(LogMessage.UNKNOWN_ERROR, "first");
        now += ShardErrors.WINDOW_NANOS;
        errors.logf(LogMessage.UNKNOWN_ERROR, "second");

        ShardErrors.Reading reading = errors.read();
        assertEquals(2, reading.total());
        assertEquals(1, reading.lastHour());
    }

    @Test
    void anHourWithMoreThanItKeepsReadsAsItsCap() {
        for (int i = 0; i < ShardErrors.MAX_COUNTED + 10; i++) {
            errors.logf(LogMessage.UNKNOWN_ERROR, "again");
        }

        assertEquals(ShardErrors.MAX_COUNTED + 10, errors.read().total());
        assertEquals(ShardErrors.MAX_COUNTED, errors.read().lastHour());
    }
}
