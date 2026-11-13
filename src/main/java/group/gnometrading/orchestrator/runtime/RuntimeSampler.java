package group.gnometrading.orchestrator.runtime;

import group.gnometrading.codecs.json.JsonEncoder;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.host.HostReadings;
import group.gnometrading.host.JvmReadings;
import group.gnometrading.metrics.SampleBuffer;
import io.aeron.driver.status.SystemCounterDescriptor;
import java.util.List;
import org.agrona.concurrent.status.CountersReader;

/**
 * The {@code runtime} sample every process sends (sessions, order gateways, shards), every interval: the host (CPU,
 * memory, disk, network, TCP retransmits, UDP drops, load), the JVM (heap, direct and mapped memory, each collector's collections
 * and time), the host clock, each agent thread (its loop passes and CPU time), the scheduling probe's latest window and
 * the Aeron media driver's system counters.
 *
 * <p>Counters go out as their change since the previous sample (the first sample has none, so they are left out of
 * it), gauges as read. A reading the host can't give is -1. Runs on the thread that sends samples, never an agent's,
 * with readers of its own (its CPU windows are its own interval); nothing allocates once constructed.
 */
public final class RuntimeSampler {

    public static final String KIND = "runtime";
    private static final long UNAVAILABLE = -1;

    private static final SystemCounterDescriptor[] AERON_COUNTERS = {
        SystemCounterDescriptor.BYTES_SENT,
        SystemCounterDescriptor.BYTES_RECEIVED,
        SystemCounterDescriptor.NAK_MESSAGES_SENT,
        SystemCounterDescriptor.NAK_MESSAGES_RECEIVED,
        SystemCounterDescriptor.RETRANSMITS_SENT,
        SystemCounterDescriptor.RETRANSMITTED_BYTES,
        SystemCounterDescriptor.LOSS_GAP_FILLS,
        SystemCounterDescriptor.SHORT_SENDS,
        SystemCounterDescriptor.SENDER_FLOW_CONTROL_LIMITS,
        SystemCounterDescriptor.FLOW_CONTROL_UNDER_RUNS,
        SystemCounterDescriptor.FLOW_CONTROL_OVER_RUNS,
        SystemCounterDescriptor.INVALID_PACKETS,
        SystemCounterDescriptor.ERRORS,
        SystemCounterDescriptor.CLIENT_TIMEOUTS,
        SystemCounterDescriptor.UNBLOCKED_PUBLICATIONS,
        SystemCounterDescriptor.CONDUCTOR_CYCLE_TIME_THRESHOLD_EXCEEDED,
        SystemCounterDescriptor.SENDER_CYCLE_TIME_THRESHOLD_EXCEEDED,
        SystemCounterDescriptor.RECEIVER_CYCLE_TIME_THRESHOLD_EXCEEDED,
    };
    private static final String[] AERON_NAMES = {
        "bytesSent",
        "bytesReceived",
        "naksSent",
        "naksReceived",
        "retransmitsSent",
        "retransmittedBytes",
        "lossGapFills",
        "shortSends",
        "senderFlowControlLimits",
        "flowControlUnderRuns",
        "flowControlOverRuns",
        "invalidPackets",
        "errors",
        "clientTimeouts",
        "unblockedPublications",
        "conductorSlowCycles",
        "senderSlowCycles",
        "receiverSlowCycles",
    };
    // Gauges: the longest cycle each driver thread has taken, in nanoseconds.
    private static final SystemCounterDescriptor[] AERON_MAX_CYCLES = {
        SystemCounterDescriptor.CONDUCTOR_MAX_CYCLE_TIME,
        SystemCounterDescriptor.SENDER_MAX_CYCLE_TIME,
        SystemCounterDescriptor.RECEIVER_MAX_CYCLE_TIME,
    };
    private static final String[] AERON_MAX_CYCLE_NAMES = {
        "conductorMaxCycleNs", "senderMaxCycleNs", "receiverMaxCycleNs"
    };

    // Host counters, in this order: their names and previous values.
    private static final String[] HOST_COUNTER_NAMES = {
        "contextSwitches",
        "netRxBytes",
        "netRxPackets",
        "netRxDrops",
        "netTxBytes",
        "netTxPackets",
        "netTxDrops",
        "tcpOutSegments",
        "tcpRetransmits",
        "udpInDatagrams",
        "udpOutDatagrams",
        "udpInErrors",
        "udpRcvbufErrors",
        "udpSndbufErrors",
        "ioReadBytes",
        "ioWriteBytes",
    };

    private final SampleBuffer samples;
    private final HostReadings host;
    private final JvmReadings jvm;
    private final ClockReadings clock;
    private final SchedulingLatencyProbe probe;
    private final CountersReader aeron;
    private final GnomeAgentRunner[] agents;
    private final String[] agentNames;
    private final long intervalMs;

