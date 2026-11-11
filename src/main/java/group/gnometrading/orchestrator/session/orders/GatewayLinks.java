package group.gnometrading.orchestrator.session.orders;

import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronStreamIds;
import group.gnometrading.schemas.gateway.HelloRefusal;
import group.gnometrading.utils.CauseChain;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;

/**
 * The control-thread side of the session's gateway links: opening and reopening each link's reply subscription and
 * ingress publication, which block on the media driver. A link the client has taken down (or never had up) is opened
 * again, a second at most between attempts: its old publication and subscription are taken from the client first and
 * closed, the gateway found, a reply subscription opened on a port the driver picks (the hello names it), then the
 * ingress publication. It also tells the registry the session wants each gateway, every {@link #WANT_WAITING_NANOS}
 * while the link isn't ready and every {@link #WANT_READY_NANOS} once it is: gateways run only while sessions want them,
 * so one that is stopped, or stopping, is started, and one in use is never stopped. Runs on a scheduled thread of its
 * own; allocates freely.
 */
public final class GatewayLinks implements Runnable, AutoCloseable {

    /** Where a gateway takes sessions. */
    record Endpoint(String ip, int port) {}

    /** Tells the registry the session wants the gateway; a failure is the caller's to log, and is said again. */
    @FunctionalInterface
    interface Wanting {
        Wanting NONE = gatewayKey -> {};

        void want(String gatewayKey) throws Exception;
    }

    /** Finds a gateway by its key; null while it isn't up. */
    @FunctionalInterface
    interface Resolver {
        Endpoint resolve(String gatewayKey);
    }

