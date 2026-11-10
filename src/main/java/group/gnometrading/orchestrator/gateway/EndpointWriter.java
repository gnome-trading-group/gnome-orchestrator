package group.gnometrading.orchestrator.gateway;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.gateway.control.GatewayControl;
import group.gnometrading.schemas.gateway.GatewayState;
import group.gnometrading.utils.CauseChain;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * The gateway's endpoint item, written on a thread of its own: the control thread only hands over what it would say,
 * and never waits on the table. A write that takes long is overtaken: only the latest state waits to be written.
 */
final class EndpointWriter implements GatewayControl.Advertiser {

    /** Writes one endpoint item; may block, and may throw. */
    interface Write {
        void write(GatewayState state, int linkedSessions, long workingOrders) throws Exception;
    }

    private record Advert(GatewayState state, int linkedSessions, long workingOrders) {}

    private final Logger logger;
    private final Write write;
    private final BlockingQueue<Advert> latest = new ArrayBlockingQueue<>(1);

    EndpointWriter(final Logger logger, final Write write) {
        this.logger = logger;
        this.write = write;
    }

    @Override
    public synchronized void advertise(final GatewayState state, final int linkedSessions, final long workingOrders) {
        this.latest.clear();
        this.latest.offer(new Advert(state, linkedSessions, workingOrders));
    }

    EndpointWriter start() {
        final Thread thread = new Thread(
                () -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            final Advert advert = this.latest.take();
                            this.write.write(advert.state(), advert.linkedSessions(), advert.workingOrders());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            this.logger.logf(
                                    LogMessage.UNKNOWN_ERROR,
                                    "Failed to write the gateway's endpoint: %s",
                                    CauseChain.of(e));
                        }
                    }
                },
                "gateway-endpoint");
        thread.setDaemon(true);
        thread.start();
        return this;
    }
}
