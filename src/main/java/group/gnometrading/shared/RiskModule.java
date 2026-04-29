package group.gnometrading.shared;

import group.gnometrading.RegistryConnection;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.di.Module;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.oms.risk.RiskEngine;
import group.gnometrading.oms.risk.RiskSyncAgent;
import group.gnometrading.risk.RiskMaster;
import java.time.Duration;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.SystemEpochClock;

public class RiskModule extends Module {

    private static final Duration DEFAULT_RISK_REFRESH_INTERVAL = Duration.ofSeconds(5);

    @Override
    protected final Module[] includes() {
        return new Module[] {new SecurityMasterModule()};
    }

    @Provides
    @Singleton
    public final RiskMaster provideRiskMaster(RegistryConnection connection) {
        return new RiskMaster(connection);
    }

    @Provides
    @Singleton
    public final RiskEngine provideRiskEngine() {
        return new RiskEngine();
    }

    @Provides
    @Singleton
    public final RiskSyncAgent provideRiskSyncAgent(final RiskMaster riskMaster, final RiskEngine riskEngine) {
        final EpochClock clock = SystemEpochClock.INSTANCE;
        return new RiskSyncAgent(riskMaster, riskEngine, clock, DEFAULT_RISK_REFRESH_INTERVAL);
    }

    @Provides
    @Singleton
    public final GnomeAgentRunner provideRiskSyncAgentRunner(
            final RiskSyncAgent riskSyncAgent, final ErrorHandler errorHandler) {
        final GnomeAgentRunner runner = new GnomeAgentRunner(riskSyncAgent, errorHandler);
        GnomeAgentRunner.startOnThread(runner);
        return runner;
    }
}
