package group.gnometrading.orchestrator.gateway;

import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.host.HostReadings;
import group.gnometrading.host.JvmReadings;
import group.gnometrading.latency.MinuteHistograms;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.metrics.CounterSampler;
import group.gnometrading.metrics.SampleBuffer;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.gateway.accounts.GatewayAccount;
import group.gnometrading.orchestrator.gateway.accounts.LaneAdmissions;
import group.gnometrading.orchestrator.gateway.accounts.LanesSampler;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import group.gnometrading.orchestrator.gateway.simulated.SimulatedAccount;
import group.gnometrading.orchestrator.gateway.transport.CrossIndex;
import group.gnometrading.orchestrator.gateway.transport.DropsSampler;
import group.gnometrading.orchestrator.gateway.transport.GatewayEgress;
import group.gnometrading.orchestrator.gateway.transport.GatewayIngress;
import group.gnometrading.orchestrator.marketdata.FeedMetrics;
import group.gnometrading.orchestrator.marketdata.FeedSampler;
import group.gnometrading.orchestrator.marketdata.ShardMarketData;
import group.gnometrading.orchestrator.runtime.ClockReadings;
import group.gnometrading.orchestrator.runtime.LatencySampler;
import group.gnometrading.orchestrator.runtime.RingSampler;
import group.gnometrading.orchestrator.runtime.RuntimeSampler;
import group.gnometrading.orchestrator.runtime.SamplePoster;
import group.gnometrading.orchestrator.runtime.SchedulingLatencyProbe;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;
import org.agrona.concurrent.SystemEpochClock;

/**
 * An order gateway's samples (subject {@code gateway}, its key), posted on their own thread every
 * {@link #POST_MILLIS}: {@code runtime} for every thread it runs, its order path's ({@link #live}), and for a paper
 * gateway each minute
 * {@code gateway.agents} (the simulated lane's and the shard reader's loops), {@code gateway.rings} (the reader's ring
 * into the lane), {@code feed.shards} (per shard read: records, network and in-shard peaks, status gaps; the streams
 * furthest behind), and {@code latency.feed} / {@code latency.paper} as their minutes close: together a record's path
 * from the venue to the books, in segments.
 */
final class GatewaySamples {

    static final long POST_MILLIS = 30_000;
    static final long STOP_MILLIS = 5_000;
    private static final long MINUTE_MILLIS = 60_000;
    // About ten minutes of a busy paper gateway's samples, held through a samples API outage.
    private static final int BUFFER_BYTES = 512 * 1024;
    private static final int MAX_PENDING = 1_024;
    private static final int MAX_SAMPLE_BYTES = 48 * 1024;
    private static final long PROBE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    /** What every gateway has: its accounts and their lanes' admissions, ingress, egress, sessions, self-trade index. */
    record Live(
            List<GatewayAccount> accounts,
            List<LaneAdmissions> admissions,
            GatewayIngress ingress,
            GatewayEgress egress,
            SessionTable sessions,
            CrossIndex cross) {}

    /** A paper gateway's simulated venue and its reader; null parts for a live one. */
    record Paper(SimulatedAccount account, ShardMarketData marketData, FeedMetrics feed, SequencedRingBuffer<?> ring) {}

    private GatewaySamples() {}

