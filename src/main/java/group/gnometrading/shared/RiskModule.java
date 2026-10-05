package group.gnometrading.shared;

import group.gnometrading.RegistryConnection;
import group.gnometrading.di.Module;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.resources.Properties;
import group.gnometrading.risk.RiskMaster;
import java.time.Duration;
import org.agrona.concurrent.SystemEpochClock;

public class RiskModule extends Module {

    @Override
    protected final Module[] includes() {
        return new Module[] {new SecurityMasterModule()};
    }

    @Provides
    @Singleton
    public final RiskMaster provideRiskMaster(final RegistryConnection connection, final Properties properties) {
        final String sessionId =
                properties.hasProperty("session.id") ? properties.getStringProperty("session.id") : null;
        return new RiskMaster(connection, properties.getIntProperty("strategy.id"), sessionId);
    }

    @Provides
    @Singleton
    public final RiskEngine provideRiskEngine(final Properties properties) {
        return RiskEngine.syncedFromRegistry(
                SystemEpochClock.INSTANCE, Duration.ofMillis(properties.getIntProperty("risk.stale.after.ms")));
    }
}