    private final long[] hostCounters = new long[HOST_COUNTER_NAMES.length];
    private final long[] previousHost = new long[HOST_COUNTER_NAMES.length];
    private final long[] previousCollections;
    private final long[] previousCollectionMs;
    private final long[] previousCycles;
    private final long[] previousCpu;
    private final long[] previousAeron = new long[AERON_COUNTERS.length];
    private boolean first = true;
    private long nextAtMs;

    /**
     * @param probe the process's scheduling probe, or null
     * @param aeron the media driver's counters, or null for a process without one
     */
    public RuntimeSampler(
            final SampleBuffer samples,
            final HostReadings host,
            final JvmReadings jvm,
            final ClockReadings clock,
            final SchedulingLatencyProbe probe,
            final CountersReader aeron,
            final List<GnomeAgentRunner> agents,
            final long intervalMs) {
        this.samples = samples;
        this.host = host;
        this.jvm = jvm;
        this.clock = clock;
        this.probe = probe;
        this.aeron = aeron;
        this.agents = agents.toArray(new GnomeAgentRunner[0]);
        this.agentNames = new String[this.agents.length];
        for (int i = 0; i < this.agents.length; i++) {
            agentNames[i] = this.agents[i].getAgent().roleName();
        }
        this.intervalMs = intervalMs;
        this.previousCollections = new long[jvm.collectors()];
        this.previousCollectionMs = new long[jvm.collectors()];
        this.previousCycles = new long[this.agents.length];
        this.previousCpu = new long[this.agents.length];
    }

    /** Takes a sample if one is due at {@code nowMs}; @return whether it did */
    public boolean sampleIfDue(final long nowMs) {
        if (nowMs < nextAtMs) {
            return false;
        }
        nextAtMs = nowMs + intervalMs;
        sample(nowMs);
        return true;
    }

    /** Takes a sample now. */
    public void sample(final long nowMs) {
        host.sample();
        clock.sample();
        readHostCounters();
        final JsonEncoder json = samples.begin(KIND, nowMs);
        try {
            json.writeObjectEntry("intervalMs", intervalMs).writeComma();
            writeHost(json);
            json.writeComma();
            writeJvm(json);
            json.writeComma();
            writeClock(json);
            json.writeComma();
            writeThreads(json);
            json.writeComma();
            writeScheduling(json);
            if (aeron != null) {
                json.writeComma();
                writeAeron(json);
            }
            samples.commit();
        } catch (RuntimeException e) {
            samples.abandon();
            throw e;
        } finally {
            first = false;
        }
    }

    private void readHostCounters() {
        hostCounters[0] = host.contextSwitches();
        hostCounters[1] = host.netRxBytes();
        hostCounters[2] = host.netRxPackets();
        hostCounters[3] = host.netRxDrops();
        hostCounters[4] = host.netTxBytes();
        hostCounters[5] = host.netTxPackets();
        hostCounters[6] = host.netTxDrops();
        hostCounters[7] = host.tcpOutSegments();
        hostCounters[8] = host.tcpRetransmits();
        hostCounters[9] = host.udpInDatagrams();
        hostCounters[10] = host.udpOutDatagrams();
        hostCounters[11] = host.udpInErrors();
        hostCounters[12] = host.udpRcvbufErrors();
        hostCounters[13] = host.udpSndbufErrors();
        hostCounters[14] = host.ioReadBytes();
        hostCounters[15] = host.ioWriteBytes();
    }

    private void writeHost(final JsonEncoder json) {
        json.writeString("host")
                .writeColon()
                .writeObjectStart()
                .writeObjectEntry("cpuBusyPermille", host.cpuBusyPermille())
                .writeComma()
                .writeObjectEntry("cpuStealPermille", host.cpuStealPermille())
                .writeComma()
                .writeObjectEntry("loadHundredths", host.loadHundredths())
                .writeComma()
                .writeObjectEntry("memAvailableBytes", host.memAvailableBytes())
                .writeComma()
                .writeObjectEntry("memTotalBytes", host.memTotalBytes())
                .writeComma()
                .writeObjectEntry("processRssBytes", host.processRssBytes())
                .writeComma()
                .writeObjectEntry("diskFreeBytes", host.diskFreeBytes());
        for (int i = 0; i < hostCounters.length; i++) {
            if (!first) {
                json.writeComma().writeObjectEntry(HOST_COUNTER_NAMES[i], delta(hostCounters[i], previousHost[i]));
            }
            previousHost[i] = hostCounters[i];
        }
        json.writeObjectEnd();
    }

