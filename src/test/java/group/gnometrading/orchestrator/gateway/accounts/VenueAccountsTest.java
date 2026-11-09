package group.gnometrading.orchestrator.gateway.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.account.VenueAccount;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.gateway.core.CrossKeys;
import group.gnometrading.orchestrator.runtime.ProcessExit;
import group.gnometrading.resources.Properties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPrivateKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.agrona.concurrent.SystemEpochClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.api.Test;

/** Each venue's account built from a secret as Secrets Manager holds it; nothing connects until it starts. */
class VenueAccountsTest {

    // The address of private key 1.
    private static final String SIGNER = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf";
    private static final String FUNDER = "0xe65019f319d3b20a9883b43283c91ca9d8c81cfb";

    private final Map<String, String> secrets = Map.of(
            "kalshi", kalshiSecret(),
            "pmus", polymarketUsSecret(),
            "pmintl", polymarketIntlSecret());

    @Test
    void everyVenuesAccountIsBuiltWithItsQueuesAndMarkets() throws Exception {
        assertBuilt(GatewayVenue.KALSHI, new VenueAccount(1, 1, "steady-heron-01", "kalshi", null));
        assertBuilt(GatewayVenue.POLYMARKET_US, new VenueAccount(2, 3, "brisk-badger-01", "pmus", null));
        assertBuilt(GatewayVenue.POLYMARKET_INTL, new VenueAccount(3, 2, "sleepy-otter-42", "pmintl", FUNDER));
    }

    @Test
    void aPolymarketIntlAccountWhoseSecretSignsForAnotherWalletIsRefused() {
        final IllegalStateException refused = assertThrows(IllegalStateException.class, () -> accounts()
                .build(
                        GatewayVenue.POLYMARKET_INTL,
                        new VenueAccount(3, 2, "sleepy-otter-42", "pmintl", "0x" + "1".repeat(40))));
        assertTrue(refused.getMessage().contains("credentials sign for"), refused.getMessage());
    }

    private void assertBuilt(final GatewayVenue venue, final VenueAccount account) throws Exception {
        final GatewayAccount built = accounts().build(venue, account);
        try {
            assertEquals("account-" + account.accountId(), built.name());
            assertNotNull(built.markets());
            assertTrue(built.commands().remaining() > 0);
            assertEquals(0, built.lanePasses(), "nothing runs before it starts");
            assertEquals(0, built.lane().workingOrders());
        } finally {
            built.close();
        }
    }

    private VenueAccounts accounts() {
        final Properties properties;
        try {
            properties = new Properties("orchestrator.prod.properties");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new VenueAccounts(new VenueAccounts.Context(
                new NullLogger(),
                properties,
                this.secrets::get,
                new GatewaySettings("live:kalshi:main", 64, 2),
                SystemEpochClock.INSTANCE,
                new SystemEpochNanoClock(),
                ProcessExit.SYSTEM,
                new CrossKeys()));
    }

    private static String kalshiSecret() {
        try {
            final byte[] key = KeyPairGenerator.getInstance("RSA")
                    .generateKeyPair()
                    .getPrivate()
                    .getEncoded();
            return "{\"apiKey\":\"key-id\",\"privateKey\":\"-----BEGIN PRIVATE KEY-----\\n"
                    + Base64.getEncoder().encodeToString(key) + "\\n-----END PRIVATE KEY-----\"}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String polymarketUsSecret() {
        try {
            final byte[] seed = ((EdECPrivateKey) KeyPairGenerator.getInstance("Ed25519")
                            .generateKeyPair()
                            .getPrivate())
                    .getBytes()
                    .orElseThrow();
            return "{\"apiKey\":\"key-id\",\"secret\":\"" + Base64.getEncoder().encodeToString(seed) + "\"}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String polymarketIntlSecret() {
        final byte[] key = new byte[32];
        key[31] = 1;
        return "{\"apiKey\":\"key\",\"secret\":\"c2VjcmV0\",\"passphrase\":\"pass\",\"ethereumPrivateKey\":\"0x"
                + HexFormat.of().formatHex(key) + "\",\"signerAddress\":\"" + SIGNER + "\",\"funderAddress\":\""
                + FUNDER + "\",\"signatureType\":\"3\"}";
    }
}
