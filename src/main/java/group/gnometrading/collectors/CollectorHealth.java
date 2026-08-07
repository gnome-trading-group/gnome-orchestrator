package group.gnometrading.collectors;

import group.gnometrading.collector.MarketDataCollector;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

final class CollectorHealth implements BooleanSupplier {

    private static final long CLOCK_TOLERANCE_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final Clock clock;
    private final LongSupplier[] lastNormalizedEventNanos;
    private final LongSupplier[] lastRawMessageNanos;
    private final BooleanSupplier[] rawCollectorHealthy;
    private final long startedAtNanos;
    private final long startupGraceNanos;
    private final long maxRawStalenessNanos;

    CollectorHealth(
            Clock clock,
            MarketDataCollector marketDataCollector,
            RawMarketDataCollector rawMarketDataCollector,
            Duration startupGrace,
            Duration maxRawStaleness) {
        this(
                clock,
                new LongSupplier[] {() -> marketDataCollector.lastEventNanos},
                new LongSupplier[] {rawMarketDataCollector::lastMessageNanos},
                new BooleanSupplier[] {rawMarketDataCollector::isHealthy},
                startupGrace,
                maxRawStaleness);
    }

    CollectorHealth(
            Clock clock,
            LongSupplier lastNormalizedEventNanos,
            LongSupplier lastRawMessageNanos,
            BooleanSupplier rawCollectorHealthy,
            Duration startupGrace,
            Duration maxRawStaleness) {
        this(
                clock,
                new LongSupplier[] {lastNormalizedEventNanos},
                new LongSupplier[] {lastRawMessageNanos},
                new BooleanSupplier[] {rawCollectorHealthy},
                startupGrace,
                maxRawStaleness);
    }

    CollectorHealth(
            Clock clock,
            LongSupplier[] lastNormalizedEventNanos,
            LongSupplier[] lastRawMessageNanos,
            BooleanSupplier[] rawCollectorHealthy,
            Duration startupGrace,
            Duration maxRawStaleness) {
        if (lastNormalizedEventNanos.length == 0
                || lastNormalizedEventNanos.length != lastRawMessageNanos.length
                || lastNormalizedEventNanos.length != rawCollectorHealthy.length) {
            throw new IllegalArgumentException("Collector health sources must be non-empty and have equal lengths");
        }
        this.clock = clock;
        this.lastNormalizedEventNanos = lastNormalizedEventNanos.clone();
        this.lastRawMessageNanos = lastRawMessageNanos.clone();
        this.rawCollectorHealthy = rawCollectorHealthy.clone();
        this.startedAtNanos = nowNanos();
        this.startupGraceNanos = startupGrace.toNanos();
        this.maxRawStalenessNanos = maxRawStaleness.toNanos();
    }

    @Override
    public boolean getAsBoolean() {
        for (BooleanSupplier source : this.rawCollectorHealthy) {
            if (!source.getAsBoolean()) {
                return false;
            }
        }

        final long now = nowNanos();
        if (now - this.startedAtNanos < this.startupGraceNanos) {
            return true;
        }

        for (int i = 0; i < this.lastNormalizedEventNanos.length; i++) {
            final long lastNormalizedEvent = this.lastNormalizedEventNanos[i].getAsLong();
            final long lastRawMessage = this.lastRawMessageNanos[i].getAsLong();
            final long rawAge = now - lastRawMessage;
            if (lastNormalizedEvent == 0L
                    || lastRawMessage == 0L
                    || rawAge < -CLOCK_TOLERANCE_NANOS
                    || rawAge > this.maxRawStalenessNanos) {
                return false;
            }
        }
        return true;
    }

    private long nowNanos() {
        return TimeUnit.MILLISECONDS.toNanos(this.clock.millis());
    }
}
