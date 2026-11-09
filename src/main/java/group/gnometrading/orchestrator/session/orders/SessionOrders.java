package group.gnometrading.orchestrator.session.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.account.SessionAccounts;
import group.gnometrading.account.VenueAccount;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.gateways.ratelimit.VenueRateLimiters;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.md.StreamDirectory;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.HostAddress;
import group.gnometrading.orchestrator.aeron.ServiceEndpoint;
import group.gnometrading.orchestrator.aeron.ServiceEndpoints;
import group.gnometrading.orchestrator.aws.SessionTag;
import group.gnometrading.orchestrator.runtime.DaemonThreads;
import group.gnometrading.orchestrator.runtime.GatewayRunners;
import group.gnometrading.orchestrator.runtime.ProcessExit;
import group.gnometrading.orchestrator.session.journal.JournalSource;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.simulation.config.ExchangeSimulationConfig;
import group.gnometrading.simulation.config.SessionSimulationConfig;
import group.gnometrading.simulation.config.SimulationResolver;
import group.gnometrading.simulation.exchange.MbpSimulatedExchange;
import group.gnometrading.simulation.latency.LatencySeeds;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.fee.FeeTimeline;
import group.gnometrading.strings.ViewString;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemNanoClock;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * A session's order entry: per listing, the simulated exchange (paper) or the venue's order gateway (live), and with
 * several listings the router between them and the OMS. Holds the venue gateways' threads for the close order, the
 * request budgets every listing on one venue account shares, and the accounts the registry bound to the session.
 */
public final class SessionOrders {

    // The OMS waits when one is full, and it reads the market data the strategy sees (to price takes against it): room
    // for a kill sweep's cancels or a busy strategy's reports keeps a slow venue writer from stopping the books too.
    public static final int OUTBOUND_BUFFER_SIZE = 1024;
    // Paper sessions are reproducible by default; set simulation.seed to vary the latency draws.
    private static final long PAPER_TRADING_DEFAULT_SEED = 0x9E3779B97F4A7C15L;

    /** Builds a live listing's gateway as a child of the session, so it inherits the session's bindings. */
    @FunctionalInterface
    public interface Gateways {
        DefaultOutboundOrchestrator create(
                Class<? extends DefaultOutboundOrchestrator> type, Map<Class<?>, Object> overrides);
    }

    /** The router's copy of each order and execution report, one pair of buffers per listing. */
    public record RoutedBuffers(
            List<SequencedRingBuffer<Intent>> orders, List<SequencedRingBuffer<OrderExecutionReport>> execReports) {}

    /** What runs order entry: an agent per listing, and the router when there are several. */
    public record Wiring(List<GnomeAgent> agents, ListingRouter router) {}

    private final Logger logger;
    private final Properties properties;
    private final SecurityMaster securityMaster;
    private final EpochNanoClock clock;
    private final Supplier<RegistryConnection> registry;
    private final ProcessExit exit;
    private final Gateways gateways;
    // One per session, so every listing trading on the same venue account draws on one request budget.
    private final VenueRateLimiters rateLimiters;
    // The live venue gateways' reader and supervisor threads; their order writers are among the session's agents.
    private final List<GatewayRunners> venueGateways = new ArrayList<>();
    private SessionAccounts sessionAccounts;
    private final AeronRuntime aeron;
    private GatewayLinks gatewayLinks;
    private ScheduledExecutorService gatewayControl;
    private List<GatewayLink> links = List.of();

    /**
     * @param exit how a live gateway ends the session; null for a local run, which can't trade live
     */
    public SessionOrders(
            Logger logger,
            Properties properties,
            SecurityMaster securityMaster,
            EpochNanoClock clock,
            Supplier<RegistryConnection> registry,
            ProcessExit exit,
            Gateways gateways,
            AeronRuntime aeron) {
        this.logger = logger;
        this.properties = properties;
        this.securityMaster = securityMaster;
        this.clock = clock;
        this.registry = registry;
        this.exit = exit;
        this.gateways = gateways;
        this.aeron = aeron;
        this.rateLimiters = new VenueRateLimiters(logger, SystemNanoClock.INSTANCE, SystemEpochClock.INSTANCE);
    }

