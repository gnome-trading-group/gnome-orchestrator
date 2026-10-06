package group.gnometrading.trading;

import group.gnometrading.concurrent.AgentRuntime;
import group.gnometrading.concurrent.CoreAllocator;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.IdlePolicy;
import group.gnometrading.concurrent.IsolatedCoreAllocator;
import group.gnometrading.concurrent.Placement;
import group.gnometrading.concurrent.ThreadPinner;
import group.gnometrading.jni.NativeThreadPinner;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.resources.Properties;
import java.util.Arrays;
import java.util.Locale;

/**
 * Installs thread placement and idle behaviour from the session's latency profile. Must run before any agent
 * thread starts — outbound gateways start theirs while the orchestrator is still configuring.
 *
 * <ul>
 *   <li>{@code latency.profile=standard}: nothing pinned, nothing busy-spins.
 *   <li>{@code latency.profile=low_latency} (default) with {@code cpu.affinity.enabled=true}: hot agents get a
 *       dedicated core from {@code cpu.isolated}, everything else shares {@code cpu.housekeeping} and sleeps when
 *       idle.
 *   <li>{@code low_latency} without affinity (local runs): nothing pinned, nothing busy-spins — spinning only pays
 *       off on a core nothing else wants.
 * </ul>
 */
public final class AgentRuntimeInstaller {

    static final String STANDARD = "standard";
    static final String LOW_LATENCY = "low_latency";
    private static final String DEFAULT_HOUSEKEEPING = "0-1";

    private AgentRuntimeInstaller() {}

    /**
     * Installs the runtime for this session.
     *
     * @param listingCount used only to warn when the instance has fewer isolated cores than hot threads
     */
    /**
     * Whether hot agents get isolated cores of their own. Inbound socket readers spin exactly when this holds, so a
     * reader never busy-polls a core it shares.
     */
    public static boolean pinsHotAgents(Properties properties) {
        return LOW_LATENCY.equals(profile(properties))
                && Boolean.parseBoolean(optional(properties, "cpu.affinity.enabled", "false"));
    }

    public static void install(Properties properties, Logger logger, int listingCount) {
        String profile = profile(properties);
        AgentRuntime.Listener listener = new LoggingListener(logger);

        if (STANDARD.equals(profile)) {
            AgentRuntime.install(ThreadPinner.NONE, CoreAllocator.NONE, IdlePolicy.standard(), listener);
            logger.logf(LogMessage.DEBUG, "Latency profile standard: no pinning, idle agents back off");
            return;
        }
        if (!LOW_LATENCY.equals(profile)) {
            throw new IllegalArgumentException("Unknown latency.profile: " + profile);
        }
        if (!pinsHotAgents(properties)) {
            AgentRuntime.install(ThreadPinner.NONE, CoreAllocator.NONE, IdlePolicy.lowLatency(), listener);
            logger.logf(LogMessage.DEBUG, "Latency profile low_latency without CPU affinity: no pinning, no spinning");
            return;
        }

        int[] isolated = IsolatedCoreAllocator.parseCpuList(optional(properties, "cpu.isolated", ""));
        int[] housekeeping =
                IsolatedCoreAllocator.parseCpuList(optional(properties, "cpu.housekeeping", DEFAULT_HOUSEKEEPING));
        boolean linux =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
        ThreadPinner pinner = NativeThreadPinner.load(linux);

        AgentRuntime.install(
                pinner, new IsolatedCoreAllocator(isolated, housekeeping), IdlePolicy.lowLatency(), listener);
        logger.logf(
                LogMessage.DEBUG,
                "Latency profile low_latency: isolated cores %s, housekeeping cores %s, native pinning %s",
                Arrays.toString(isolated),
                Arrays.toString(housekeeping),
                pinner == ThreadPinner.NONE ? "unavailable" : "loaded");

        int expectedHot = expectedHotThreads(optional(properties, "mode", "paper"), listingCount);
        if (expectedHot > isolated.length) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Instance undersized: ~%d hot threads expected for %d listing(s) but only %d isolated cores;"
                            + " the overflow will back off on housekeeping cores",
                    expectedHot,
                    listingCount,
                    isolated.length);
        }
    }

    /**
     * Hot agents per session: each listing's socket reader plus its outbound side (the simulated exchange in
     * paper, writer and reader in live), the strategy and the OMS, and the mux and router once there is more than
     * one listing.
     */
    static int expectedHotThreads(String mode, int listingCount) {
        int perListing = "live".equals(mode) ? 3 : 2;
        int multiListing = listingCount > 1 ? 2 : 0;
        return perListing * listingCount + 2 + multiListing;
    }

    private static String profile(Properties properties) {
        return optional(properties, "latency.profile", LOW_LATENCY).toLowerCase(Locale.ROOT);
    }

    private static String optional(Properties properties, String key, String fallback) {
        return properties.hasProperty(key) ? properties.getStringProperty(key) : fallback;
    }

    private static final class LoggingListener implements AgentRuntime.Listener {
        private final Logger logger;

        private LoggingListener(Logger logger) {
            this.logger = logger;
        }

        @Override
        public void onPlacement(GnomeAgent agent, Placement placement) {
            logger.logf(
                    LogMessage.DEBUG,
                    "Agent %s (%s) on cpus %s%s",
                    agent.roleName(),
                    agent.threadProfile(),
                    Arrays.toString(placement.cpus()),
                    placement.dedicated() ? " (dedicated)" : "");
        }

        @Override
        public void onPlacementFailure(GnomeAgent agent, Placement placement, Throwable error) {
            logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Agent %s could not be placed on cpus %s, running unpinned: %s",
                    agent.roleName(),
                    Arrays.toString(placement.cpus()),
                    error.toString());
        }
    }
}
