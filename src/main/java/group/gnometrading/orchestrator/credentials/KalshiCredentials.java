package group.gnometrading.orchestrator.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public record KalshiCredentials(String apiKey, PrivateKey privateKey) implements ExchangeCredentials {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> KEY_ALGORITHMS = List.of("Ed25519", "RSA");
    private static final int ED25519_SEED_LENGTH = 32;

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
     * Kalshi keys are Ed25519 or RSA, both handed out as PKCS#8 PEM whose header doesn't say which, so each is tried. A
     * bare base64 Ed25519 seed (32 bytes, or 64 with the public key after it) is taken too.
     */
    static PrivateKey parsePrivateKey(String text) {
        if (!text.contains("-----BEGIN")) {
            return parseEd25519Seed(text);
        }
        final byte[] pkcs8 = Base64.getDecoder()
                .decode(text.replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s+", ""));
        for (final String algorithm : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            } catch (InvalidKeySpecException e) {
                // Not this algorithm's key: try the next.
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(algorithm + " keys unsupported by this JDK", e);
            }
        }
        throw new RuntimeException("Kalshi private key is neither an Ed25519 nor an RSA PKCS#8 key");
    }

    private static PrivateKey parseEd25519Seed(String base64) {
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64.replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Kalshi private key is neither PEM nor base64", e);
        }
        if (decoded.length != ED25519_SEED_LENGTH && decoded.length != 2 * ED25519_SEED_LENGTH) {
            throw new RuntimeException("Kalshi base64 private key must be a 32-byte Ed25519 seed (or 64 with its "
                    + "public key), got " + decoded.length + " bytes");
        }
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new EdECPrivateKeySpec(
                            NamedParameterSpec.ED25519, Arrays.copyOf(decoded, ED25519_SEED_LENGTH)));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to parse Kalshi Ed25519 seed", e);
        }
    }
}
