package group.gnometrading.gateways.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyFactory;
import java.security.PrivateKey;
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
            return new KalshiCredentials(apiKey, parseRsaKey(privateKeyPem));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Kalshi credentials JSON", e);
        }
    }

    private static PrivateKey parseRsaKey(String pem) {
        try {
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse RSA private key", e);
        }
    }
}
