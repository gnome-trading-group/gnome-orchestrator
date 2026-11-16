package group.gnometrading.orchestrator.gateway;

import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.lane.LaneVenue;
import group.gnometrading.gateways.account.lane.RequestShape;
import group.gnometrading.gateways.account.lane.RequestTiming;
import group.gnometrading.gateways.orders.CancelResult;
import group.gnometrading.gateways.orders.LookupResult;
import group.gnometrading.gateways.orders.OrderContext;
import group.gnometrading.gateways.orders.SubmitResult;
import group.gnometrading.gateways.venues.kalshi.KalshiAuthSigner;
import group.gnometrading.gateways.venues.kalshi.KalshiOrderCodec;
import group.gnometrading.networking.http.HTTPRequestWriter;
import group.gnometrading.networking.http.HTTPResponse;
import group.gnometrading.schemas.RejectReason;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.function.ToIntFunction;

/**
 * A test venue whose every request is signed as Kalshi's are (RSA-PSS over the timestamp, method and path, on the
 * lane's thread, its three headers added), so a load test's lane pays a live Kalshi lane's per-request cost. The key is
 * made at start, 2048 bits as Kalshi's are, and thrown away: the venue never checks it.
 */
public final class SigningLaneVenue implements LaneVenue<String> {

    private static final byte[] HEADER_KEY = ascii(KalshiOrderCodec.HEADER_KEY);
    private static final byte[] HEADER_TIMESTAMP = ascii(KalshiOrderCodec.HEADER_TIMESTAMP);
    private static final byte[] HEADER_SIGNATURE = ascii(KalshiOrderCodec.HEADER_SIGNATURE);

    private final LaneVenue<String> venue;
    private final KalshiAuthSigner signer;