    /** With one listing the OMS's buffers are the gateway's; with several, the router's per-listing pairs. */
    public static RoutedBuffers routedBuffers(
            int listingCount,
            GlobalSequence sessionSequence,
            Map<SequencedRingBuffer<?>, JournalSource> journalSources) {
        if (listingCount == 1) {
            return null;
        }
        List<SequencedRingBuffer<Intent>> orders = new ArrayList<>(listingCount);
        List<SequencedRingBuffer<OrderExecutionReport>> execReports = new ArrayList<>(listingCount);
        for (int i = 0; i < listingCount; i++) {
            SequencedRingBuffer<Intent> order =
                    new SequencedRingBuffer<>(Intent::new, sessionSequence, OUTBOUND_BUFFER_SIZE);
            SequencedRingBuffer<OrderExecutionReport> execReport =
                    new SequencedRingBuffer<>(OrderExecutionReport::new, sessionSequence, OUTBOUND_BUFFER_SIZE);
            journalSources.put(order, JournalSource.ROUTED_ORDER);
            journalSources.put(execReport, JournalSource.ROUTED_EXEC_REPORT);
            orders.add(order);
            execReports.add(execReport);
        }
        return new RoutedBuffers(orders, execReports);
    }

    /**
     * Builds each listing's order entry, reading the session's market data from {@code marketData}, and records each
     * live listing's venue order lookup in {@code venueQueries} for startup recovery.
     */
    public Wiring wire(
            List<Listing> listings,
            StreamDirectory streams,
            SequencedRingBuffer<?> marketData,
            SequencedRingBuffer<Intent> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> omsExecReportBuffer,
            RoutedBuffers routedBuffers,
            Map<Integer, VenueOrderQuery> venueQueries) {
        if (gatewayEnabled()) {
            return wireGateway(listings, orderOutboundBuffer, omsExecReportBuffer, venueQueries);
        }
        List<GnomeAgent> agents = new ArrayList<>(listings.size());
        if (routedBuffers == null) {
            agents.add(gateway(
                    listings.get(0), streams, marketData, orderOutboundBuffer, omsExecReportBuffer, venueQueries));
            return new Wiring(agents, null);
        }
        Map<Long, SequencedRingBuffer<?>> perListingOutBufs = new HashMap<>();
        List<SequencedRingBuffer<OrderExecutionReport>> perExchangeExecBufs = routedBuffers.execReports();
        for (int i = 0; i < listings.size(); i++) {
            Listing listing = listings.get(i);
            long routingKey = ListingRouter.routingKey(
                    listing.exchange().exchangeId(), listing.security().securityId());
            SequencedRingBuffer<Intent> perExchangeOutBuf =
                    routedBuffers.orders().get(i);
            perListingOutBufs.put(routingKey, perExchangeOutBuf);
            agents.add(
                    gateway(listing, streams, marketData, perExchangeOutBuf, perExchangeExecBufs.get(i), venueQueries));
        }
        return new Wiring(
                agents,
                new ListingRouter(orderOutboundBuffer, perListingOutBufs, perExchangeExecBufs, omsExecReportBuffer));
    }

    /** Whether the session's live orders go through order gateways rather than its own per-listing gateways. */
    private boolean gatewayEnabled() {
        return "live".equals(properties.getStringProperty("mode"))
                && properties.getBooleanProperty("order.gateway.enabled");
    }

