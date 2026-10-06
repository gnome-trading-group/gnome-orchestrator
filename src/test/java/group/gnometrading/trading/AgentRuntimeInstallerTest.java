package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import group.gnometrading.concurrent.AgentRuntime;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.resources.Properties;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

class AgentRuntimeInstallerTest {

    private static final GnomeAgent HOT = new GnomeAgent() {
        @Override
        public int doWork() {
            return 0;
        }

        @Override
        public ThreadProfile threadProfile() {
            return ThreadProfile.HOT_PATH;
        }
    };

    private static final GnomeAgent BACKGROUND = () -> 0;

    private final Logger logger = mock(Logger.class);

    @AfterEach
    void tearDown() {
        AgentRuntime.reset();
    }

    @Test
    void testOnlyLowLatencyWithAffinityPinsHotAgents() throws Exception {
        assertTrue(AgentRuntimeInstaller.pinsHotAgents(new Properties("agent-runtime/affinity.properties")));
        assertFalse(AgentRuntimeInstaller.pinsHotAgents(new Properties("agent-runtime/no-affinity.properties")));
        assertFalse(AgentRuntimeInstaller.pinsHotAgents(new Properties("agent-runtime/standard.properties")));
    }

    @Test
    void testStandardProfileNeverSpins() throws Exception {
        AgentRuntimeInstaller.install(new Properties("agent-runtime/standard.properties"), logger, 1);

        assertInstanceOf(BackoffIdleStrategy.class, AgentRuntime.attach(HOT));
        assertInstanceOf(SleepingIdleStrategy.class, AgentRuntime.attach(BACKGROUND));
    }

    @Test
    void testLowLatencyWithoutAffinityNeverSpins() throws Exception {
        AgentRuntimeInstaller.install(new Properties("agent-runtime/no-affinity.properties"), logger, 1);

        assertInstanceOf(BackoffIdleStrategy.class, AgentRuntime.attach(HOT));
        assertInstanceOf(SleepingIdleStrategy.class, AgentRuntime.attach(BACKGROUND));
    }

    // Off Linux the native library is absent and not required, so placement runs without actually pinning.
    @Test
    @DisabledOnOs(OS.LINUX)
    void testAffinityGivesHotAgentsDedicatedCoresAndSleepsTheRest() throws Exception {
        AgentRuntimeInstaller.install(new Properties("agent-runtime/affinity.properties"), logger, 1);

        assertInstanceOf(NoOpIdleStrategy.class, AgentRuntime.attach(HOT));
        assertInstanceOf(NoOpIdleStrategy.class, AgentRuntime.attach(HOT));
        assertInstanceOf(BackoffIdleStrategy.class, AgentRuntime.attach(HOT));
        assertInstanceOf(SleepingIdleStrategy.class, AgentRuntime.attach(BACKGROUND));
    }

    @Test
    @DisabledOnOs(OS.LINUX)
    void testWarnsWhenIsolatedCoresCannotFitTheHotThreads() throws Exception {
        AgentRuntimeInstaller.install(new Properties("agent-runtime/affinity.properties"), logger, 3);

        verify(logger).logf(eq(LogMessage.UNKNOWN_ERROR), contains("undersized"), any(Object[].class));
    }

    @Test
    void testRejectsUnknownProfile() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentRuntimeInstaller.install(new Properties("agent-runtime/unknown.properties"), logger, 1));
    }

    @Test
    void testExpectedHotThreads() {
        assertEquals(4, AgentRuntimeInstaller.expectedHotThreads("paper", 1));
        assertEquals(10, AgentRuntimeInstaller.expectedHotThreads("paper", 3));
        assertEquals(5, AgentRuntimeInstaller.expectedHotThreads("live", 1));
        assertEquals(10, AgentRuntimeInstaller.expectedHotThreads("live", 2));
    }
}
