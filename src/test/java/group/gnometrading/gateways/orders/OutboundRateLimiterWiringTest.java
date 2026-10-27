package group.gnometrading.gateways.orders;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.gateways.ratelimit.VenueRateLimiter;
import group.gnometrading.gateways.ratelimit.VenueRateLimiters;
import group.gnometrading.gateways.venues.polymarket.us.PolymarketUsVenue;
import group.gnometrading.logging.Logger;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import java.io.IOException;
import java.util.Map;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemNanoClock;
import org.junit.jupiter.api.Test;

class OutboundRateLimiterWiringTest {

    @Test
    void gatewaysGivenOneRegistryShareALimiterPerAccount() {
        final Session session = new Session();
        final VenueRateLimiters registry =
                new VenueRateLimiters(new NullLogger(), SystemNanoClock.INSTANCE, SystemEpochClock.INSTANCE);

        final Gateway first = session.gateway(Map.of(VenueRateLimiters.class, registry));
        final Gateway second = session.gateway(Map.of(VenueRateLimiters.class, registry));

        final VenueRateLimiter limiter = first.limiter("key-1");
        assertSame(limiter, second.limiter("key-1"), "listings on one account draw on one budget");
        assertNotSame(limiter, second.limiter("key-2"));
    }

    @Test
    void gatewayStartedOnItsOwnMakesItsOwnRegistry() {
        final Session session = new Session();

        final Gateway alone = session.gateway(Map.of());

        assertSame(alone.limiter("key-1"), alone.limiter("key-1"));
        assertNotSame(alone.limiter("key-1"), session.gateway(Map.of()).limiter("key-1"));
    }

    @Test
    void misspeltRateLimitPropertyStopsTheGatewayFromStarting() {
        final Session session = new Session("--polymarket.us.ratelimit.shar=0.5");

        assertThrows(
                IllegalArgumentException.class, () -> session.gateway(Map.of()).limiter("key-1"));
    }

    /** Provides what a trading session gives its gateways. */
    static final class Session extends Orchestrator {
        private final String[] args;

        Session(final String... args) {
            this.args = args;
        }

        @Provides
        @Singleton
        public Logger provideLogger() {
            return new NullLogger();
        }

        @Provides
        @Singleton
        public Properties provideProperties() throws IOException {
            return new Properties("orchestrator.dev.properties", this.args);
        }

        @Override
        public void configure() {}

        Gateway gateway(final Map<Class<?>, Object> overrides) {
            return createChildOrchestrator(Gateway.class, overrides);
        }
    }

    public static final class Gateway extends DefaultOutboundOrchestrator {
        @Override
        public void configure() {}

        VenueRateLimiter limiter(final String account) {
            return venueRateLimiter(PolymarketUsVenue.rateLimits(), "polymarket.us.ratelimit.", account);
        }

        @Override
        public VenueOrderQuery createVenueOrderQuery() {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutboundAgents startGatewayAgents(
                final SequencedRingBuffer<?> orderOutboundBuffer,
                final SequencedRingBuffer<OrderExecutionReport> execReportBuffer) {
            throw new UnsupportedOperationException();
        }
    }
}
