package group.gnometrading.orchestrator.gateway.control;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.aeron.HostAddress;
import group.gnometrading.orchestrator.gateway.core.GatewayMessage;
import group.gnometrading.schemas.gateway.MessageHeaderDecoder;
import group.gnometrading.schemas.gateway.SessionHelloDecoder;
import group.gnometrading.schemas.gateway.SessionHelloOrdersDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Reads sessions' hellos, each with its open orders over as many pages as the session needed, into whole
 * {@link Hello}s, one per image at a time. On the gateway's control thread; allocates.
 */
final class HelloPages {

    // A hello whose pages never all arrive is dropped; the session sends it again.
    static final long HELLO_PAGES_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final Logger logger;
    private final LongSupplier nanoClock;
    private final MessageHeaderDecoder header = new MessageHeaderDecoder();
    private final SessionHelloDecoder hello = new SessionHelloDecoder();
    private final SessionHelloOrdersDecoder helloOrders = new SessionHelloOrdersDecoder();
    // Per image: a hello waiting for its pages of open orders.
    private final Map<Integer, Hello> pending = new HashMap<>();

    HelloPages(final Logger logger, final LongSupplier nanoClock) {
        this.logger = logger;
        this.nanoClock = nanoClock;
    }

    /** @return the hello, if it lists no open orders; else null until its last page arrives */
    Hello onHello(final GatewayMessage message) {
        this.header.wrap(message.buffer, 0);
        this.hello.wrap(
                message.buffer, MessageHeaderDecoder.ENCODED_LENGTH, this.header.blockLength(), this.header.version());
        final List<long[]> listings = new ArrayList<>();
        for (final SessionHelloDecoder.ListingsDecoder listing : this.hello.listings()) {
            listings.add(new long[] {listing.exchangeId(), listing.securityId()});
        }
        final Hello hello = new Hello(
                this.hello.sessionSeq(),
                this.hello.sessionId().trim(),
                this.hello.strategyId(),
                this.hello.mode(),
                this.hello.venueAccountId(),
                this.hello.protocolVersion(),
                HostAddress.unpack((int) this.hello.replyIp()),
                this.hello.replyPort(),
                this.hello.lastReportSeq(),
                this.hello.lastReportEpoch(),
                TimeUnit.MILLISECONDS.toNanos(this.hello.codTimeoutMs()),
                TimeUnit.MILLISECONDS.toNanos(this.hello.maxQueueMs()),
                this.hello.openOrderPages(),
                listings,
                this.nanoClock.getAsLong(),
                new int[] {0},
                new ArrayList<>());
        if (hello.pages() == 0) {
            return hello;
        }
        this.pending.put(message.image, hello);
        return null;
    }

    /**
     * A page of the session's open orders, as it holds them.
     *
     * @return the whole hello once this was its last page; else null
     */
    Hello onPage(final GatewayMessage message) {
        final Hello hello = this.pending.get(message.image);
        if (hello == null) {
            return null;
        }
        this.header.wrap(message.buffer, 0);
        this.helloOrders.wrap(
                message.buffer, MessageHeaderDecoder.ENCODED_LENGTH, this.header.blockLength(), this.header.version());
        if (this.helloOrders.sessionSeq() != hello.sessionSeq()) {
            return null;
        }
        for (final SessionHelloOrdersDecoder.OrdersDecoder order : this.helloOrders.orders()) {
            hello.orders()
                    .add(new Hello.Order(
                            order.clientOidCounter(),
                            order.exchangeId(),
                            order.securityId(),
                            order.state(),
                            order.size(),
                            order.exchangeOrderId().trim()));
        }
        hello.pagesSeen()[0]++;
        if (this.helloOrders.last() == 1 || hello.pagesSeen()[0] >= hello.pages()) {
            this.pending.remove(message.image);
            return hello;
        }
        return null;
    }

    /** Forgets the image's hello, if one was being gathered. */
    void drop(final int image) {
        this.pending.remove(image);
    }

    boolean anyPending() {
        return !this.pending.isEmpty();
    }

    /** Drops each hello whose pages haven't all arrived in time. */
    void expire(final long now) {
        final Iterator<Map.Entry<Integer, Hello>> it = this.pending.entrySet().iterator();
        while (it.hasNext()) {
            final Hello hello = it.next().getValue();
            if (now - hello.receivedAtNanos() > HELLO_PAGES_NANOS) {
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Gateway: session %s's hello never got all %d pages",
                        hello.sessionId(),
                        hello.pages());
                it.remove();
            }
        }
    }
}
