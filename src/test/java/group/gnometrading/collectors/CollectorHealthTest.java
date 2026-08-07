package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CollectorHealthTest {

    private MutableClock clock;
    private AtomicLong lastNormalized;
    private AtomicLong lastRaw;
    private AtomicBoolean rawHealthy;
    private CollectorHealth health;

    @BeforeEach
    void setUp() {
        this.clock = new MutableClock(Instant.parse("2026-08-03T12:00:00Z"));
        this.lastNormalized = new AtomicLong();
        this.lastRaw = new AtomicLong();
        this.rawHealthy = new AtomicBoolean(true);
        this.health = new CollectorHealth(
                this.clock,
                this.lastNormalized::get,
                this.lastRaw::get,
                this.rawHealthy::get,
                Duration.ofSeconds(60),
                Duration.ofSeconds(30));
    }

    @Test
    void allowsConnectionStartupGrace() {
        assertTrue(this.health.getAsBoolean());
        this.clock.advance(Duration.ofSeconds(59));
        assertTrue(this.health.getAsBoolean());
    }

    @Test
    void requiresAnInitialNormalizedEventAfterStartup() {
        this.lastRaw.set(nowNanos());
        this.clock.advance(Duration.ofSeconds(61));
        this.lastRaw.set(nowNanos());
        assertFalse(this.health.getAsBoolean());

        this.lastNormalized.set(nowNanos());
        assertTrue(this.health.getAsBoolean());
    }

    @Test
    void failsWhenTheVenueConnectionGoesStale() {
        this.lastNormalized.set(nowNanos());
        this.lastRaw.set(nowNanos());
        this.clock.advance(Duration.ofSeconds(61));
        assertFalse(this.health.getAsBoolean());
    }

    @Test
    void failsImmediatelyWhenAnUploadFails() {
        this.rawHealthy.set(false);
        assertFalse(this.health.getAsBoolean());
    }

    @Test
    void requiresEveryConfiguredListingToRemainCurrent() {
        final AtomicLong secondNormalized = new AtomicLong(nowNanos());
        final AtomicLong secondRaw = new AtomicLong(nowNanos());
        final AtomicBoolean secondHealthy = new AtomicBoolean(true);
        this.lastNormalized.set(nowNanos());
        this.lastRaw.set(nowNanos());
        final CollectorHealth multiListingHealth = new CollectorHealth(
                this.clock,
                new LongSupplier[] {this.lastNormalized::get, secondNormalized::get},
                new LongSupplier[] {this.lastRaw::get, secondRaw::get},
                new BooleanSupplier[] {this.rawHealthy::get, secondHealthy::get},
                Duration.ofSeconds(60),
                Duration.ofSeconds(30));

        this.clock.advance(Duration.ofSeconds(61));
        this.lastNormalized.set(nowNanos());
        this.lastRaw.set(nowNanos());
        assertFalse(multiListingHealth.getAsBoolean());

        secondNormalized.set(nowNanos());
        secondRaw.set(nowNanos());
        assertTrue(multiListingHealth.getAsBoolean());

        secondHealthy.set(false);
        assertFalse(multiListingHealth.getAsBoolean());
    }

    private long nowNanos() {
        return TimeUnit.MILLISECONDS.toNanos(this.clock.millis());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.instant;
        }
    }
}