    public SigningLaneVenue(final LaneVenue<String> venue) {
        this.venue = venue;
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.signer = new KalshiAuthSigner(
                    "load-test-key", generator.generateKeyPair().getPrivate());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("No throwaway signing key: " + e.getMessage(), e);
        }
    }

    private void sign(final RequestShape shape, final long nowMillis, final HTTPRequestWriter request) {
        try {
            this.signer.sign(nowMillis, shape.method.name(), shape.path, shape.signed());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        request.header(HEADER_KEY, this.signer.apiKeyBytes())
                .header(HEADER_TIMESTAMP, this.signer.timestampBytes(), 0, this.signer.timestampLength())
                .header(HEADER_SIGNATURE, this.signer.signatureBytes(), 0, this.signer.signatureLength());
    }

    @Override
    public String market(final int exchangeId, final long securityId) {
        return this.venue.market(exchangeId, securityId);
    }

    @Override
    public int prepare(
            final LaneCommand command,
            final String market,
            final OrderContext ctx,
            final byte[] body,
            final long nowMillis) {
        return this.venue.prepare(command, market, ctx, body, nowMillis);
    }

    @Override
    public boolean resendsAreSafe() {
        return this.venue.resendsAreSafe();
    }

    @Override
    public boolean venueIdKnown(final OrderContext ctx) {
        return this.venue.venueIdKnown(ctx);
    }

    @Override
    public boolean canAmend(final OrderContext ctx, final String market, final long price, final long size) {
        return this.venue.canAmend(ctx, market, price, size);
    }

    @Override
    public boolean canLookUp(final OrderContext ctx) {
        return this.venue.canLookUp(ctx);
    }

    @Override
    public void submitShape(final OrderContext ctx, final String market, final RequestShape shape) {
        this.venue.submitShape(ctx, market, shape);
    }

    @Override
    public boolean lookupShape(final OrderContext ctx, final String market, final RequestShape shape) {
        return this.venue.lookupShape(ctx, market, shape);
    }

    @Override
    public void cancelShape(final OrderContext ctx, final String market, final RequestShape shape) {
        this.venue.cancelShape(ctx, market, shape);
    }

    @Override
    public void amendShape(final OrderContext ctx, final String market, final RequestShape shape) {
        this.venue.amendShape(ctx, market, shape);
    }

    @Override
    public void writeSubmit(
            final OrderContext ctx,
            final RequestShape shape,
            final byte[] body,
            final int bodyLength,
            final HTTPRequestWriter request,
            final long nowMillis) {
        sign(shape, nowMillis, request);
        this.venue.writeSubmit(ctx, shape, body, bodyLength, request, nowMillis);
    }

    @Override
    public void writeLookup(
            final OrderContext ctx,
            final String market,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        sign(shape, nowMillis, request);
        this.venue.writeLookup(ctx, market, shape, request, nowMillis);
    }

    @Override
    public void writeCancel(
            final OrderContext ctx,
            final String market,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        sign(shape, nowMillis, request);
        this.venue.writeCancel(ctx, market, shape, request, nowMillis);
    }

    @Override
    public void writeAmend(
            final OrderContext ctx,
            final String market,
            final long price,
            final long size,
            final boolean postOnly,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        sign(shape, nowMillis, request);
        this.venue.writeAmend(ctx, market, price, size, postOnly, shape, request, nowMillis);
    }

    @Override
    public SubmitResult readSubmit(final OrderContext ctx, final HTTPResponse response) {
        return this.venue.readSubmit(ctx, response);
    }

    @Override
    public LookupResult readLookup(final OrderContext ctx, final HTTPResponse response) {
        return this.venue.readLookup(ctx, response);
    }

    @Override
    public CancelResult readCancel(final OrderContext ctx, final HTTPResponse response) {
        return this.venue.readCancel(ctx, response);
    }

    @Override
    public RejectReason readAmend(
            final OrderContext ctx, final long newSize, final HTTPResponse response, final AmendAnswer out) {
        return this.venue.readAmend(ctx, newSize, response, out);
    }

    @Override
    public void bindHeaders(final ToIntFunction<String> indexOf) {
        this.venue.bindHeaders(indexOf);
    }

    @Override
    public void readTiming(final HTTPResponse response, final RequestTiming timing) {
        this.venue.readTiming(response, timing);
    }

    @Override
    public long finalFillQty(final OrderContext ctx) {
        return this.venue.finalFillQty(ctx);
    }

    @Override
    public boolean readerNeedsVenueId() {
        return this.venue.readerNeedsVenueId();
    }

    @Override
    public void onReleased(final OrderContext ctx) {
        this.venue.onReleased(ctx);
    }

    @Override
    public int maxBatch() {
        return this.venue.maxBatch();
    }

    @Override
    public void batchSubmitShape(final RequestShape shape) {
        this.venue.batchSubmitShape(shape);
    }

    @Override
    public void writeBatchSubmit(
            final OrderContext[] ctxs,
            final byte[][] bodies,
            final int[] bodyLengths,
            final int count,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        sign(shape, nowMillis, request);
        this.venue.writeBatchSubmit(ctxs, bodies, bodyLengths, count, shape, request, nowMillis);
    }

    @Override
    public int readBatchSubmit(
            final OrderContext[] ctxs, final int count, final HTTPResponse response, final SubmitResult[] results) {
        return this.venue.readBatchSubmit(ctxs, count, response, results);
    }

    @Override
    public void batchCancelShape(final RequestShape shape) {
        this.venue.batchCancelShape(shape);
    }

    @Override
    public void writeBatchCancel(
            final OrderContext[] ctxs,
            final int count,
            final RequestShape shape,
            final HTTPRequestWriter request,
            final long nowMillis) {
        sign(shape, nowMillis, request);
        this.venue.writeBatchCancel(ctxs, count, shape, request, nowMillis);
    }

    @Override
    public int readBatchCancel(
            final OrderContext[] ctxs, final int count, final HTTPResponse response, final CancelResult[] results) {
        return this.venue.readBatchCancel(ctxs, count, response, results);
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
