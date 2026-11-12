package group.gnometrading.orchestrator.session.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.RegistryConnection;
import group.gnometrading.account.SessionAccounts;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.HostAddress;
import group.gnometrading.orchestrator.aeron.ServiceEndpoint;
import group.gnometrading.orchestrator.aeron.ServiceEndpoints;
import group.gnometrading.orchestrator.aws.SessionTag;
import group.gnometrading.orchestrator.runtime.DaemonThreads;
import group.gnometrading.orchestrator.session.lifecycle.OrderGatewayLinkStatus;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * A session's order entry: every listing's orders go through its venue's order gateway, one link per venue and one
 * client thread. Live, the gateway trades the session's bound venue account, and startup recovery asks the venue through
 * the same links, so the session holds no trading key; paper, the venue's paper gateway simulates it, and recovery
 * settles from the ledger alone.
 */
public final class SessionOrders {

    // The OMS waits when one is full, and it reads the market data the strategy sees (to price takes against it): room
    // for a kill sweep's cancels or a busy strategy's reports keeps a slow venue writer from stopping the books too.
    public static final int OUTBOUND_BUFFER_SIZE = 1024;
    // How long startup recovery waits for the gateway's answer, which waits on the venue and the account's read budget.
    private static final long RECOVERY_QUERY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    // A paper session is bound to no venue account: it trades on its paper gateway's one simulated account.
    private static final int PAPER_ACCOUNT_ID = 0;

    private final Logger logger;
    private final Properties properties;
    private final EpochNanoClock clock;
    private final Supplier<RegistryConnection> registry;
    private SessionAccounts sessionAccounts;
    private final AeronRuntime aeron;
    private GatewayLinks gatewayLinks;
    private ScheduledExecutorService gatewayControl;
    private List<GatewayLink> links = List.of();

    public SessionOrders(
            Logger logger,
            Properties properties,
            EpochNanoClock clock,
            Supplier<RegistryConnection> registry,
            AeronRuntime aeron) {
        this.logger = logger;
        this.properties = properties;
        this.clock = clock;
        this.registry = registry;
        this.aeron = aeron;
    }

    /**
     * Every listing's orders through its venue's order gateway: one link per venue, read and written by one
     * {@link GatewayClient} straight from the OMS's buffers. Startup recovery asks a live venue through the client too,
     * so the client starts, and is welcomed, before recovery runs; each live listing's lookup goes in
     * {@code venueQueries}. A paper session's go nowhere: its simulated venue holds nothing a restart could ask about.
     *
     * @return the client, to run on its own thread
     */
    public GnomeAgent wire(
            List<Listing> listings,
            SequencedRingBuffer<Intent> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> omsExecReportBuffer,
            Map<Integer, VenueOrderQuery> venueQueries) {
        boolean live = "live".equals(properties.getStringProperty("mode"));
        String sessionId = SessionTag.of(properties);
        List<GatewayLink> links = links(listings, live);
        this.links = links;
        GatewayClient client = new GatewayClient(
                links,
                orderOutboundBuffer,
                omsExecReportBuffer,
                new GatewayClient.Identity(
                        sessionSeq(),
                        sessionId,
                        properties.getIntProperty("strategy.id"),
                        properties.getIntProperty("order.gateway.cod.timeout.ms"),
                        properties.getIntProperty("order.gateway.max.queue.ms"),
                        optionalInt(properties, "order.gateway.max.open.orders"),
                        optionalInt(properties, "order.gateway.max.new.per.second")),
                properties.getIntProperty("order.gateway.max.orders"),
                System::nanoTime,
                clock);
        if (live) {
            VenueOrderQuery recovery = new GatewayVenueOrderQuery(client, RECOVERY_QUERY_TIMEOUT_NANOS);
            for (Listing listing : listings) {
                venueQueries.put(listing.listingId(), recovery);
            }
        }
        String local = properties.hasProperty("order.gateway.local")
                ? properties.getStringProperty("order.gateway.local").trim()
                : "";
        this.gatewayLinks = new GatewayLinks(
                logger,
                aeron.aeron(),
                local.isEmpty() ? HostAddress.privateIp() : "127.0.0.1",
                links,
                local.isEmpty() ? endpointsResolver() : localResolver(local),
                // A local run names its gateway, which nothing starts for it.
                local.isEmpty() ? this::want : GatewayLinks.Wanting.NONE,
                System::nanoTime);
        this.gatewayControl = DaemonThreads.scheduler("gateway-links");
        this.gatewayControl.scheduleWithFixedDelay(gatewayLinks, 0, 100, TimeUnit.MILLISECONDS);
        logger.logf(LogMessage.DEBUG, "Orders go through %d order gateway(s)", links.size());
        return client;
    }

    /** One link per venue the listings are on, to its live gateway or its paper one. */
    private List<GatewayLink> links(List<Listing> listings, boolean live) {
        Map<Integer, List<long[]>> byExchange = new LinkedHashMap<>();
        Map<Integer, String> codes = new HashMap<>();
        for (Listing listing : listings) {
            int exchangeId = listing.exchange().exchangeId();
            byExchange
                    .computeIfAbsent(exchangeId, id -> new ArrayList<>())
                    .add(new long[] {exchangeId, listing.security().securityId()});
            codes.put(exchangeId, listing.exchange().exchangeCode().toLowerCase(Locale.ROOT));
        }
        List<GatewayLink> links = new ArrayList<>();
        for (Map.Entry<Integer, List<long[]>> exchange : byExchange.entrySet()) {
            links.add(new GatewayLink(
                    (live ? "live:" : "paper:") + codes.get(exchange.getKey()) + ":main",
                    exchange.getKey(),
                    live ? sessionAccounts().forExchange(exchange.getKey()).accountId() : PAPER_ACCOUNT_ID,
                    exchange.getValue(),
                    properties.getIntProperty("order.gateway.pending.messages")));
        }
        return links;
    }

    /** Tells the registry the session wants the gateway: it starts one that isn't running and keeps it running. */
    private void want(String gatewayKey) {
        registry.get()
                .post(
                        new ViewString("/api/gateways/ensure?gatewayKey="
                                + URLEncoder.encode(gatewayKey, StandardCharsets.UTF_8)),
                        new byte[0],
                        0);
    }

    /**
     * The session's slot at every gateway: its registry {@code session_seq}, never reused; a local run, which has no
     * registry session, names its own ({@code session.seq}).
     */
    private long sessionSeq() {
        if (!properties.hasProperty("session.id")) {
            long seq = properties.hasProperty("session.seq") ? properties.getLongProperty("session.seq") : 0;
            if (seq <= 0) {
                throw new IllegalStateException("A run without a registry session needs session.seq for its gateways");
            }
            return seq;
        }
        String sessionId = properties.getStringProperty("session.id");
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

    /** Each order gateway link, for the heartbeat. */
    public List<? extends OrderGatewayLinkStatus> gatewayLinks() {
        return this.links;
    }

    /**
     * Waits until every order gateway link is ready, so the strategy's first orders aren't refused as the session
     * starts; the links' control thread meanwhile asks the registry for any gateway not running.
     *
     * @throws IllegalStateException if they aren't ready in time: a session that can't reach its gateway can't trade
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

    /** A whole-number property the session may leave out: 0, which asks for nothing of its own. */
    private static int optionalInt(final Properties properties, final String key) {
        return properties.hasProperty(key) ? properties.getIntProperty(key) : 0;
    }
}