    /**
     * The order path every gateway has: {@code gateway.transport} (what ingress passed on and refused by reason, what
     * egress took and sent), {@code gateway.drops}, {@code gateway.lanes} per account as its lane's minute closes, and
     * the latency sets of ingress ({@code gw.transit}), egress ({@code gw.report_out}) and each account's lane
     * ({@code gw.queue}, {@code gw.budget_wait}, {@code gw.sign_send}, {@code venue.answer}, kind
     * {@code latency.lane.<accountId>}).
     */
    private static void live(
            final SampleBuffer buffer,
            final Live live,
            final List<LongPredicate> samplers,
            final List<MinuteHistograms> sets,
            final List<String> kinds) {
        final CounterSampler transport = new CounterSampler(
                buffer,
                "gateway.transport",
                List.of("ingress", "refused", "egress"),
                List.of(
                        live.ingress().counters(),
                        live.ingress().refusals(),
                        live.egress().counters()),
                MINUTE_MILLIS);
        final DropsSampler drops =
                new DropsSampler(buffer, live.ingress(), live.egress(), live.sessions(), live.cross(), MINUTE_MILLIS);
        samplers.add(transport::sampleIfDue);
        samplers.add(drops::sampleIfDue);
        if (!live.accounts().isEmpty()) {
            final SessionTable sessions = live.sessions();
            final List<LanesSampler.Lane> accounts = new ArrayList<>();
            for (int i = 0; i < live.accounts().size(); i++) {
                accounts.add(LanesSampler.Lane.of(
                        live.accounts().get(i), live.admissions().get(i)));
            }
            final LanesSampler lanes = new LanesSampler(
                    buffer,
                    accounts,
                    slot -> {
                        final GatewaySession session = sessions.bySlot(slot);
                        return session == null ? null : session.sessionId;
                    },
                    System::nanoTime);
            samplers.add(lanes::sampleIfDue);
        }
        sets.add(live.ingress().latency());
        kinds.add(GatewayIngress.LATENCY_KIND);
        sets.add(live.egress().latency());
        kinds.add(GatewayEgress.LATENCY_KIND);
        for (final LaneAdmissions lane : live.admissions()) {
            sets.add(lane.latency());
            kinds.add("latency.lane." + lane.accountId());
        }
    }

    /**
     * Starts them once every thread has; null, and nothing started, without a sender (no API key: a local run).
     *
     * @param runners every thread the gateway started, null entries skipped
     */
    static SamplePoster start(
            final Logger logger,
            final String gatewayKey,
            final SamplePoster.Sender sender,
            final long runtimeIntervalMs,
            final AeronRuntime aeron,
            final List<GnomeAgentRunner> runners,
            final Live live,
            final Paper paper) {
        if (sender == null) {
            logger.logf(LogMessage.DEBUG, "No samples API key: gateway %s posts no samples", gatewayKey);
            return null;
        }
        final SampleBuffer buffer = new SampleBuffer(BUFFER_BYTES, MAX_PENDING, MAX_SAMPLE_BYTES);
        final List<GnomeAgentRunner> threads = new ArrayList<>();
        for (final GnomeAgentRunner runner : runners) {
            if (runner != null) {
                threads.add(runner);
            }
        }
        final List<LongPredicate> samplers = new ArrayList<>();
        final List<MinuteHistograms> sets = new ArrayList<>();
        final List<String> kinds = new ArrayList<>();
        live(buffer, live, samplers, sets, kinds);
        if (paper != null) {
            threads.add(paper.account().runner());
            threads.add(paper.marketData().runner());
            final CounterSampler agents = new CounterSampler(
                    buffer,
                    "gateway.agents",
                    List.of("lane", "feed"),
                    List.of(paper.account().laneCounters(), paper.feed().counters()),
                    MINUTE_MILLIS);
            final RingSampler rings = new RingSampler(
                    buffer, "gateway.rings", List.of("marketData"), List.of(paper.ring()), MINUTE_MILLIS);
            final FeedSampler feed = paper.marketData().sampler(buffer, MINUTE_MILLIS);
            sets.addAll(paper.feed().sets());
            kinds.addAll(FeedMetrics.kinds());
            sets.add(paper.account().laneLatency());
            kinds.add(SimulatedAccount.LATENCY_KIND);
            samplers.add(agents::sampleIfDue);
            samplers.add(rings::sampleIfDue);
            samplers.add(feed::sampleIfDue);
        }
        final LatencySampler latency = new LatencySampler(buffer, sets, kinds);
        samplers.add(latency::sampleIfDue);
        final RuntimeSampler runtime = new RuntimeSampler(
                buffer,
                HostReadings.ofThisProcess(new File("/")),
                new JvmReadings(),
                ClockReadings.ofThisHost(SystemEpochClock.INSTANCE),
                SchedulingLatencyProbe.start(PROBE_INTERVAL_NANOS),
                aeron.aeron().countersReader(),
                threads,
                runtimeIntervalMs);
        samplers.add(0, runtime::sampleIfDue);
        final SamplePoster poster = new SamplePoster(
                logger, "gateway", gatewayKey, buffer, samplers, sender, System::currentTimeMillis, POST_MILLIS);
        poster.start("gateway-samples");
        return poster;
    }
}
