package group.gnometrading.orchestrator.shard;

import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronPathAgent;
import group.gnometrading.orchestrator.aeron.AeronPorts;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.AgentInvoker;

/**
 * A media driver with no threads of its own ({@link ThreadingMode#INVOKER}), run on the publisher's spinning thread,
 * so a low-latency shard spins one core rather than two (the publisher's and the driver's network thread).
 *
 * <p>Until the publisher's thread takes the driver ({@link #onPublisherThread}), and after it lets go, a stand-in thread
 * runs it, as the client's connect and every add or close of a publication wait on the driver. Only one thread ever
 * runs it at a time.
 */
final class InvokedAeron implements AutoCloseable {

    private static final long STAND_IN_PARK_NANOS = TimeUnit.MICROSECONDS.toNanos(50);

    private final MediaDriver driver;
    private final AgentInvoker invoker;
    private final Aeron aeron;
    private volatile boolean standInRuns;
    private Thread standIn;

    private InvokedAeron(final MediaDriver driver) {
        this.driver = driver;
        this.invoker = driver.sharedAgentInvoker();
        startStandIn();
        this.aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
    }

    static InvokedAeron launch(final String name, final ErrorHandler errors) {
        final File shm = new File("/dev/shm");
        final File parent = shm.isDirectory() ? shm : new File(System.getProperty("java.io.tmpdir"));
        final MediaDriver driver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .aeronDirectoryName(new File(parent, "aeron-" + name).getAbsolutePath())
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .receiverWildcardPortRange(AeronPorts.receiveRange())
                .publicationTermBufferLength(AeronChannels.MARKET_DATA_TERM_LENGTH)
                .threadingMode(ThreadingMode.INVOKER)
                .errorHandler(errors));
        return new InvokedAeron(driver);
    }

    Aeron aeron() {
        return aeron;
    }

    /** {@code publisher}, also running the driver each pass once its thread starts. */
    AeronPathAgent onPublisherThread(final StreamPublisherAgent publisher) {
        return new AeronPathAgent() {
            @Override
            public void onStart() throws Exception {
                stopStandIn();
                publisher.onStart();
            }

            @Override
            public int doWork() throws Exception {
                return publisher.doWork() + invoker.invoke();
            }

            @Override
            public void onClose() {
                publisher.onClose();
                startStandIn();
            }

            @Override
            public ThreadProfile threadProfile() {
                return publisher.threadProfile();
            }

            @Override
            public String roleName() {
                return publisher.roleName();
            }
        };
    }

    private synchronized void startStandIn() {
        standInRuns = true;
        standIn = new Thread(
                () -> {
                    while (standInRuns) {
                        if (invoker.invoke() == 0) {
                            LockSupport.parkNanos(STAND_IN_PARK_NANOS);
                        }
                    }
                },
                "aeron-driver-stand-in");
        standIn.setDaemon(true);
        standIn.start();
    }

    private synchronized void stopStandIn() throws InterruptedException {
        standInRuns = false;
        standIn.join();
    }

    @Override
    public void close() throws Exception {
        try {
            aeron.close();
        } finally {
            stopStandIn();
            driver.close();
        }
    }
}
