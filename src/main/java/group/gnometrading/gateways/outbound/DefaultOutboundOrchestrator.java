package group.gnometrading.gateways.outbound;

import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import org.agrona.ErrorHandler;

public abstract class DefaultOutboundOrchestrator extends Orchestrator {

    public static Class<? extends DefaultOutboundOrchestrator> findOutboundOrchestrator(final Listing listing) {
        switch (listing.exchange().exchangeName().toLowerCase()) {
            case "polymarket" -> {
                return PolymarketOutboundOrchestrator.class;
            }
            default -> throw new IllegalArgumentException("No live outbound gateway for exchange: "
                    + listing.exchange().exchangeName());
        }
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
