package group.gnometrading.gateways.outbound;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.gateways.GatewayConfig;
import group.gnometrading.logging.Logger;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;

public abstract class DefaultOutboundOrchestrator extends Orchestrator {

    protected static final int DEFAULT_QUEUE_CAPACITY = 1 << 7;

    public static Class<? extends DefaultOutboundOrchestrator> findOutboundOrchestrator(final Listing listing) {
        switch (listing.exchange().exchangeName().toLowerCase()) {
            case "polymarket" -> {
                return PolymarketOutboundOrchestrator.class;
            }
            case "kalshi" -> {
                return KalshiOutboundOrchestrator.class;
            }
            default -> throw new IllegalArgumentException("No live outbound gateway for exchange: "
                    + listing.exchange().exchangeName());
        }
    }

    protected final ManyToOneRingBuffer<OrderContext> createOrderContextQueue() {
        return new ManyToOneRingBuffer<>(OrderContext[]::new, OrderContext::new, DEFAULT_QUEUE_CAPACITY);
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
     * Starts the reader and supervisor on background threads; returns the writer agent for the
     * caller to run on its own thread (it polls the order outbound ring buffer).
     */
    public abstract GnomeAgent startGatewayAgents(
            SequencedRingBuffer<?> orderOutboundBuffer,
            SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
            ErrorHandler errorHandler);
}
