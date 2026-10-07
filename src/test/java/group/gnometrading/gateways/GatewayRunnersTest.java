package group.gnometrading.gateways;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GatewayRunnersTest {

    @Test
    void closesAGatewayWhoseReaderIsPausedMidReconnect() {
        final ReaderPauseControl pause = new ReaderPauseControl();
        final AtomicBoolean disconnected = new AtomicBoolean();
        // A reader that runs its work loop the way the gateway readers do.
        final GnomeAgent reader = () -> pause.awaitIfPaused() ? 1 : 0;
        // A supervisor whose close disconnects, which first needs the reader paused.
        final GnomeAgent supervisor = new GnomeAgent() {
            @Override
            public int doWork() {
                return 0;
            }

            @Override
            public void onClose() {
                pause.pause();
                disconnected.set(true);
            }
        };
        final GatewayRunners runners = GatewayRunners.venue(
                        pause,
                        new GnomeAgentRunner(reader, Throwable::printStackTrace),
                        new GnomeAgentRunner(supervisor, Throwable::printStackTrace))
                .start();
        pause.release();
        // The supervisor pauses the reader to reconnect, and the session shuts down before it resumes.
        pause.pause();

        assertTimeoutPreemptively(Duration.ofSeconds(5), runners::close);
        assertTrue(disconnected.get(), "the supervisor's disconnect completed");
    }
}
