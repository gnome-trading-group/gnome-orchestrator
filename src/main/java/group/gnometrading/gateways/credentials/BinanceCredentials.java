package group.gnometrading.gateways.credentials;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record BinanceCredentials(String apiKey, PrivateKey privateKey) implements ExchangeCredentials {

    private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Override
    public String exchange() {
        return "binance";
    }

    public static BinanceCredentials fromJson(String json) {
        String apiKey = null;
        String privateKeyPem = null;
        Matcher matcher = JSON_STRING_FIELD.matcher(json);
        while (matcher.find()) {
            switch (matcher.group(1)) {
                case "apiKey" -> apiKey = matcher.group(2);
                case "privateKey" -> privateKeyPem =
                        matcher.group(2).replace("\\n", "\n").replace("\\\\", "\\");
            }
        }
        if (apiKey == null || privateKeyPem == null) {
            throw new RuntimeException("Binance credentials secret missing apiKey or privateKey");
        }
        return new BinanceCredentials(apiKey, parseEd25519Key(privateKeyPem));
    }

    private static PrivateKey parseEd25519Key(String pem) {
        try {
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Ed25519 private key", e);
        }
    }
}
