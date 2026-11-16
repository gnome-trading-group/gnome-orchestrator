package group.gnometrading.orchestrator.credentials;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.EdECPrivateKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class KalshiCredentialsTest {

    @Test
    void parsesAnEd25519Pkcs8Pem() throws Exception {
        final PrivateKey key =
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();

        final KalshiCredentials credentials = KalshiCredentials.fromJson(json(pem(key)));

        assertEquals("key-id", credentials.apiKey());
        assertEquals("kalshi", credentials.exchange());
        assertEquals("EdDSA", credentials.privateKey().getAlgorithm());
        assertArrayEquals(seed(key), seed(credentials.privateKey()));
    }

    @Test
    void refusesAnRsaKey() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final String rsaPem = pem(generator.generateKeyPair().getPrivate());

        final RuntimeException error =
                assertThrows(RuntimeException.class, () -> KalshiCredentials.fromJson(json(rsaPem)));

        assertFalse(error.getMessage().contains(rsaPem.substring(40, 80)), "the key is never echoed");
    }

    @Test
    void refusesSomethingThatIsNotAPem() {
        assertThrows(RuntimeException.class, () -> KalshiCredentials.parsePrivateKey("not base64 at all!"));
    }

    @Test
    void missingFieldsAreAnError() {
        assertThrows(RuntimeException.class, () -> KalshiCredentials.fromJson("{\"apiKey\":\"key-id\"}"));
        assertThrows(RuntimeException.class, () -> KalshiCredentials.fromJson("{\"privateKey\":\"AAAA\"}"));
    }

    private static String json(final String pem) {
        return "{\"apiKey\":\"key-id\",\"privateKey\":\"" + pem.replace("\n", "\\n") + "\"}";
    }

    private static String pem(final PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static byte[] seed(final PrivateKey key) {
        return ((EdECPrivateKey) key).getBytes().orElseThrow();
    }
}
