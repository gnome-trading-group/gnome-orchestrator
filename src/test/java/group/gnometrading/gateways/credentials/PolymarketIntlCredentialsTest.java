package group.gnometrading.gateways.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PolymarketIntlCredentialsTest {

    private static final String SAFE_WALLET = "{\"apiKey\":\"key\",\"secret\":\"c2VjcmV0\",\"passphrase\":\"pass\","
            + "\"ethereumPrivateKey\":\"0x0102\",\"signerAddress\":\"0xEoa\",\"funderAddress\":\"0xSafe\","
            + "\"signatureType\":\"2\"}";

    @Test
    void parsesAPolymarketComSafeWallet() {
        final PolymarketIntlCredentials credentials = PolymarketIntlCredentials.fromJson(SAFE_WALLET);

        assertEquals("0xEoa", credentials.signerAddress());
        assertEquals("0xSafe", credentials.funderAddress());
        assertEquals(2, credentials.signatureType());
        assertEquals(2, credentials.ethereumPrivateKey().length);
    }

    @Test
    void missingFunderOrSignatureTypeIsAnError() {
        assertThrows(
                RuntimeException.class,
                () -> PolymarketIntlCredentials.fromJson(SAFE_WALLET.replace(",\"signatureType\":\"2\"", "")));
        assertThrows(
                RuntimeException.class,
                () -> PolymarketIntlCredentials.fromJson(SAFE_WALLET.replace("\"funderAddress\"", "\"proxyWallet\"")));
    }
}
