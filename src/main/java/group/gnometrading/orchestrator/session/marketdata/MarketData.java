package group.gnometrading.orchestrator.session.marketdata;

import group.gnometrading.orchestrator.runtime.GatewayHealth;
import java.util.List;
import org.agrona.ErrorHandler;

/** A session's market data, as the close order and the heartbeat see it. */
public interface MarketData extends AutoCloseable {
    /** Starts delivering; may wait for it to be ready, and throws if it can't be. */
    void start(ErrorHandler errorHandler) throws Exception;

    /** One entry per listing; filled once it has started. */
    List<GatewayHealth> health();
}
