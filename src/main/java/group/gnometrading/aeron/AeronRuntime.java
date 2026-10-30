package group.gnometrading.aeron;

import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import java.io.File;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.BusySpinIdleStrategy;

/**
 * A process's embedded media driver and its Aeron client: one per host (sessions, shards and gateways each run one
 * process per host), so a session's shard streams and its gateway link share one driver and its receive ports.
 */
public final class AeronRuntime implements AutoCloseable {

    private static final File SHARED_MEMORY = new File("/dev/shm");

    private final MediaDriver driver;
    private final Aeron aeron;

    private AeronRuntime(final MediaDriver driver, final Aeron aeron) {
        this.driver = driver;
        this.aeron = aeron;
    }

    /**
     * Starts the driver in {@code /dev/shm/aeron-<name>} (the temp directory where there is no {@code /dev/shm}),
     * clearing what a previous run left there, and connects a client to it.
     */
    public static AeronRuntime launch(final String name, final AeronProfile profile, final ErrorHandler errors) {
        return launch(name, profile, MarketDataIdle.DEFAULT, errors);
    }

    /** As {@link #launch(String, AeronProfile, ErrorHandler)}, its threads parking at most as long as {@code idle}. */
    public static AeronRuntime launch(
            final String name, final AeronProfile profile, final MarketDataIdle idle, final ErrorHandler errors) {
        final MediaDriver.Context context = new MediaDriver.Context()
                .aeronDirectoryName(directoryFor(name))
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .receiverWildcardPortRange(AeronPorts.receiveRange())
                // Channels set their own term length; this only stops a channel that forgets from taking 3 x 16 MiB.
                .publicationTermBufferLength(AeronChannels.MARKET_DATA_TERM_LENGTH)
                .errorHandler(errors);
        if (profile.dedicated()) {
            context.threadingMode(ThreadingMode.SHARED_NETWORK)
                    .sharedNetworkIdleStrategy(new BusySpinIdleStrategy())
                    .conductorIdleStrategy(idle.driverStrategy());
        } else {
            context.threadingMode(ThreadingMode.SHARED).sharedIdleStrategy(idle.driverStrategy());
        }
        final MediaDriver driver = MediaDriver.launchEmbedded(context);
        try {
            final Aeron aeron = Aeron.connect(new Aeron.Context()
                    .aeronDirectoryName(driver.aeronDirectoryName())
                    .errorHandler(errors));
            return new AeronRuntime(driver, aeron);
        } catch (RuntimeException e) {
            driver.close();
            throw e;
        }
    }

    public Aeron aeron() {
        return this.aeron;
    }

    public String directory() {
        return this.driver.aeronDirectoryName();
    }

    @Override
    public void close() {
        try {
            this.aeron.close();
        } finally {
            this.driver.close();
        }
    }

    static String directoryFor(final String name) {
        final File parent =
                SHARED_MEMORY.isDirectory() ? SHARED_MEMORY : new File(System.getProperty("java.io.tmpdir"));
        return new File(parent, "aeron-" + name).getAbsolutePath();
    }
}
