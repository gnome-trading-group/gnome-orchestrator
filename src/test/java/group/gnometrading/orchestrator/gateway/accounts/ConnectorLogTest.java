package group.gnometrading.orchestrator.gateway.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConnectorLogTest {

    private final List<String> lines = new ArrayList<>();
    private long now = 1_000;
    private final ConnectorLog log = new ConnectorLog(new Lines(), "account-1", () -> this.now);

    @Test
    void theFirstFailureIsSaidThenAtMostOnceAMinuteWithHowManyFailed() {
        fail();
        fail();
        fail();
        this.now += ConnectorLog.REPEAT_NANOS;
        fail();

        assertEquals(2, this.lines.size());
        assertTrue(
                this.lines.get(0).contains("1 connection(s) to venue.test couldn't be opened: IOException: refused"));
        assertTrue(this.lines.get(1).contains("3 connection(s)"), this.lines.get(1));
    }

    @Test
    void anOpenAfterFailuresIsSaidOnceAndAFailureAfterThatIsSaidAtOnce() {
        fail();
        this.log.onOpened("venue.test", 0);
        this.log.onOpened("venue.test", 1);
        this.now += TimeUnit.SECONDS.toNanos(1);
        fail();

        assertEquals(3, this.lines.size());
        assertTrue(this.lines.get(1).contains("connections to venue.test open again"));
        assertTrue(this.lines.get(2).contains("couldn't be opened"));
    }

    @Test
    void opensWithoutFailuresSayNothing() {
        this.log.onOpened("venue.test", 0);

        assertTrue(this.lines.isEmpty());
    }

    private void fail() {
        this.log.onOpenFailed("venue.test", 0, new IOException("refused"));
    }

    private final class Lines implements Logger {
        @Override
        public void logf(final LogMessage msg, final String format, final Object... args) {
            ConnectorLogTest.this.lines.add(String.format(format, args));
        }

        @Override
        public void log(final LogMessage msg) {}

        @Override
        public void log(final LogMessage msg, final long v1) {}

        @Override
        public void log(final LogMessage msg, final long v1, final long v2) {}

        @Override
        public void log(final LogMessage msg, final long v1, final long v2, final long v3) {}

        @Override
        public void log(final LogMessage msg, final long v1, final long v2, final long v3, final long v4) {}

        @Override
        public void log(
                final LogMessage msg, final long v1, final long v2, final long v3, final long v4, final long v5) {}

        @Override
        public void log(
                final LogMessage msg,
                final long v1,
                final long v2,
                final long v3,
                final long v4,
                final long v5,
                final long v6) {}
    }
}
