package group.gnometrading.orchestrator.gateway.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.sm.PriceGrid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GriddedMarketsTest {

    private static final long DOLLAR = 1_000_000_000L;
    private static final PriceGrid CENTS = PriceGrid.single(DOLLAR / 100, DOLLAR);
    private static final PriceGrid MILLIS = PriceGrid.single(DOLLAR / 1000, DOLLAR);

    private final Logger logger = mock(Logger.class);
    private final GriddedMarkets markets = new GriddedMarkets(logger, "account");

    @Test
    void aMarketIsMovedOntoItsListingsGridOnceAndAgainOnlyWhenItChanges() {
        final List<PriceGrid> seen = new ArrayList<>();
        markets.add(7, seen::add);

        markets.apply(id -> CENTS);
        markets.apply(id -> CENTS);
        markets.apply(id -> MILLIS);

        assertEquals(List.of(CENTS, MILLIS), seen);
    }

    @Test
    void aListingWithNoSpecKeepsItsGrid() {
        final List<PriceGrid> seen = new ArrayList<>();
        markets.add(7, seen::add);

        markets.apply(id -> null);

        assertEquals(List.of(), seen);
    }

    @Test
    void aMarketThatRefusesAGridKeepsItsOwnWithoutStoppingTheOthers() {
        final List<PriceGrid> seen = new ArrayList<>();
        markets.add(7, grid -> {
            throw new IllegalArgumentException("must end at a dollar");
        });
        markets.add(8, seen::add);
        final Map<Integer, PriceGrid> grids = Map.of(7, PriceGrid.single(1, PriceGrid.OPEN), 8, CENTS);

        markets.apply(grids::get);

        assertEquals(List.of(CENTS), seen);
        verify(logger).logf(eq(LogMessage.UNKNOWN_ERROR), anyString(), any(), any(), any(), any());
    }
}
