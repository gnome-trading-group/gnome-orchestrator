package group.gnometrading.shared;

import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.resources.Properties;

public interface SecurityMasterModule extends PropertiesModule {

    @Provides
    @Singleton
    default SecurityMaster provideSecurityMaster(Properties properties) {
        final String url = properties.getStringProperty("registry.url");
        final String apiKey = properties.getStringProperty("registry.api_key");
        return new SecurityMaster(new RegistryConnection(url, apiKey));
    }

}
