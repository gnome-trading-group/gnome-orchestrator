package group.gnometrading.shared;

import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.di.Module;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.resources.Properties;

public class SecurityMasterModule extends Module {

    @Override
    protected final Module[] includes() {
        return new Module[] {new PropertiesModule()};
    }

    @Provides
    @Singleton
    public final RegistryConnection provideRegistryConnection(Properties properties) {
        return new RegistryConnection(
                properties.getStringProperty("registry.url"), properties.getStringProperty("registry.api_key"));
    }

    @Provides
    @Singleton
    public final SecurityMaster provideSecurityMaster(RegistryConnection connection) {
        return new SecurityMaster(connection);
    }
}
