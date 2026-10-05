package group.gnometrading.shared;

import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.di.Module;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.resources.Properties;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;

public class SecurityMasterModule extends Module {

    @Override
    protected final Module[] includes() {
        return new Module[] {new PropertiesModule()};
    }

    @Provides
    @Singleton
    public final RegistryEndpoint provideRegistryEndpoint(Properties properties) {
        String keyId = properties.getStringProperty("registry.api.key.id");
        String apiKey;
        if (!keyId.isEmpty()) {
            try (ApiGatewayClient client =
                    ApiGatewayClient.builder().region(Region.US_EAST_1).build()) {
                apiKey = client.getApiKey(r -> r.apiKey(keyId).includeValue(true))
                        .value();
            }
        } else {
            apiKey = "";
        }
        return new RegistryEndpoint(properties.getStringProperty("registry.url"), apiKey);
    }

    // Not @Singleton: RetryableHTTPClient isn't thread-safe, so each consumer thread needs its own connection.
    @Provides
    public final RegistryConnection provideRegistryConnection(RegistryEndpoint endpoint) {
        return new RegistryConnection(endpoint.host(), endpoint.apiKey());
    }

    @Provides
    @Singleton
    public final SecurityMaster provideSecurityMaster(RegistryConnection connection) {
        return new SecurityMaster(connection);
    }
}
