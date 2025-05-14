package group.gnometrading.collectors;

import group.gnometrading.SecurityMaster;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.shared.SecurityMasterModule;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public class DelegatingCollectorOrchestrator extends Orchestrator implements SecurityMasterModule {

    static {
        instanceClass = DelegatingCollectorOrchestrator.class;
    }

    @Provides
    @Singleton
    @Named("LISTINGS")
    public List<Listing> provideListings(SecurityMaster securityMaster) {
        List<Listing> output = new ArrayList<>();
        String listingIds = System.getenv("LISTING_IDS");
        for (String listingId : listingIds.split(",")) {
            output.add(securityMaster.getListing(Integer.parseInt(listingId)));
        }
        return output;
    }

    private Class<? extends Orchestrator> findOrchestrator(
            final Listing listing,
            final SecurityMaster securityMaster
    ) {
        final Exchange exchange = securityMaster.getExchange(listing.exchangeId());
        switch (exchange.exchangeName()) {
            case "Hyperliquid" -> {
                return HyperliquidCollectorOrchestrator.class;
            }
            default -> throw new IllegalArgumentException("Unmapped exchange: " + exchange.exchangeName());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void configure() {
        final List<Listing> listings = this.getInstance(List.class, "LISTINGS");
        final SecurityMaster securityMaster = this.getInstance(SecurityMaster.class);

        for (Listing listing : listings) {
            final var orchestrator = findOrchestrator(listing, securityMaster);
            final Orchestrator instance;
            try {
                instance = orchestrator.getDeclaredConstructor().newInstance();
            } catch (InstantiationException | InvocationTargetException | IllegalAccessException |
                     NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
            instance.configure();
        }
    }
}
