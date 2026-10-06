package group.gnometrading.gateways.outbound;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.gateways.outbound.recovery.VenueOrderQuery;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;

public abstract class DefaultOutboundOrchestrator extends Orchestrator {

    public static Class<? extends DefaultOutboundOrchestrator> findOutboundOrchestrator(final Listing listing) {
        switch (listing.exchange().exchangeCode()) {
            case "POLYMARKET_INTL" -> {
                return PolymarketIntlOutboundOrchestrator.class;
            }
            case "KALSHI" -> {
                return KalshiOutboundOrchestrator.class;
            }
            default -> throw new IllegalArgumentException("No live outbound gateway for exchange code: "
                    + listing.exchange().exchangeCode() + " ("
                    + listing.exchange().exchangeName() + ")");
        }
    }

    protected final ManyToOneRingBuffer<OrderContext> createOrderContextQueue() {
        return new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, OrderContext.HANDOFF_QUEUE_CAPACITY);
    }

    protected final GnomeAgent startAgents(
            final OutboundSocketReader reader,
            final OutboundSocketWriter writer,
            final GatewayConfig config,
            final Logger logger,
            final EpochClock epochClock,
            final ErrorHandler errorHandler) {
        final OutboundGateway gateway = new OutboundGateway(logger, reader, config, epochClock);
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(gateway, errorHandler));
        GnomeAgentRunner.startOnThread(new GnomeAgentRunner(reader, errorHandler));
        return writer;
    }

    /**
     * What a starting session asks the venue about orders earlier sessions left. Shares the writer's HTTP client
     * and credentials, so it must be done with before the writer starts.
     */
    public abstract VenueOrderQuery createVenueOrderQuery();

    /**
     * Starts the reader and supervisor on background threads; returns the writer agent for the
     * caller to run on its own thread (it polls the order outbound ring buffer).
     */
    public abstract GnomeAgent startGatewayAgents(
            SequencedRingBuffer<?> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            ErrorHandler errorHandler);
}