    /**
     * Every listing's orders through its venue's order gateway: one link per venue, read and written by one
     * {@link GatewayClient} straight from the OMS's buffers, with no router. Each listing's venue lookup is still built
     * here, for startup recovery.
     */
    private Wiring wireGateway(
            List<Listing> listings,
            SequencedRingBuffer<Intent> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> omsExecReportBuffer,
            Map<Integer, VenueOrderQuery> venueQueries) {
        String sessionId = properties.getStringProperty("session.id");
        Map<Integer, List<long[]>> byExchange = new LinkedHashMap<>();
        Map<Integer, String> codes = new HashMap<>();
        for (Listing listing : listings) {
            int exchangeId = listing.exchange().exchangeId();
            byExchange
                    .computeIfAbsent(exchangeId, id -> new ArrayList<>())
                    .add(new long[] {exchangeId, listing.security().securityId()});
            codes.put(exchangeId, listing.exchange().exchangeCode().toLowerCase(Locale.ROOT));
            venueQueries.put(listing.listingId(), outbound(listing).createVenueOrderQuery());
        }
        List<GatewayLink> links = new ArrayList<>();
        for (Map.Entry<Integer, List<long[]>> exchange : byExchange.entrySet()) {
            links.add(new GatewayLink(
                    "live:" + codes.get(exchange.getKey()) + ":main",
                    exchange.getKey(),
                    sessionAccounts().forExchange(exchange.getKey()).accountId(),
                    exchange.getValue(),
                    properties.getIntProperty("order.gateway.pending.messages")));
        }
        this.links = links;
        GatewayClient client = new GatewayClient(
                links,
                orderOutboundBuffer,
                omsExecReportBuffer,
                new GatewayClient.Identity(
                        sessionSeq(sessionId),
                        sessionId,
                        properties.getIntProperty("strategy.id"),
                        properties.getIntProperty("order.gateway.cod.timeout.ms"),
                        properties.getIntProperty("order.gateway.max.queue.ms")),
                properties.getIntProperty("order.gateway.max.orders"),
                System::nanoTime,
                clock);
        String local = properties.hasProperty("order.gateway.local")
                ? properties.getStringProperty("order.gateway.local").trim()
                : "";
        this.gatewayLinks = new GatewayLinks(
                logger,
                aeron.aeron(),
                local.isEmpty() ? HostAddress.privateIp() : "127.0.0.1",
                links,
                local.isEmpty() ? endpointsResolver() : localResolver(local),
                System::nanoTime);
        this.gatewayControl = DaemonThreads.scheduler("gateway-links");
        this.gatewayControl.scheduleWithFixedDelay(gatewayLinks, 0, 100, TimeUnit.MILLISECONDS);
        logger.logf(LogMessage.DEBUG, "Orders go through %d order gateway(s)", links.size());
        return new Wiring(List.of(client), null);
    }

    /** Each listing's own venue orchestrator, built for its venue order lookup only; it sends nothing. */
    private DefaultOutboundOrchestrator outbound(Listing listing) {
        return gateways.create(
                DefaultOutboundOrchestrator.findOutboundOrchestrator(listing),
                Map.of(
                        Listing.class,
                        listing,
                        VenueRateLimiters.class,
                        rateLimiters,
                        FeeTimeline.class,
                        securityMaster.getFeeTimeline(listing.listingId()),
                        VenueAccount.class,
                        sessionAccounts().forExchange(listing.exchange().exchangeId()),
                        ProcessExit.class,
                        exit));
    }

