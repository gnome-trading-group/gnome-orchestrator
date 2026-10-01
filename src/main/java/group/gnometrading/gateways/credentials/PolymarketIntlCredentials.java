package group.gnometrading.gateways.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Secret {@code gnome/exchange-credentials/polymarket-intl}.
 *
 * @param signerAddress the EOA that holds {@code ethereumPrivateKey}; the API key belongs to it
 * @param funderAddress the wallet the orders trade from: the EOA itself, or the polymarket.com proxy or Safe
 * @param signatureType how orders are signed: 0 EOA, 1 Polymarket proxy, 2 Gnosis Safe (polymarket.com)
 */
public record PolymarketIntlCredentials(
        String apiKey,
        String secret,
        String passphrase,
        byte[] ethereumPrivateKey,
        String signerAddress,
        String funderAddress,
        int signatureType)
        implements ExchangeCredentials {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String exchange() {
        return "polymarket-intl";
    }

    @SuppressWarnings("unchecked")
    public static PolymarketIntlCredentials fromJson(final String json) {
        try {
            final Map<String, String> fields = MAPPER.readValue(json, Map.class);
            final String privateKeyHex = required(fields, "ethereumPrivateKey");
            return new PolymarketIntlCredentials(
                    required(fields, "apiKey"),
                    required(fields, "secret"),
                    required(fields, "passphrase"),
                    hexToBytes(privateKeyHex.startsWith("0x") ? privateKeyHex.substring(2) : privateKeyHex),
                    required(fields, "signerAddress"),
                    required(fields, "funderAddress"),
                    Integer.parseInt(required(fields, "signatureType")));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Polymarket International credentials JSON", e);
        }
    }

    private static String required(final Map<String, String> fields, final String name) {
        final String value = fields.get(name);
        if (value == null) {
            throw new RuntimeException("Polymarket International credentials missing " + name);
        }
        return value;
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
