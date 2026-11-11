package group.gnometrading.orchestrator.gateway.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.orders.recovery.AccountOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.orchestrator.runtime.BoundedCalls;
import group.gnometrading.sm.Listing;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedOrderQueryTest {

    private static final long TIMEOUT_MILLIS = 200;

    /** A venue whose answers wait on a latch, so a test decides when it answers. */
    private static final class SlowVenue implements VenueOrderQuery {
        final CountDownLatch answer = new CountDownLatch(1);
        volatile int concurrent;
        volatile int mostConcurrent;

        @Override
        public List<VenueOrder> listOpenOrders(final Listing listing) throws IOException {
            enter();
            try {
                this.answer.await();
                return List.of();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            } finally {
                this.concurrent--;
            }
        }

        @Override
        public Optional<VenueOrder> getOrder(final Listing listing, final String exchangeOrderId, final long after) {
            enter();
            this.concurrent--;
            return Optional.of(new VenueOrder(exchangeOrderId, "v", 0, 0, 0, true));
        }

        @Override
        public boolean cancel(final Listing listing, final VenueOrder order) throws IOException {
            throw new IOException("venue refused");
        }

        @Override
        public List<AccountOrder> listAllOpenOrders() {
            return List.of();
        }

        @Override
        public boolean cancel(final AccountOrder order) throws IOException {
            throw new IOException("venue refused");
        }

        private synchronized void enter() {
            this.concurrent++;
            this.mostConcurrent = Math.max(this.mostConcurrent, this.concurrent);
        }
    }

    private final SlowVenue venue = new SlowVenue();
    private final BoundedOrderQuery query =
            new BoundedOrderQuery(this.venue, new BoundedCalls("test-venue"), TIMEOUT_MILLIS);

    @Test
    void aVenueThatDoesntAnswerInTimeIsAFailedQueryNotAWait() {
        final long started = System.nanoTime();
        final IOException failed = assertThrows(IOException.class, () -> this.query.listOpenOrders(null));
        final long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(waitedMillis < 2 * TIMEOUT_MILLIS, "waited " + waitedMillis + " ms");
        assertTrue(failed.getMessage().contains("no answer within"), failed.getMessage());
    }

    @Test
    void callsGoOneAtATimeSoTheVenuesClientIsNeverShared() throws Exception {
        this.venue.answer.countDown();
        for (int i = 0; i < 20; i++) {
            this.query.listOpenOrders(null);
            this.query.getOrder(null, "x-" + i, 0);
        }
        assertEquals(1, this.venue.mostConcurrent);
    }

    @Test
    void answersAndTheVenuesOwnFailuresPassThrough() throws Exception {
        assertEquals("x-1", this.query.getOrder(null, "x-1", 0).orElseThrow().exchangeOrderId());
        assertEquals(
                "venue refused",
                assertThrows(IOException.class, () -> this.query.cancel(null, null))
                        .getMessage());
    }
}
