package group.gnometrading.shared;

import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;

public interface SecurityMasterModule {

    @Provides
    @Singleton
    default SecurityMaster provideSecurityMaster() {
        final String url = System.getenv("REGISTRY_URL");
        final String apiKey = System.getenv("REGISTRY_API_KEY");
        return new SecurityMaster(new RegistryConnection(url, apiKey));
    }

}
