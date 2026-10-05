package group.gnometrading.shared;

/**
 * The registry API host and the resolved API key value, shared so the key is fetched from API Gateway once.
 *
 * @param host registry API host, without scheme
 * @param apiKey API key value; empty when no key is configured
 */
public record RegistryEndpoint(String host, String apiKey) {}
