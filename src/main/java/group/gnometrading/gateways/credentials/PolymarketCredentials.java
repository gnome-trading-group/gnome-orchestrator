package group.gnometrading.gateways.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public record PolymarketCredentials(
        String apiKey,
        String secret,
        String passphrase,
        byte[] ethereumPrivateKey,
        String signerAddress,
        String proxyWalletAddress)
        implements ExchangeCredentials {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String exchange() {
        return "polymarket";
    }

    @SuppressWarnings("unchecked")
    public static PolymarketCredentials fromJson(final String json) {
        try {
            final Map<String, String> fields = MAPPER.readValue(json, Map.class);
            final String apiKey = fields.get("apiKey");
            final String secret = fields.get("secret");
            final String passphrase = fields.get("passphrase");
            final String privateKeyHex = fields.get("ethereumPrivateKey");
            final String signerAddress = fields.get("signerAddress");
            final String proxyWalletAddress = fields.get("proxyWalletAddress");
            if (apiKey == null
                    || secret == null
                    || passphrase == null
                    || privateKeyHex == null
                    || signerAddress == null
                    || proxyWalletAddress == null) {
                throw new RuntimeException("Polymarket credentials missing required field");
            }
            return new PolymarketCredentials(
                    apiKey,
                    secret,
                    passphrase,
                    hexToBytes(privateKeyHex.startsWith("0x") ? privateKeyHex.substring(2) : privateKeyHex),
                    signerAddress,
                    proxyWalletAddress);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Polymarket credentials JSON", e);
        }
    }

    private static byte[] hexToBytes(final String hex) {
        final int len = hex.length();
        final byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }
}
