package group.gnometrading.shared;

import group.gnometrading.RegistryConnection;
import group.gnometrading.di.Module;
import group.gnometrading.oms.risk.ListingDirectory;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.resources.Properties;
import group.gnometrading.risk.RiskMaster;
import group.gnometrading.sm.Listing;
import java.time.Duration;
import java.util.List;
import org.agrona.concurrent.SystemEpochClock;

/**
 * The risk engine and the policies it is fed are built for a session's listings, which are only known once the
 * session has resolved them, so they are made here on request rather than injected.
 */
public class RiskModule extends Module {

    @Override
    protected final Module[] includes() {
        return new Module[] {new SecurityMasterModule()};
    }

    /**
     * Fetches only the policies that can apply to this session: rows naming no strategy are narrowed to its listings
     * and exchanges, so the firm's listing and exchange rows don't all count against every session's policy cap.
     */
    public static RiskMaster riskMaster(
            final RegistryConnection connection, final Properties properties, final ListingDirectory directory) {
        final String sessionId =
                properties.hasProperty("session.id") ? properties.getStringProperty("session.id") : null;
        final int[] listingIds = new int[directory.listingCount()];
        for (int i = 0; i < listingIds.length; i++) {
            listingIds[i] = directory.listingAt(i);
        }
        return new RiskMaster(
                connection, properties.getIntProperty("strategy.id"), sessionId, listingIds, directory.exchangeIds());
    }

    public static RiskEngine riskEngine(final Properties properties, final ListingDirectory directory) {
        return RiskEngine.syncedFromRegistry(
                SystemEpochClock.INSTANCE,
                Duration.ofMillis(properties.getIntProperty("risk.stale.after.ms")),
                directory);
    }

    public static ListingDirectory directory(final List<Listing> listings) {
        return new ListingDirectory(listings);
    }
}
