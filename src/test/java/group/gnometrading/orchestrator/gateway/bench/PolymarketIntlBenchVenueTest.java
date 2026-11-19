package group.gnometrading.orchestrator.gateway.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.gateways.venues.polymarket.intl.PolymarketIntlOrderSigner;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** The bench venue names orders as Polymarket does, by the hash the gateway's signer signed. */
class PolymarketIntlBenchVenueTest {

    // The address of private key 1.
    private static final String SIGNER = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf";
    private static final String FUNDER = "0xe65019f319d3b20a9883b43283c91ca9d8c81cfb";
    private static final String TOKEN = "71321045679252212594626385532706912750332728571942532289631379312455583992563";

    @Test
    void theVenuesOrderIdIsTheHashTheGatewaySigned() throws Exception {
        final byte[] key = new byte[32];
        key[31] = 1;
        for (final int signatureType : new int[] {
            PolymarketIntlOrderSigner.SIGNATURE_TYPE_EOA, PolymarketIntlOrderSigner.SIGNATURE_TYPE_POLY_1271
        }) {
            try (PolymarketIntlOrderSigner signer = new PolymarketIntlOrderSigner(key, SIGNER, FUNDER, signatureType)) {
                final PolymarketIntlOrderSigner.SignedOrder signed = signer.signOrder(
                        PolymarketIntlOrderSigner.tokenIdBytes(TOKEN),
                        2_750_000,
                        5_000_000,
                        0,
                        1_700_000_000_000L,
                        false);
                final byte[] hash = new byte[66];
                signed.writeOrderHashHex(hash, 0);
                final String zero = "0x" + HexFormat.of().formatHex(new byte[32]);
                final String body = "{\"salt\":" + signed.salt() + ",\"maker\":\"" + signed.maker() + "\",\"signer\":\""
                        + signed.signer() + "\",\"tokenId\":\"" + TOKEN + "\",\"makerAmount\":\"2750000\","
                        + "\"takerAmount\":\"5000000\",\"side\":\"BUY\",\"expiration\":\"0\",\"signatureType\":"
                        + signatureType + ",\"timestamp\":\"1700000000000\",\"metadata\":\"" + zero
                        + "\",\"builder\":\""
                        + zero + "\"}";
                assertEquals(
                        new String(hash, StandardCharsets.US_ASCII),
                        PolymarketIntlBenchVenue.orderHash(new ObjectMapper().readTree(body)),
                        "signature type " + signatureType);
            }
        }
    }
}
