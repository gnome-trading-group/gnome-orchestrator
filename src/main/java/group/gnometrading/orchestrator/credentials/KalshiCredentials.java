package group.gnometrading.orchestrator.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

public record KalshiCredentials(String apiKey, PrivateKey privateKey) implements ExchangeCredentials {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String exchange() {
        return "kalshi";
    }

    @SuppressWarnings("unchecked")
    public static KalshiCredentials fromJson(String json) {
        try {
            Map<String, String> fields = MAPPER.readValue(json, Map.class);
            String apiKey = fields.get("apiKey");
            String privateKeyPem = fields.get("privateKey");
            if (apiKey == null || privateKeyPem == null) {
                throw new RuntimeException("Kalshi credentials secret missing apiKey or privateKey");
            }
            return new KalshiCredentials(apiKey, parsePrivateKey(privateKeyPem));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Kalshi credentials JSON", e);
        }
    }

    /**
     * Kalshi's keys are PKCS#8 PEM, Ed25519 or RSA by the header alike. Only Ed25519 is taken: RSA-2048 signs every
     * request about fifty times slower, on the order path.
     */
    static PrivateKey parsePrivateKey(String pem) {
        final byte[] pkcs8;
        try {
            pkcs8 = Base64.getDecoder()
                    .decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
                            .replace("-----END PRIVATE KEY-----", "")
                            .replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Kalshi private key is not a PKCS#8 PEM", e);
        }
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (InvalidKeySpecException e) {
            throw new RuntimeException("Kalshi private key must be Ed25519 (PKCS#8 PEM); RSA keys are not accepted");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 keys unsupported by this JDK", e);
        }
    }
}
