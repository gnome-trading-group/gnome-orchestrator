package group.gnometrading.gateways.outbound;

import group.gnometrading.gateways.ratelimit.VenueRateLimiter;
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
    // A connection dropped silently along the way (a NAT, a proxy) is noticed after 10 s idle plus three unanswered
    // probes 5 s apart, rather than the kernel's two hours, so the writer's keep-warm replaces it before the next
    // order is written into it. The probes may also keep a NAT's mapping alive in the first place.
    private static final int KEEP_ALIVE_IDLE_SECONDS = 10;
    private static final int KEEP_ALIVE_INTERVAL_SECONDS = 5;
    private static final int KEEP_ALIVE_PROBES = 3;

    private OrderEntryHttpClients() {}

    /** Every request waits its turn on {@code rateLimiter}, which the account's other clients share. */
    static HTTPClient create(final VenueRateLimiter rateLimiter) {
        return HTTPClient.builder()
                .withRequestGate(rateLimiter)
                .captureHeaders(rateLimiter.capturedHeaders())
                .withReadBufferSize(READ_BUFFER_BYTES)
                .withConnectTimeout(CONNECT_TIMEOUT_MS)
                .withReadTimeout(READ_TIMEOUT_MS)
                .withResponseTimeout(RESPONSE_TIMEOUT_MS)
                .withKeepAlive(KEEP_ALIVE_IDLE_SECONDS, KEEP_ALIVE_INTERVAL_SECONDS, KEEP_ALIVE_PROBES)
                .build();
    }
}
