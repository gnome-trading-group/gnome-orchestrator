package group.gnometrading.gateways.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPrivateKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PolymarketUsCredentialsTest {

    @Test
    void parsesApiKeyAndEd25519Secret() throws Exception {
        final byte[] seed = ((EdECPrivateKey) KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair()
                        .getPrivate())
                .getBytes()
                .orElseThrow();
        final String json =
                "{\"apiKey\":\"key-id\",\"secret\":\"" + Base64.getEncoder().encodeToString(seed) + "\"}";

        final PolymarketUsCredentials credentials = PolymarketUsCredentials.fromJson(json);

        assertEquals("key-id", credentials.apiKey());
        assertEquals("EdDSA", credentials.privateKey().getAlgorithm());
        assertEquals("polymarket-us", credentials.exchange());
    }

    @Test
    void missingFieldsAreAnError() {
        assertThrows(RuntimeException.class, () -> PolymarketUsCredentials.fromJson("{\"apiKey\":\"key-id\"}"));
        assertThrows(RuntimeException.class, () -> PolymarketUsCredentials.fromJson("{\"secret\":\"AAAA\"}"));
    }
}