    static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);
    // While a link waits for its gateway, which may be starting from nothing.
    static final long WANT_WAITING_NANOS = TimeUnit.SECONDS.toNanos(10);
    // Once linked: well inside the time the registry keeps a gateway nobody has asked for.
    static final long WANT_READY_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final long PORT_WAIT_NANOS = TimeUnit.SECONDS.toNanos(2);
    // Long enough for the client to have finished any pass that still holds the old publication or subscription.
    private static final long HANDOVER_MILLIS = 50;

    private final Logger logger;
    private final Aeron aeron;
    private final String localIp;
    private final List<GatewayLink> links;
    private final Resolver resolver;
    private final LongSupplier nanoClock;
    private final Wanting wanting;
    private final Map<GatewayLink, Long> retryAt = new HashMap<>();
    private final Map<GatewayLink, Long> wantAt = new HashMap<>();
    // Tests make a session deaf by advertising a reply port nothing listens on.
    volatile IntUnaryOperator advertisedReplyPort = IntUnaryOperator.identity();

    GatewayLinks(
            final Logger logger,
            final Aeron aeron,
            final String localIp,
            final List<GatewayLink> links,
            final Resolver resolver,
            final LongSupplier nanoClock) {
        this(logger, aeron, localIp, links, resolver, Wanting.NONE, nanoClock);
    }

    GatewayLinks(
            final Logger logger,
            final Aeron aeron,
            final String localIp,
            final List<GatewayLink> links,
            final Resolver resolver,
            final Wanting wanting,
            final LongSupplier nanoClock) {
        this.wanting = wanting;
        this.logger = logger;
        this.aeron = aeron;
        this.localIp = localIp;
        this.links = links;
        this.resolver = resolver;
        this.nanoClock = nanoClock;
    }

    @Override
    public void run() {
        for (final GatewayLink link : this.links) {
            want(link);
            try {
                tend(link);
            } catch (Exception e) {
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Gateway %s: can't open the link: %s",
                        link.gatewayKey,
                        CauseChain.of(e));
                this.retryAt.put(link, this.nanoClock.getAsLong() + RETRY_NANOS);
            }
        }
    }

    private void want(final GatewayLink link) {
        final long now = this.nanoClock.getAsLong();
        final Long due = this.wantAt.get(link);
        if (due != null && now - due < 0) {
            return;
        }
        this.wantAt.put(link, now + (link.state == GatewayLink.READY ? WANT_READY_NANOS : WANT_WAITING_NANOS));
        try {
            this.wanting.want(link.gatewayKey);
        } catch (Exception e) {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Couldn't tell the registry gateway %s is wanted: %s",
                    link.gatewayKey,
                    CauseChain.of(e));
        }
    }

    private void tend(final GatewayLink link) throws InterruptedException {
        if (link.state != GatewayLink.DOWN) {
            return;
        }
        final long now = this.nanoClock.getAsLong();
        final Long due = this.retryAt.get(link);
        if (due != null && now - due < 0) {
            return;
        }
        this.retryAt.put(link, now + RETRY_NANOS);
        if (link.connectTimedOut) {
            link.connectTimedOut = false;
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Gateway %s: nothing answered at its address for %d s; looking it up again",
                    link.gatewayKey,
                    TimeUnit.NANOSECONDS.toSeconds(GatewayClient.CONNECT_TIMEOUT_NANOS));
        }
        final HelloRefusal refusal = link.refusal;
        if (refusal != HelloRefusal.NONE) {
            this.logger.logf(LogMessage.UNKNOWN_ERROR, "Gateway %s refused the session: %s", link.gatewayKey, refusal);
            link.refusal = HelloRefusal.NONE;
        }
        retire(link);
        final Endpoint endpoint = this.resolver.resolve(link.gatewayKey);
        if (endpoint == null) {
            return;
        }
        final Subscription replies = this.aeron.addSubscription(
                AeronChannels.gatewayReplySubscription(this.localIp), AeronStreamIds.GATEWAY_REPLY);
        final int port = boundPort(replies);
        if (port <= 0) {
            replies.close();
            throw new IllegalStateException("The reply subscription was given no port");
        }
        // Exclusive: its own Aeron session id, which is how the gateway tells this session's messages from any other's
        // (a shared publication would give every link on this driver the same one). Only the client offers on it.
        final Publication publication = this.aeron.addExclusivePublication(
                AeronChannels.gatewayIngress(endpoint.ip(), endpoint.port()), AeronStreamIds.GATEWAY_INGRESS);
        link.replyIp = this.localIp;
        link.replyPort = this.advertisedReplyPort.applyAsInt(port);
        link.replies = replies;
        link.publication = publication;
        link.helloPage = 0;
        link.connectingSinceNanos = this.nanoClock.getAsLong();
        link.state = GatewayLink.CONNECTING;
        this.logger.logf(
                LogMessage.DEBUG,
                "Gateway %s: connecting to %s:%d, replies on %s:%d",
                link.gatewayKey,
                endpoint.ip(),
                endpoint.port(),
                this.localIp,
                port);
    }

    /** Takes the link's publication and subscription from the client, then closes them (which blocks). */
    private void retire(final GatewayLink link) throws InterruptedException {
        final Publication publication = link.publication;
        final Subscription replies = link.replies;
        if (publication == null && replies == null) {
            return;
        }
        link.publication = null;
        link.replies = null;
        Thread.sleep(HANDOVER_MILLIS);
        if (publication != null) {
            publication.close();
        }
        if (replies != null) {
            replies.close();
        }
    }

    private int boundPort(final Subscription replies) throws InterruptedException {
        final long deadline = this.nanoClock.getAsLong() + PORT_WAIT_NANOS;
        while (this.nanoClock.getAsLong() - deadline < 0) {
            final List<String> addresses = replies.localSocketAddresses();
            if (!addresses.isEmpty()) {
                final String address = addresses.get(0);
                return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
            }
            Thread.sleep(5);
        }
        return -1;
    }

    @Override
    public void close() throws InterruptedException {
        for (final GatewayLink link : this.links) {
            link.state = GatewayLink.DOWN;
            retire(link);
        }
    }
}
