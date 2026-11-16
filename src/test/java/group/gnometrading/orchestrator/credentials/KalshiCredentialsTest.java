package group.gnometrading.orchestrator.credentials;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.EdECPrivateKey;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class KalshiCredentialsTest {

    @Test
    void parsesAnRsaPkcs8Pem() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final PrivateKey key = generator.generateKeyPair().getPrivate();

        final KalshiCredentials credentials = KalshiCredentials.fromJson(json(pem(key)));

        assertEquals("key-id", credentials.apiKey());
        assertEquals("RSA", credentials.privateKey().getAlgorithm());
        assertArrayEquals(key.getEncoded(), credentials.privateKey().getEncoded());
    }

    @Test
    void parsesAnEd25519Pkcs8Pem() throws Exception {
        final PrivateKey key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();

        final KalshiCredentials credentials = KalshiCredentials.fromJson(json(pem(key)));

        assertEquals("EdDSA", credentials.privateKey().getAlgorithm());
        assertArrayEquals(seed(key), seed(credentials.privateKey()));
    }

    @Test
    void parsesABareEd25519Seed() throws Exception {
        final PrivateKey key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();

        final PrivateKey parsed = KalshiCredentials.parsePrivateKey(Base64.getEncoder().encodeToString(seed(key)));

        assertArrayEquals(seed(key), seed(parsed));
    }

    @Test
    void parsesAnEd25519SeedFollowedByItsPublicKey() throws Exception {
        final KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final byte[] seedAndPublic = Arrays.copyOf(seed(keys.getPrivate()), 64);

        final PrivateKey parsed = KalshiCredentials.parsePrivateKey(Base64.getEncoder().encodeToString(seedAndPublic));

        assertArrayEquals(seed(keys.getPrivate()), seed(parsed));
    }

    @Test
    void refusesAKeyOfNeitherKindWithoutEchoingIt() {
        final String notAKey = Base64.getEncoder().encodeToString(new byte[48]);

        final RuntimeException error = assertThrows(RuntimeException.class, () -> KalshiCredentials.parsePrivateKey(
                "-----BEGIN PRIVATE KEY-----\n" + notAKey + "\n-----END PRIVATE KEY-----\n"));

        assertFalse(error.getMessage().contains(notAKey));
        assertThrows(RuntimeException.class, () -> KalshiCredentials.parsePrivateKey(notAKey));
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