    private void writeJvm(final JsonEncoder json) {
        json.writeString("jvm")
                .writeColon()
                .writeObjectStart()
                .writeObjectEntry("heapUsedBytes", jvm.heapUsedBytes())
                .writeComma()
                .writeObjectEntry("heapCommittedBytes", jvm.heapCommittedBytes())
                .writeComma()
                .writeObjectEntry("heapMaxBytes", jvm.heapMaxBytes())
                .writeComma()
                .writeObjectEntry("directBytes", jvm.directBytes())
                .writeComma()
                .writeObjectEntry("mappedBytes", jvm.mappedBytes())
                .writeComma()
                .writeObjectEntry("threads", jvm.threadCount())
                .writeComma()
                .writeString("gc")
                .writeColon()
                .writeArrayStart();
        for (int c = 0; c < jvm.collectors(); c++) {
            final long collections = jvm.collections(c);
            final long ms = jvm.collectionMs(c);
            if (c > 0) {
                json.writeComma();
            }
            json.writeObjectStart().writeObjectEntry("name", jvm.collectorName(c));
            if (!first) {
                json.writeComma()
                        .writeObjectEntry("collections", delta(collections, previousCollections[c]))
                        .writeComma()
                        .writeObjectEntry("ms", delta(ms, previousCollectionMs[c]));
            }
            json.writeObjectEnd();
            previousCollections[c] = collections;
            previousCollectionMs[c] = ms;
        }
        json.writeArrayEnd().writeObjectEnd();
    }

    private void writeClock(final JsonEncoder json) {
        json.writeString("clock")
                .writeColon()
                .writeObjectStart()
                .writeObjectEntry("source", clock.source().name())
                .writeComma()
                .writeObjectEntry("errorBoundNanos", clock.errorBoundNanos())
                .writeComma()
                .writeObjectEntry("offsetNanos", clock.offsetNanos())
                .writeComma()
                .writeObjectEntry("ageMs", clock.ageMs())
                .writeObjectEnd();
    }

    // An agent whose loop made no passes in the interval was stuck for all of it; its CPU time says whether it spun.
    private void writeThreads(final JsonEncoder json) {
        json.writeString("threads").writeColon().writeArrayStart();
        for (int i = 0; i < agents.length; i++) {
            final long cycles = agents[i].cycles();
            final Thread thread = agents[i].getThread();
            final long cpu = thread == null || thread == GnomeAgentRunner.TOMBSTONE
                    ? UNAVAILABLE
                    : jvm.threadCpuNanos(thread.getId());
            if (i > 0) {
                json.writeComma();
            }
            json.writeObjectStart()
                    .writeObjectEntry("name", agentNames[i])
                    .writeComma()
                    .writeObjectEntry("exited", agents[i].isClosed());
            if (!first) {
                json.writeComma()
                        .writeObjectEntry("cycles", delta(cycles, previousCycles[i]))
                        .writeComma()
                        .writeObjectEntry("cpuNanos", delta(cpu, previousCpu[i]));
            }
            json.writeObjectEnd();
            previousCycles[i] = cycles;
            previousCpu[i] = cpu;
        }
        json.writeArrayEnd();
    }

    private void writeScheduling(final JsonEncoder json) {
        json.writeString("scheduling").writeColon();
        final SchedulingLatencyProbe.Window window = probe == null ? null : probe.latest();
        if (window == null) {
            json.writeNull();
            return;
        }
        json.writeObjectStart()
                .writeObjectEntry("samples", window.samples())
                .writeComma()
                .writeObjectEntry("p50Micros", window.p50Micros())
                .writeComma()
                .writeObjectEntry("p99Micros", window.p99Micros())
                .writeComma()
                .writeObjectEntry("p999Micros", window.p999Micros())
                .writeComma()
                .writeObjectEntry("maxMicros", window.maxMicros())
                .writeComma()
                .writeObjectEntry("over1ms", window.over1ms())
                .writeComma()
                .writeObjectEntry("over5ms", window.over5ms())
                .writeObjectEnd();
    }

    private void writeAeron(final JsonEncoder json) {
        json.writeString("aeron").writeColon().writeObjectStart();
        for (int i = 0; i < AERON_MAX_CYCLES.length; i++) {
            if (i > 0) {
                json.writeComma();
            }
            json.writeObjectEntry(AERON_MAX_CYCLE_NAMES[i], aeron.getCounterValue(AERON_MAX_CYCLES[i].id()));
        }
        for (int i = 0; i < AERON_COUNTERS.length; i++) {
            final long value = aeron.getCounterValue(AERON_COUNTERS[i].id());
            if (!first) {
                json.writeComma().writeObjectEntry(AERON_NAMES[i], delta(value, previousAeron[i]));
            }
            previousAeron[i] = value;
        }
        json.writeObjectEnd();
    }

    private static long delta(final long now, final long before) {
        return now == UNAVAILABLE || before == UNAVAILABLE ? UNAVAILABLE : now - before;
    }
}