    /** The session's slot at every gateway: its registry {@code session_seq}, never reused. */
    private long sessionSeq(String sessionId) {
        ByteBuffer body = registry.get()
                .get(new ViewString(
                        "/api/strategy-sessions?sessionId=" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)));
        byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        try {
            JsonNode rows = new ObjectMapper().readTree(bytes);
            long seq = rows.isEmpty() ? 0 : rows.get(0).path("session_seq").asLong();
            if (seq <= 0) {
                throw new IllegalStateException("The registry has no session_seq for session " + sessionId);
            }
            return seq;
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable session " + sessionId, e);
        }
    }

    /** Where gateways advertise themselves: the service-endpoints table, ready ones only. */
    private static GatewayLinks.Resolver endpointsResolver() {
        ServiceEndpoints endpoints = new ServiceEndpoints(DynamoDbClient.builder()
                .region(Region.of(ServiceEndpoints.REGION))
                .build());
        return key -> {
            ServiceEndpoint endpoint = endpoints.get(
                    ServiceEndpoint.Kind.ORDER_GATEWAY, key, TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()));
            return endpoint == null || !("READY".equals(endpoint.state()) || "DEGRADED".equals(endpoint.state()))
                    ? null
                    : new GatewayLinks.Endpoint(endpoint.privateIp(), endpoint.port());
        };
    }

    /** A local run: one gateway at {@code ip:port}, whatever the key. */
    private static GatewayLinks.Resolver localResolver(String address) {
        int colon = address.lastIndexOf(':');
        GatewayLinks.Endpoint endpoint =
                new GatewayLinks.Endpoint(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)));
        return key -> endpoint;
    }

    /**
     * Waits until every order gateway link is ready, so the strategy's first orders aren't refused as the session
     * starts. Without gateways there is nothing to wait for.
     *
     * @throws IllegalStateException if they aren't ready in time: a live session that can't reach its gateway can't
     *     trade
     */
    public void awaitGateways(long timeoutNanos) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutNanos;
        while (!links.stream().allMatch(link -> link.state == GatewayLink.READY)) {
            if (System.nanoTime() - deadline > 0) {
                throw new IllegalStateException("Order gateways not ready: "
                        + links.stream()
                                .filter(link -> link.state != GatewayLink.READY)
                                .map(link -> link.gatewayKey)
                                .toList());
            }
            Thread.sleep(50);
        }
    }

    /** Closes the gateway links, after the agent that uses them has stopped. */
    public void close() throws InterruptedException {
        if (gatewayControl != null) {
            gatewayControl.shutdownNow();
            gatewayControl.awaitTermination(5, TimeUnit.SECONDS);
            gatewayLinks.close();
        }
    }

    /** The live venue gateways' reader and supervisor threads, closed after the agents and watched by the heartbeat. */
    public List<GatewayRunners> venueGateways() {
        return venueGateways;
    }

    public VenueRateLimiters rateLimiters() {
        return rateLimiters;
    }

    private GnomeAgent gateway(
            Listing listing,
            StreamDirectory streams,
            SequencedRingBuffer<?> marketDataBuffer,
            SequencedRingBuffer<?> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            Map<Integer, VenueOrderQuery> venueQueries) {
        if ("paper".equals(properties.getStringProperty("mode"))) {
            return paperGateway(listing, streams, marketDataBuffer, orderOutboundBuffer, execReportBuffer);
        }
        // Each venue gateway is built for its own listing.
        DefaultOutboundOrchestrator outbound = gateways.create(
                DefaultOutboundOrchestrator.findOutboundOrchestrator(listing),
                Map.of(
                        Listing.class,
                        listing,
                        VenueRateLimiters.class,
                        rateLimiters,
                        // The fees the venue charges on this listing, for fills it doesn't report a fee for.
                        FeeTimeline.class,
                        securityMaster.getFeeTimeline(listing.listingId()),
                        VenueAccount.class,
                        sessionAccounts().forExchange(listing.exchange().exchangeId()),
                        // Live trading needs a session, so it always has a way to end it.
                        ProcessExit.class,
                        exit));
        venueQueries.put(listing.listingId(), outbound.createVenueOrderQuery());
        DefaultOutboundOrchestrator.OutboundAgents agents =
                outbound.startGatewayAgents(orderOutboundBuffer, execReportBuffer);
        venueGateways.add(agents.gateway());
        return agents.writer();
    }

    private GnomeAgent paperGateway(
            Listing listing,
            StreamDirectory streams,
            SequencedRingBuffer<?> marketDataBuffer,
            SequencedRingBuffer<?> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer) {
        // The session's config carries its simulation as one JSON value, in the shape a backtest config takes.
        SessionSimulationConfig simulation =
                SessionSimulationConfig.fromJson(properties.getStringProperty("simulation.json"));
        SimulationResolver resolver = new SimulationResolver(securityMaster, simulation.asOf);
        ExchangeSimulationConfig config = resolver.resolve(listing, false, simulation.layersFor(listing));
        logger.logf(
                LogMessage.DEBUG,
                "Listing %d simulates as of %s with %s",
                listing.listingId(),
                simulation.asOf,
                config.toJson());
        long baseSeed = simulation.seed != null ? simulation.seed : PAPER_TRADING_DEFAULT_SEED;
        MbpSimulatedExchange exchange = (MbpSimulatedExchange) config.toSimulatedExchange(
                listing.listingId(), LatencySeeds.derive(baseSeed, listing.listingId()), resolver.registryFees());
        return new PaperTradingOutboundGateway(
                exchange,
                marketDataBuffer,
                streams.streamId(listing.listingId()),
                orderOutboundBuffer,
                execReportBuffer,
                clock,
                SessionTag.of(properties));
    }

    /**
     * The venue accounts the registry bound to this session when it was created, read once. A live session trades
     * only through them, so one without a session (a local run) can't trade live.
     */
    private SessionAccounts sessionAccounts() {
        if (this.sessionAccounts == null) {
            if (!properties.hasProperty("session.id")) {
                throw new IllegalStateException("Live trading needs a registry session to bind its venue accounts");
            }
            this.sessionAccounts = SessionAccounts.fetch(registry.get(), properties.getStringProperty("session.id"));
        }
        return this.sessionAccounts;
    }
}
