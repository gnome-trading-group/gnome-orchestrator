package group.gnometrading.shared;

import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.di.Module;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.resources.Properties;
import java.util.function.Function;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;

public class SecurityMasterModule extends Module {

    @Override
    protected final Module[] includes() {
        return new Module[] {new PropertiesModule()};
    }

    @Provides
    public final RegistryConnection provideRegistryConnection(Properties properties) {
        final String apiKey = resolveRegistryApiKey(properties, SecurityMasterModule::getApiKeyById);
        return new RegistryConnection(properties.getStringProperty("registry.url"), apiKey);
    }

    static String resolveRegistryApiKey(Properties properties, Function<String, String> keyLookup) {
        final String keyId = optionalProperty(properties, "registry.api.key.id").trim();
        if (!keyId.isEmpty()) {
            return keyLookup.apply(keyId);
        }
        return optionalProperty(properties, "registry.api.key");
    }

    private static String optionalProperty(Properties properties, String name) {
        try {
            final String value = properties.getStringProperty(name);
            return value == null ? "" : value;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static String getApiKeyById(String keyId) {
        try (ApiGatewayClient client = ApiGatewayClient.create()) {
            return client.getApiKey(r -> r.apiKey(keyId).includeValue(true)).value();
        }
    }

    @Provides
    @Singleton
    public final SecurityMaster provideSecurityMaster(RegistryConnection connection) {
        return new SecurityMaster(connection);
    }
}
