package group.gnometrading.gateways.credentials;

import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.gateways.outbound.exchanges.polymarket.us.PolymarketUsAuthSigner;
import java.security.PrivateKey;
import java.util.Map;

public record PolymarketUsCredentials(String apiKey, PrivateKey privateKey) implements ExchangeCredentials {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String exchange() {
        return "polymarket-us";
    }

    @SuppressWarnings("unchecked")
    public static PolymarketUsCredentials fromJson(String json) {
        try {
            Map<String, String> fields = MAPPER.readValue(json, Map.class);
            String apiKey = fields.get("apiKey");
            String secret = fields.get("secret");
            if (apiKey == null || secret == null) {
                throw new RuntimeException("Polymarket US credentials secret missing apiKey or secret");
            }
            return new PolymarketUsCredentials(apiKey, PolymarketUsAuthSigner.parseSecretKey(secret));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Polymarket US credentials JSON", e);
        }
    }
}
