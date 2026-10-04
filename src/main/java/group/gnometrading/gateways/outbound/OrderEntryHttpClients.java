package group.gnometrading.gateways.outbound;

import group.gnometrading.networking.http.HTTPClient;

/** HTTP clients for exchanges' REST order entry. */
final class OrderEntryHttpClients {

    // Order lookups list up to a few hundred orders, which runs to hundreds of KB.
    private static final int READ_BUFFER_BYTES = 1 << 20;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 1_000;
    // A slower reply is treated as an unknown outcome and reconciled by looking the order up, which beats
    // holding the outbound thread for the client's 30 second default.
    private static final int RESPONSE_TIMEOUT_MS = 5_000;

    private OrderEntryHttpClients() {}

    static HTTPClient create() {
        return HTTPClient.builder()
                .withReadBufferSize(READ_BUFFER_BYTES)
                .withConnectTimeout(CONNECT_TIMEOUT_MS)
                .withReadTimeout(READ_TIMEOUT_MS)
                .withResponseTimeout(RESPONSE_TIMEOUT_MS)
                .build();
    }
}
