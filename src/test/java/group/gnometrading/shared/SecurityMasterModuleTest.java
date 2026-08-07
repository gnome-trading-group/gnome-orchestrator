package group.gnometrading.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import group.gnometrading.resources.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SecurityMasterModuleTest {

    @Test
    void usesInjectedApiKeyWhenNoApiGatewayKeyIdIsConfigured() {
        final Properties properties = mock(Properties.class);
        when(properties.getStringProperty("registry.api.key.id")).thenThrow(new IllegalArgumentException("missing"));
        when(properties.getStringProperty("registry.api.key")).thenReturn("injected-key");

        assertEquals("injected-key", SecurityMasterModule.resolveRegistryApiKey(properties, ignored -> {
            throw new AssertionError("API Gateway lookup must not run");
        }));
    }

    @Test
    void prefersApiGatewayKeyIdWhenBothSourcesAreConfigured() {
        final Properties properties = mock(Properties.class);
        final AtomicReference<String> requestedKeyId = new AtomicReference<>();
        when(properties.getStringProperty("registry.api.key.id")).thenReturn("  production-key-id  ");
        when(properties.getStringProperty("registry.api.key")).thenReturn("injected-key");

        final String apiKey = SecurityMasterModule.resolveRegistryApiKey(properties, keyId -> {
            requestedKeyId.set(keyId);
            return "looked-up-key";
        });

        assertEquals("looked-up-key", apiKey);
        assertEquals("production-key-id", requestedKeyId.get());
    }

    @Test
    void permitsAnUnauthenticatedRegistryWhenNeitherSourceIsConfigured() {
        final Properties properties = mock(Properties.class);
        when(properties.getStringProperty("registry.api.key.id")).thenThrow(new IllegalArgumentException("missing"));
        when(properties.getStringProperty("registry.api.key")).thenThrow(new IllegalArgumentException("missing"));

        assertEquals("", SecurityMasterModule.resolveRegistryApiKey(properties, ignored -> "unused"));
    }
}
