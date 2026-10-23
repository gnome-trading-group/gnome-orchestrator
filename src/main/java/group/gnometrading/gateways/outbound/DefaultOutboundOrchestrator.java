package group.gnometrading.gateways.outbound;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.GatewayErrorHandler;
import group.gnometrading.gateways.GatewayRunners;
import group.gnometrading.gateways.ProcessExit;
import group.gnometrading.gateways.outbound.recovery.VenueOrderQuery;
import group.gnometrading.gateways.ratelimit.VenueRateLimiter;
import group.gnometrading.gateways.ratelimit.VenueRateLimiters;
import group.gnometrading.gateways.ratelimit.VenueRateLimits;
import group.gnometrading.logging.Logger;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemNanoClock;

public abstract class DefaultOutboundOrchestrator extends Orchestrator {

    public static Class<? extends DefaultOutboundOrchestrator> findOutboundOrchestrator(final Listing listing) {
        switch (listing.exchange().exchangeCode()) {
            case "POLYMARKET_INTL" -> {
                return PolymarketIntlOutboundOrchestrator.class;
            }
            case "KALSHI" -> {
                return KalshiOutboundOrchestrator.class;
            }
            case "POLYMARKET_US" -> {
                return PolymarketUsOutboundOrchestrator.class;
            }
            default -> throw new IllegalArgumentException("No live outbound gateway for exchange code: "
                    + listing.exchange().exchangeCode() + " ("
                    + listing.exchange().exchangeName() + ")");
        }
    }

    /** A trading session puts its own in the child's bindings, which take precedence over this. */
    @Provides
    @Singleton
    public ProcessExit provideProcessExit() {
        return ProcessExit.SYSTEM;
    }

    /**
     * Only for a gateway started on its own: a trading session passes in one registry for all its gateways, so that
     * listings on the same account share one budget.
     */
    @Provides
    @Singleton
    public VenueRateLimiters provideVenueRateLimiters(final Logger logger) {
        return new VenueRateLimiters(logger, SystemNanoClock.INSTANCE, SystemEpochClock.INSTANCE);
    }

    /**
     * The limiter every HTTP client on {@code account} shares: {@code defaults} adjusted by the properties under
     * {@code propertyPrefix} (see {@link VenueRateLimits#withOverrides}).
     */
    protected final VenueRateLimiter venueRateLimiter(
            final VenueRateLimits defaults, final String propertyPrefix, final String account) {
        final VenueRateLimits limits =
                defaults.withOverrides(getInstance(Properties.class).getPropertiesByPrefix(propertyPrefix));
        return getInstance(VenueRateLimiters.class).forAccount(limits, account);
    }

    protected final ManyToOneRingBuffer<OrderContext> createOrderContextQueue() {
        return new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, OrderContext.HANDOFF_QUEUE_CAPACITY);
    }

    protected final OutboundAgents startAgents(
            final OutboundSocketReader reader,
            final OutboundSocketWriter writer,
            final GatewayConfig config,
            final Logger logger,
            final EpochClock epochClock) {
        final OutboundGateway gateway = new OutboundGateway(logger, reader, config, epochClock);
        // A dropped socket surfaces as a reader error; reconnecting (and catching up) is the answer, not an exit.
        final ErrorHandler errorHandler = new GatewayErrorHandler(
                logger, "order gateway", gateway::forceReconnect, getInstance(ProcessExit.class));
        final GatewayRunners runners = GatewayRunners.venue(
                        getInstance(Listing.class).listingId(),
                        reader.pauseControl,
                        new GnomeAgentRunner(reader, errorHandler),
                        new GnomeAgentRunner(gateway, errorHandler))
                .start();
        return new OutboundAgents(writer, runners);
    }

    /**
     * The order writer, for the caller to run on its own thread (it polls the order outbound ring buffer), and the
     * gateway's other threads, already running, for the caller to close once the writer has stopped.
     */
    public record OutboundAgents(GnomeAgent writer, GatewayRunners gateway) {}

    /**
     * What a starting session asks the venue about orders earlier sessions left. Shares the writer's HTTP client
     * and credentials, so it must be done with before the writer starts.
     */
    public abstract VenueOrderQuery createVenueOrderQuery();

    /** Starts the reader and supervisor on background threads, and returns them with the writer agent. */
    public abstract OutboundAgents startGatewayAgents(
            SequencedRingBuffer<?> orderOutboundBuffer, SequencedRingBuffer<OrderExecutionReport> execReportBuffer);
}
