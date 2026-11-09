package group.gnometrading.orchestrator.gateway.control;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.gateways.account.lane.AccountLane;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.AccountState;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.orders.recovery.VenueOrder;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronStreamIds;
import group.gnometrading.orchestrator.gateway.core.AccountLink;
import group.gnometrading.orchestrator.gateway.core.GatewayMessage;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.GatewayStatus;
import group.gnometrading.orchestrator.gateway.core.ReplayRing;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.gateway.GatewayState;
import group.gnometrading.schemas.gateway.GoodbyeReason;
import group.gnometrading.schemas.gateway.HelloRefusal;
import group.gnometrading.schemas.gateway.MessageHeaderDecoder;
import group.gnometrading.schemas.gateway.MessageHeaderEncoder;
import group.gnometrading.schemas.gateway.Mode;
import group.gnometrading.schemas.gateway.OrderState;
import group.gnometrading.schemas.gateway.OrderStatusEncoder;
import group.gnometrading.schemas.gateway.ReHelloReason;
import group.gnometrading.schemas.gateway.ReHelloRequiredEncoder;
import group.gnometrading.schemas.gateway.SessionGoodbyeDecoder;
import group.gnometrading.schemas.gateway.SessionGoodbyeEncoder;
import group.gnometrading.schemas.gateway.SessionHelloDecoder;
import group.gnometrading.schemas.gateway.SessionHelloOrdersDecoder;
import group.gnometrading.schemas.gateway.SessionWelcomeEncoder;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.fee.FeeTimeline;
import group.gnometrading.utils.CauseChain;
import io.aeron.Aeron;
import io.aeron.Publication;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The gateway's control thread: everything about sessions that may block or allocate.
 *
 * <ul>
 *   <li>A hello (and its pages of open orders) is checked against the registry: the session is starting or running,
 *       its slot and strategy are the registry's, it is bound to an account this gateway holds, and it leases every
 *       listing it names. Those listings are made tradeable, its reply publication opened, its image linked, and a
 *       welcome sent that says from which report the replay starts; or it is refused with the reason.
 *   <li>A session silent past its cancel-on-disconnect timeout, or one that stops echoing the gateway's heartbeats (its
 *       replies aren't arriving), has every order cancelled, is unlinked, and is told to say hello again.
 *   <li>A message that can't be read evicts its session the same way, and refuses its hellos for 5 minutes.
 *   <li>The gateway's state (READY once every account is, DEGRADED while any isn't) and its endpoint record.
 *   <li>A planned stop: every session's orders cancelled and a goodbye sent, waiting for the lanes to empty.
 * </ul>
 */
public final class GatewayControl implements GnomeAgent {

    /** How a listing is found and priced: by the security master in the process. */
    public interface Listings {
        Listing listing(int exchangeId, long securityId);

        FeeTimeline fees(Listing listing);
    }

    /** Records where sessions reach the gateway, and its state; called every {@link #ADVERTISE_INTERVAL_NANOS}. */
    @FunctionalInterface
    public interface Advertiser {
        void advertise(GatewayState state);
    }

    /** Opens the publication a session's replies go on; blocks on the media driver. */
    @FunctionalInterface
    public interface Replies {
        Publication open(String sessionIp, int port);

        static Replies over(final Aeron aeron) {
            return (ip, port) ->
                    aeron.addPublication(AeronChannels.gatewayReply(ip, port), AeronStreamIds.GATEWAY_REPLY);
        }
    }

    /**
     * When the gateway counts as idle and stops: no session linked or saying hello and no order working for
     * {@code afterNanos}, and no session has asked for it (the registry's {@code wanted_at}) since that began. Zero never
     * stops.
     */
    public record Idle(long afterNanos, LongSupplier wantedAtMillis, LongSupplier wallMillis, Runnable onIdle) {
        public static final Idle NEVER = new Idle(0, () -> 0, System::currentTimeMillis, () -> {});
    }

    /**
     * A session whose orders a restart recovered from the ownership log: held for it until it says hello, cancelled
     * if it doesn't come back within the hello grace plus its cancel-on-disconnect timeout.
     */
    public record Recovered(
            long slot, String sessionId, int strategyId, int accountId, long codTimeoutNanos, Set<Long> counters) {}

    /**
     * What a restart took back: where accepted sessions are recorded, the sessions whose orders were recovered, and how
     * long after the gateway is first ready such a session has to say hello, on top of its own timeout.
     */
    /**
     * @param loaded per account, the listings the restart already made tradeable on it, by listing key: not loaded
     *     again when a hello names them
     */
    public record Restart(
            SessionsFile sessionsFile, List<Recovered> sessions, long helloGraceNanos, Map<Integer, Set<Long>> loaded) {
        public static final long HELLO_GRACE_NANOS = TimeUnit.SECONDS.toNanos(10);
    }

    // How far back the venue is searched for an order a restart lost track of.
    private static final long LOOKBACK_MILLIS = TimeUnit.DAYS.toMillis(2);
    // How long a session no longer linked is kept, with its ring and totals, before it is forgotten.
    static final long UNLINKED_KEEP_NANOS = TimeUnit.MINUTES.toNanos(10);

    static final long ADVERTISE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(15);
    static final long EVICTION_NANOS = TimeUnit.MINUTES.toNanos(5);
    // A refused session is answered on a publication of its own, kept this long for the answer to get through.
    static final long REFUSAL_LINGER_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final int MESSAGES_PER_PASS = 64;

    private final Logger logger;
    private final SessionTable sessions;
    private final GatewayStatus status;
    private final Map<Integer, AccountLink> accounts;
    private final ManyToOneRingBuffer<GatewayMessage> fromIngress;
    private final ManyToOneRingBuffer<GatewayMessage> toEgress;
    private final SessionRegistry registry;
    private final Listings listings;
    private final Replies replies;
    private final Advertiser advertiser;
    private final LongSupplier nanoClock;
    private final int replayCapacity;
    private final Idle idle;
    private final SessionsFile sessionsFile;
    private final long helloGraceNanos;
    // Per slot, a recovered session not yet reconciled: its orders as the ownership log has them.
    private final Map<Long, Recovered> recovered = new HashMap<>();
    // Per slot, when a recovered session's orders are cancelled unless it has said hello.
    private final Map<Long, Long> orphanDeadlines = new HashMap<>();
    // Per slot, when a session stopped being linked.
    private final Map<Long, Long> unlinkedAt = new HashMap<>();
    // Refused hellos whose answer is still waiting for its publication to connect.
    private final List<Refusal> refusals = new ArrayList<>();
    private SessionRegistry.SessionRecord lastRecord;
    private final MessageConsumer<GatewayMessage> handler = this::onMessage;

    private final MessageHeaderDecoder header = new MessageHeaderDecoder();
    private final HelloPages helloPages;
    private final SessionGoodbyeDecoder goodbye = new SessionGoodbyeDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final UnsafeBuffer outbound = new UnsafeBuffer(new byte[GatewayMessage.MAX_LENGTH]);

    // Per slot: what this thread tracks of each linked session.
    private final Map<Long, Link> links = new HashMap<>();
    // Session ids refused until a time, for messages that couldn't be read.
    private final Map<String, Long> evicted = new HashMap<>();
    // Per account: listings made tradeable on it, by listing key.
    private final Map<Integer, Set<Long>> loaded = new HashMap<>();
    private final List<Linger> lingering = new ArrayList<>();
    // Per slot, the reply publication last opened for the session: egress learns of it only with the welcome, so a
    // second hello before then can't find it on the session.
    private final Map<Long, Publication> repliesOpened = new HashMap<>();
    private boolean everReady;
    private long nextTickNanos;
    private long nextAdvertiseNanos;
    private long idleSinceNanos = -1;
    private long idleSinceMillis;
    private boolean idleFired;

    /** What this thread tracks of a linked session: its timeout, and when its echo of our heartbeats last moved. */
    private static final class Link {
        final long codTimeoutNanos;
        long echoed;
        long echoMovedNanos;

        Link(final long codTimeoutNanos, final long echoed, final long now) {
            this.codTimeoutNanos = codTimeoutNanos;
            this.echoed = echoed;
            this.echoMovedNanos = now;
        }
    }

    private record Linger(Publication publication, long closeAtNanos) {}

    private record Refusal(Publication publication, UnsafeBuffer answer, long deadlineNanos) {}

    @SuppressWarnings("checkstyle:ParameterNumber")
    public GatewayControl(
            final Logger logger,
            final SessionTable sessions,
            final GatewayStatus status,
            final Map<Integer, AccountLink> accounts,
            final ManyToOneRingBuffer<GatewayMessage> fromIngress,
            final ManyToOneRingBuffer<GatewayMessage> toEgress,
            final SessionRegistry registry,
            final Listings listings,
            final Replies replies,
            final Advertiser advertiser,
            final LongSupplier nanoClock,
            final int replayCapacity,
            final Idle idle,
            final Restart restart) {
        this.logger = logger;
        this.sessions = sessions;
        this.status = status;
        this.accounts = accounts;
        this.fromIngress = fromIngress;
        this.toEgress = toEgress;
        this.registry = registry;
        this.listings = listings;
        this.replies = replies;
        this.advertiser = advertiser;
        this.nanoClock = nanoClock;
        this.replayCapacity = replayCapacity;
        this.idle = idle;
        this.helloPages = new HelloPages(logger, nanoClock);
        this.sessionsFile = restart.sessionsFile();
        this.helloGraceNanos = restart.helloGraceNanos();
        restart.loaded().forEach((accountId, keys) -> this.loaded.put(accountId, new HashSet<>(keys)));
        for (final Recovered session : restart.sessions()) {
            final AccountLink account = accounts.get(session.accountId());
            if (account == null) {
                continue;
            }
            // Its reports collect in its ring until it says hello; its orders' fills are summed up then.
            sessions.add(new GatewaySession(
                    session.slot(), session.sessionId(), session.strategyId(), account, replayCapacity));
            this.recovered.put(session.slot(), session);
        }
    }

    @Override
    public int doWork() {
        int work = 0;
        this.fromIngress.read(this.handler, MESSAGES_PER_PASS);
        final long now = this.nanoClock.getAsLong();
        if (now - this.nextTickNanos >= 0) {
            this.nextTickNanos = now + TICK_NANOS;
            work += tick(now);
        }
        return work;
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.BACKGROUND;
    }

    @Override
    public String roleName() {
        return "gateway-control";
    }

    // ----- messages from sessions -----

    private void onMessage(final GatewayMessage message) {
        try {
            if (message.kind == GatewayMessage.UNREADABLE) {
                evict(message.image, "a message it couldn't read");
                return;
            }
            final int templateId = SbeMessage.templateId(message.buffer, 0);
            if (templateId == SessionHelloDecoder.TEMPLATE_ID) {
                onHello(message);
            } else if (templateId == SessionHelloOrdersDecoder.TEMPLATE_ID) {
                onHelloOrders(message);
            } else if (templateId == SessionGoodbyeDecoder.TEMPLATE_ID) {
                onGoodbye(message);
            } else {
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Gateway: ignored message %d from image %d",
                        templateId,
                        message.image);
            }
        } catch (RuntimeException e) {
            // A message that decodes but makes no sense is the session's fault, not a reason to stop the control
            // thread.
            this.logger.logf(LogMessage.UNKNOWN_ERROR, "Gateway: bad session message: %s", CauseChain.of(e));
            evict(message.image, "a message it couldn't act on");
        }
    }

    private void onHello(final GatewayMessage message) {
        final Hello hello = this.helloPages.onHello(message);
        if (hello != null) {
            complete(message.image, hello);
        }
    }

    /** The session's open orders, as it holds them; reconciled with what the gateway recovered after a restart. */
    private void onHelloOrders(final GatewayMessage message) {
        final Hello hello = this.helloPages.onPage(message);
        if (hello != null) {
            complete(message.image, hello);
        }
    }

    private void complete(final int image, final Hello hello) {
        final long now = this.nanoClock.getAsLong();
        final HelloRefusal refusal = check(hello, now);
        if (refusal != HelloRefusal.NONE) {
            refuse(hello, refusal, now);
            return;
        }
        final AccountLink account = this.accounts.get(hello.venueAccountId());
        if (!ownsSlotAndImage(image, hello)) {
            refuse(hello, HelloRefusal.WRONG_ACCOUNT, now);
            return;
        }
        final GatewaySession session = sessionFor(hello, account);
        account.markets().addSession(session.slot, session.sessionId);
        introduce(session, this.lastRecord.rateWeight(), hello);
        // Handed to egress with the welcome, which switches to it: nothing goes out on it before the welcome.
        final Publication reply = this.replies.open(hello.replyIp(), hello.replyPort());
        final Publication previous = this.repliesOpened.put(session.slot, reply);
        if (previous != null) {
            this.lingering.add(new Linger(previous, now + REFUSAL_LINGER_NANOS));
        }
        final LongHashSet leases = new LongHashSet();
        for (final long[] listing : hello.listings()) {
            leases.add(PublishedMap.listingKey((int) listing[0], listing[1]));
        }
        session.leases = leases;
        session.lastHeardNanos = now;
        this.sessions.link(session, image);
        final Link link = new Link(hello.codTimeoutNanos(), session.echoedHeartbeatSeq, now);
        final Link linked = this.links.get(session.slot);
        if (linked != null) {
            // A session that can send but not hear us says hello again every couple of seconds; were each hello to
            // restart the clock, it would never be found deaf and its orders would rest for good. A healthy one
            // echoes within a heartbeat of its welcome.
            link.echoMovedNanos = linked.echoMovedNanos;
        }
        this.links.put(session.slot, link);
        session.state = GatewaySession.ACTIVE;
        record(hello, now);
        final Recovered recovered = this.recovered.remove(session.slot);
        this.orphanDeadlines.remove(session.slot);
        if (recovered != null) {
            resync(session, reply);
            reconcile(session, recovered, hello);
        } else if (hello.lastReportSeq() > 0 && hello.lastReportEpoch() != this.status.epoch) {
            // Its numbering is another gateway's: nothing to resume from, so where each order stands instead.
            resync(session, reply);
        } else {
            sendWelcome(session, hello, reply);
        }
        this.logger.logf(
                LogMessage.DEBUG,
                "Gateway: session %s (slot %d) linked on image %d, replies to %s:%d",
                hello.sessionId(),
                hello.sessionSeq(),
                image,
                hello.replyIp(),
                hello.replyPort());
    }

    /** The hello's session as the gateway holds it, or a new one if it holds none. */
    private GatewaySession sessionFor(final Hello hello, final AccountLink account) {
        final GatewaySession held = this.sessions.bySlot(hello.sessionSeq());
        if (held != null) {
            return held;
        }
        final GatewaySession session = new GatewaySession(
                hello.sessionSeq(), hello.sessionId(), hello.strategyId(), account, this.replayCapacity);
        if (hello.lastReportSeq() > 0 && hello.lastReportEpoch() == this.status.epoch) {
            // Numbered by this gateway, which has since forgotten it: its reports carry on from where it is.
            session.replay.continueAfter(hello.lastReportSeq());
        }
        this.sessions.add(session);
        return session;
    }

    /**
     * Whether the hello's session may take its slot and image: the slot is no other session's (one recovered without
     * an owner becomes the hello's, which the registry vouched for), and no other session's messages arrive on the
     * image, or this hello couldn't be told from them.
     */
    private boolean ownsSlotAndImage(final int image, final Hello hello) {
        final GatewaySession onImage = this.sessions.byImage(image);
        if (onImage != null && onImage.slot != hello.sessionSeq()) {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Gateway: session %s said hello on image %d, which carries session %s",
                    hello.sessionId(),
                    image,
                    onImage.sessionId);
            return false;
        }
        final GatewaySession session = this.sessions.bySlot(hello.sessionSeq());
        if (session != null && session.sessionId == null) {
            session.sessionId = hello.sessionId();
        }
        if (session != null && !session.sessionId.equals(hello.sessionId())) {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Gateway: slot %d is %s's, not %s's",
                    hello.sessionSeq(),
                    session.sessionId,
                    hello.sessionId());
            return false;
        }
        return true;
    }

    /** Why the hello can't be taken, or NONE; makes its listings tradeable when it can. */
    private HelloRefusal check(final Hello hello, final long now) {
        final AccountLink account = this.accounts.get(hello.venueAccountId());
        if (this.recovered.containsKey(hello.sessionSeq())
                && (account == null || account.state() != AccountState.READY)) {
            // Its orders' state is summed up from what the account's catch-up reported: not until all of it is in.
            return HelloRefusal.NOT_READY;
        }
        final Long evictedUntil = this.evicted.get(hello.sessionId());
        if (evictedUntil != null && now - evictedUntil < 0) {
            return HelloRefusal.EVICTED;
        }
        final int version = hello.protocolVersion();
        if (version != SessionHelloDecoder.SCHEMA_VERSION && version != SessionHelloDecoder.SCHEMA_VERSION - 1) {
            return HelloRefusal.PROTOCOL_VERSION;
        }
        if (!this.status.takesOrders()) {
            return HelloRefusal.NOT_READY;
        }
        if (accountFull(hello)) {
            return HelloRefusal.SESSION_CAP;
        }
        return checkRegistry(hello, now);
    }

    /** A session new to the gateway, on an account whose lane has no place left for another. */
    private boolean accountFull(final Hello hello) {
        return this.sessions.bySlot(hello.sessionSeq()) == null
                && sessionsOn(hello.venueAccountId()) >= AccountLane.MAX_SESSIONS;
    }

    /** Sessions the gateway holds on the account, linked or not yet forgotten: each has a place in its lane. */
    private int sessionsOn(final int accountId) {
        int count = 0;
        for (final GatewaySession session : this.sessions.all()) {
            if (session.account.accountId() == accountId) {
                count++;
            }
        }
        return count;
    }

    /** Against the registry: the session runs, under this slot and strategy, bound to an account this gateway holds. */
    private HelloRefusal checkRegistry(final Hello hello, final long now) {
        final SessionRegistry.SessionRecord record;
        try {
            record = this.registry.lookup(hello.sessionId(), now);
        } catch (SessionRegistry.UnavailableException e) {
            this.logger.logf(LogMessage.UNKNOWN_ERROR, "Gateway: %s", CauseChain.of(e));
            return HelloRefusal.REGISTRY_UNAVAILABLE;
        }
        this.lastRecord = record;
        if (record == null
                || !record.running()
                || record.sessionSeq() != hello.sessionSeq()
                || record.strategyId() != hello.strategyId()) {
            return HelloRefusal.SESSION_NOT_RUNNING;
        }
        final AccountLink account = this.accounts.get(hello.venueAccountId());
        if (hello.mode() != Mode.LIVE || !"live".equals(record.mode()) || account == null) {
            return HelloRefusal.WRONG_ACCOUNT;
        }
        return checkListings(hello, record, account);
    }

    private HelloRefusal checkListings(
            final Hello hello, final SessionRegistry.SessionRecord record, final AccountLink account) {
        final List<Listing> named = new ArrayList<>();
        for (final long[] key : hello.listings()) {
            final Listing listing = this.listings.listing((int) key[0], key[1]);
            if (listing == null || !record.listingIds().contains(listing.listingId())) {
                return HelloRefusal.LISTING_NOT_LEASED;
            }
            final Integer bound =
                    record.accountByExchange().get(listing.exchange().exchangeId());
            if (bound == null || bound != hello.venueAccountId()) {
                return HelloRefusal.WRONG_ACCOUNT;
            }
            named.add(listing);
        }
        final Set<Long> tradeable = this.loaded.computeIfAbsent(hello.venueAccountId(), id -> new HashSet<>());
        for (final Listing listing : named) {
            final long key = PublishedMap.listingKey(
                    listing.exchange().exchangeId(), listing.security().securityId());
            if (tradeable.contains(key)) {
                continue;
            }
            try {
                account.markets().load(listing, this.listings.fees(listing));
                tradeable.add(key);
            } catch (Exception e) {
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Gateway: can't make listing %d tradeable: %s",
                        listing.listingId(),
                        CauseChain.of(e));
                return HelloRefusal.NOT_READY;
            }
        }
        return HelloRefusal.NONE;
    }

    /**
     * The welcome, and from where the replay starts: after the last report the session has, if the ring still holds what
     * follows it; past the ring, an order status for each order instead, as the reports between are gone.
     */
    private void sendWelcome(final GatewaySession session, final Hello hello, final Publication reply) {
        final ReplayRing replay = session.replay;
        long resumeAfter = hello.lastReportSeq();
        if (resumeAfter > replay.newest()) {
            resumeAfter = replay.newest();
        } else if (resumeAfter < replay.newest() && !replay.holds(resumeAfter + 1)) {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Gateway: session %s missed reports %d to %d, past its replay; sending order statuses",
                    session.sessionId,
                    resumeAfter + 1,
                    replay.oldest() - 1);
            resync(session, reply);
            return;
        }
        final int length = encodeWelcome(session.slot, true, HelloRefusal.NONE);
        toEgress(GatewayMessage.UNNUMBERED, session.slot, resumeAfter, length, reply);
    }

    /** The welcome, then an order status for each order the session's ring names instead of the reports themselves. */
    private void resync(final GatewaySession session, final Publication reply) {
        final int length = encodeWelcome(session.slot, true, HelloRefusal.NONE);
        toEgress(GatewayMessage.RESYNC, session.slot, -1, length, reply);
    }

    /**
     * After a restart, the session's open orders against those the ownership log recovered. One it is cancelling is
     * cancelled again; one the gateway holds that the session doesn't know is cancelled. One the session holds that the
     * gateway doesn't may have ended before the restart with its last report lost with the process: it is looked up at
     * the venue and reported as the venue has it, still-resting ones cancelled first; one the venue never had was never
     * sent, and is reported rejected.
     */
    private void reconcile(final GatewaySession session, final Recovered recovered, final Hello hello) {
        final Set<Long> listed = new HashSet<>();
        for (final Hello.Order order : hello.orders()) {
            listed.add(order.counter());
            if (!recovered.counters().contains(order.counter())) {
                settleUnheld(session, order);
            } else if (order.state() == OrderState.PENDING_CANCEL) {
                cancelOne(session, order.counter(), order.exchangeId(), order.securityId());
            }
        }
        for (final long counter : recovered.counters()) {
            if (!listed.contains(counter)) {
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "GATEWAY_UNATTRIBUTED_EVENTS: session %s doesn't know its order %d; cancelling it",
                        session.sessionId,
                        counter);
                cancelOne(session, counter, 0, 0);
            }
        }
    }

    /** An order the session holds open that the gateway doesn't: reported as the venue has it. */
    private void settleUnheld(final GatewaySession session, final Hello.Order order) {
        final Listing listing = this.listings.listing(order.exchangeId(), order.securityId());
        if (order.exchangeOrderId().isEmpty() || listing == null) {
            // It never had a report, so nothing names it at the venue: with no record of it here, it wasn't sent.
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Gateway: session %s's order %d is unknown here and unnamed at the venue; reported rejected",
                    session.sessionId,
                    order.counter());
            sendStatus(session, order, OrderState.REJECTED, 0, 0, 0);
            return;
        }
        try {
            final VenueOrderQuery query = session.account.orderQuery();
            final long since = this.idle.wallMillis().getAsLong() - LOOKBACK_MILLIS;
            Optional<VenueOrder> found = query.getOrder(listing, order.exchangeOrderId(), since);
            if (found.isEmpty()) {
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Gateway: session %s's order %d never reached the venue; reported rejected",
                        session.sessionId,
                        order.counter());
                sendStatus(session, order, OrderState.REJECTED, 0, 0, 0);
                return;
            }
            if (!found.get().terminal()) {
                this.logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "GATEWAY_UNATTRIBUTED_EVENTS: session %s's order %d rests at the venue unheld; cancelling it",
                        session.sessionId,
                        order.counter());
                query.cancel(listing, found.get());
                found = query.getOrder(listing, order.exchangeOrderId(), since);
            }
            final VenueOrder venue = found.orElseThrow();
            if (!venue.terminal()) {
                throw new IOException("still resting after its cancel");
            }
            sendStatus(
                    session,
                    order,
                    venue.filledQty() >= order.size() ? OrderState.FILLED : OrderState.CANCELED,
                    venue.filledQty(),
                    venue.filledNotional(),
                    venue.fees());
        } catch (Exception e) {
            // Left as the session holds it: its next hello after a restart tries again.
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Gateway: can't settle session %s's order %d at the venue: %s",
                    session.sessionId,
                    order.counter(),
                    CauseChain.of(e));
        }
    }

    private void sendStatus(
            final GatewaySession session,
            final Hello.Order order,
            final OrderState state,
            final long cumulative,
            final long notional,
            final long fees) {
        final OrderStatusEncoder status = new OrderStatusEncoder()
                .wrapAndApplyHeader(this.outbound, 0, this.headerEncoder)
                .sessionSeq(session.slot)
                .reportSeq(0)
                .clientOidCounter(order.counter())
                .exchangeId(order.exchangeId())
                .securityId(order.securityId())
                .state(state)
                .venueOrderId("")
                .cumulativeQty(cumulative)
                .leavesQty(0)
                .notional(notional)
                .fees(fees);
        toEgress(
                GatewayMessage.NUMBERED,
                session.slot,
                -1,
                MessageHeaderEncoder.ENCODED_LENGTH + status.encodedLength());
    }

    private void record(final Hello hello, final long now) {
        final SessionRegistry.SessionRecord record = this.lastRecord;
        try {
            this.sessionsFile.append(new SessionsFile.Entry(
                    hello.sessionSeq(),
                    hello.sessionId(),
                    hello.strategyId(),
                    hello.venueAccountId(),
                    TimeUnit.NANOSECONDS.toMillis(hello.codTimeoutNanos()),
                    this.idle.wallMillis().getAsLong(),
                    record.status(),
                    record.mode(),
                    record.listingIds(),
                    record.accountByExchange(),
                    record.rateWeight()));
        } catch (RuntimeException e) {
            this.logger.logf(LogMessage.UNKNOWN_ERROR, "Gateway: %s", CauseChain.of(e));
        }
    }

    /** Answers a refused hello on a publication of its own: the session may hold none of ours. */
    private void refuse(final Hello hello, final HelloRefusal refusal, final long now) {
        this.logger.logf(
                LogMessage.UNKNOWN_ERROR,
                "Gateway: refused session %s (slot %d): %s",
                hello.sessionId(),
                hello.sessionSeq(),
                refusal);
        final Publication publication = this.replies.open(hello.replyIp(), hello.replyPort());
        final int length = encodeWelcome(hello.sessionSeq(), false, refusal);
        final byte[] answer = new byte[length];
        this.outbound.getBytes(0, answer);
        this.refusals.add(new Refusal(publication, new UnsafeBuffer(answer), now + REFUSAL_LINGER_NANOS));
    }

    /** Offers each refusal once its publication connects, without holding up the thread; gives up at its deadline. */
    private void answerRefusals(final long now) {
        final Iterator<Refusal> it = this.refusals.iterator();
        while (it.hasNext()) {
            final Refusal refusal = it.next();
            final boolean answered = refusal.publication()
                            .offer(refusal.answer(), 0, refusal.answer().capacity())
                    > 0;
            if (answered || now - refusal.deadlineNanos() >= 0) {
                it.remove();
                this.lingering.add(new Linger(refusal.publication(), now + REFUSAL_LINGER_NANOS));
            }
        }
    }

    private int encodeWelcome(final long slot, final boolean accepted, final HelloRefusal refusal) {
        final SessionWelcomeEncoder welcome = new SessionWelcomeEncoder()
                .wrapAndApplyHeader(this.outbound, 0, this.headerEncoder)
                .sessionSeq(slot)
                .accepted((short) (accepted ? 1 : 0))
                .refusal(refusal)
                .gatewayEpoch(this.status.epoch)
                .budgetShareBps(0);
        return MessageHeaderEncoder.ENCODED_LENGTH + welcome.encodedLength();
    }

    private void onGoodbye(final GatewayMessage message) {
        final GatewaySession session = this.sessions.byImage(message.image);
        if (session == null) {
            return;
        }
        this.header.wrap(message.buffer, 0);
        this.goodbye.wrap(
                message.buffer, MessageHeaderDecoder.ENCODED_LENGTH, this.header.blockLength(), this.header.version());
        if (this.goodbye.sessionSeq() != session.slot) {
            return;
        }
        // The session says it has nothing working; cancelling anyway costs nothing if that's so.
        cancelAll(session);
        unlink(session);
        session.account.markets().removeSession(session.slot);
        this.logger.logf(LogMessage.DEBUG, "Gateway: session %s said goodbye", session.sessionId);
    }

    // ----- liveness -----

    private int tick(final long now) {
        int work = 0;
        for (final GatewaySession session : this.sessions.all()) {
            if (session.state == GatewaySession.ACTIVE && silent(session, now)) {
                work++;
            }
        }
        this.helloPages.expire(now);
        cancelOrphans(now);
        answerRefusals(now);
        forgetIdleSessions(now);
        closeLingering(now);
        updateState();
        if (now - this.nextAdvertiseNanos >= 0) {
            this.nextAdvertiseNanos = now + ADVERTISE_INTERVAL_NANOS;
            this.advertiser.advertise(this.status.state());
        }
        checkIdle(now);
        return work;
    }

    private void checkIdle(final long now) {
        if (this.idle.afterNanos() <= 0 || this.idleFired) {
            return;
        }
        if (!quiet()) {
            this.idleSinceNanos = -1;
            return;
        }
        if (this.idleSinceNanos < 0) {
            this.idleSinceNanos = now;
            this.idleSinceMillis = this.idle.wallMillis().getAsLong();
            return;
        }
        if (now - this.idleSinceNanos < this.idle.afterNanos()) {
            return;
        }
        long wantedAt;
        try {
            wantedAt = this.idle.wantedAtMillis().getAsLong();
        } catch (RuntimeException e) {
            // Unsure whether a session is on its way: stay up and look again after another idle spell.
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR, "Gateway: can't read when it was last wanted: %s", CauseChain.of(e));
            wantedAt = Long.MAX_VALUE;
        }
        if (wantedAt >= this.idleSinceMillis) {
            this.idleSinceNanos = now;
            this.idleSinceMillis = this.idle.wallMillis().getAsLong();
            return;
        }
        this.idleFired = true;
        this.logger.logf(
                LogMessage.DEBUG,
                "Gateway: idle for %d minutes and not wanted since; stopping",
                TimeUnit.NANOSECONDS.toMinutes(now - this.idleSinceNanos));
        this.idle.onIdle().run();
    }

    /** No session linked or saying hello, and no order working. */
    private boolean quiet() {
        if (this.helloPages.anyPending()) {
            return false;
        }
        for (final GatewaySession session : this.sessions.all()) {
            if (session.state != GatewaySession.UNLINKED) {
                return false;
            }
        }
        for (final AccountLink account : this.accounts.values()) {
            if (account.workingOrders() != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the session has gone quiet one way or the other past its timeout: it stopped sending, or it stopped
     * echoing our heartbeats (ours aren't reaching it, so neither would its reports); if so its orders are cancelled.
     */
    private boolean silent(final GatewaySession session, final long now) {
        final Link link = this.links.get(session.slot);
        if (link == null) {
            return false;
        }
        final long echoed = session.echoedHeartbeatSeq;
        if (echoed != link.echoed) {
            link.echoed = echoed;
            link.echoMovedNanos = now;
        }
        final boolean quiet = now - session.lastHeardNanos > link.codTimeoutNanos;
        final boolean deaf = now - link.echoMovedNanos > link.codTimeoutNanos;
        if (!quiet && !deaf) {
            return false;
        }
        this.logger.logf(
                LogMessage.UNKNOWN_ERROR,
                "Gateway: session %s %s for %d ms; cancelling its orders",
                session.sessionId,
                quiet ? "silent" : "not hearing the gateway",
                TimeUnit.NANOSECONDS.toMillis(now - (quiet ? session.lastHeardNanos : link.echoMovedNanos)));
        cut(session, ReHelloReason.COD_FIRED);
        return true;
    }

    /**
     * Cancels the session's orders and tells it to say hello again if it is listening. Its image stays its own, so
     * anything it sends before it hears is refused with a report rather than dropped unanswered.
     */
    private void cut(final GatewaySession session, final ReHelloReason reason) {
        cancelAll(session);
        session.state = GatewaySession.UNLINKED;
        this.links.remove(session.slot);
        this.unlinkedAt.put(session.slot, this.nanoClock.getAsLong());
        final int length = MessageHeaderEncoder.ENCODED_LENGTH
                + new ReHelloRequiredEncoder()
                        .wrapAndApplyHeader(this.outbound, 0, this.headerEncoder)
                        .sessionSeq(session.slot)
                        .reason(reason)
                        .encodedLength();
        toEgress(GatewayMessage.UNNUMBERED, session.slot, -1, length);
    }

    private void evict(final int image, final String why) {
        final GatewaySession session = this.sessions.byImage(image);
        this.helloPages.drop(image);
        if (session == null) {
            this.logger.logf(LogMessage.UNKNOWN_ERROR, "Gateway: image %d sent %s; no session to evict", image, why);
            return;
        }
        this.logger.logf(
                LogMessage.UNKNOWN_ERROR,
                "Gateway: evicting session %s for %s; refused for %d minutes",
                session.sessionId,
                why,
                TimeUnit.NANOSECONDS.toMinutes(EVICTION_NANOS));
        this.evicted.put(session.sessionId, this.nanoClock.getAsLong() + EVICTION_NANOS);
        cut(session, ReHelloReason.EVICTED);
    }

    private void cancelAll(final GatewaySession session) {
        laneCommand(session, LaneCommand.CANCEL_SESSION);
    }

    /**
     * Tells the session's lane its share of the account's budget, how long its orders may wait for it, and the caps it
     * asked for (the lane holds it to the gateway's), before any order of this hello reaches the lane: ingress
     * forwards none until the session is linked, after this.
     */
    private void introduce(final GatewaySession session, final int weight, final Hello hello) {
        final ManyToOneRingBuffer<LaneCommand> lane = session.account.commands();
        final int index = claim(lane);
        final LaneCommand command = lane.indexAt(index);
        command.reset();
        command.kind = LaneCommand.SESSION;
        command.sessionSlot = session.slot;
        command.weight = weight;
        command.maxQueueNanos = hello.maxQueueNanos();
        command.maxOpenOrders = hello.maxOpenOrders();
        command.maxNewPerSecond = hello.maxNewPerSecond();
        lane.commit(index);
    }

    private void laneCommand(final GatewaySession session, final int kind) {
        final ManyToOneRingBuffer<LaneCommand> lane = session.account.commands();
        final int index = claim(lane);
        final LaneCommand command = lane.indexAt(index);
        command.reset();
        command.kind = kind;
        command.sessionSlot = session.slot;
        lane.commit(index);
    }

    private static int claim(final ManyToOneRingBuffer<LaneCommand> lane) {
        int index;
        while ((index = lane.tryClaim()) < 0) {
            Thread.onSpinWait();
        }
        return index;
    }

    private void cancelOne(
            final GatewaySession session, final long counter, final int exchangeId, final long securityId) {
        final ManyToOneRingBuffer<LaneCommand> lane = session.account.commands();
        final int index = claim(lane);
        final LaneCommand command = lane.indexAt(index);
        command.reset();
        command.kind = LaneCommand.CANCEL;
        command.sessionSlot = session.slot;
        command.clientOidCounter = counter;
        command.clientOidStrategyId = session.strategyId;
        command.exchangeId = exchangeId;
        command.securityId = securityId;
        lane.commit(index);
    }

    private void unlink(final GatewaySession session) {
        session.state = GatewaySession.UNLINKED;
        this.sessions.unlink(session);
        this.links.remove(session.slot);
        this.unlinkedAt.put(session.slot, this.nanoClock.getAsLong());
    }

    /**
     * Forgets a session unlinked for a while with nothing left open, closing its publication: a gateway up for weeks
     * would otherwise keep every session it ever served.
     */
    private void forgetIdleSessions(final long now) {
        final Iterator<Map.Entry<Long, Long>> it = this.unlinkedAt.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Long, Long> unlinked = it.next();
            final GatewaySession session = this.sessions.bySlot(unlinked.getKey());
            if (session == null || session.state != GatewaySession.UNLINKED) {
                it.remove();
                continue;
            }
            if (now - unlinked.getValue() < UNLINKED_KEEP_NANOS || session.totals.size() > 0) {
                continue;
            }
            it.remove();
            this.sessions.remove(session);
            session.account.markets().removeSession(session.slot);
            laneCommand(session, LaneCommand.SESSION_ENDED);
            final Publication reply = this.repliesOpened.remove(session.slot);
            if (reply != null) {
                this.lingering.add(new Linger(reply, now + REFUSAL_LINGER_NANOS));
            }
            this.logger.logf(LogMessage.DEBUG, "Gateway: forgot session %s (slot %d)", session.sessionId, session.slot);
        }
    }

    /**
     * Each recovered session's timer starts once its own account is ready, as it can't say hello before: one account
     * that never comes up holds back no other's.
     */
    private void startOrphanTimers() {
        final long now = this.nanoClock.getAsLong();
        for (final Recovered session : this.recovered.values()) {
            final AccountLink account = this.accounts.get(session.accountId());
            if (!this.orphanDeadlines.containsKey(session.slot())
                    && account != null
                    && account.state() == AccountState.READY) {
                this.orphanDeadlines.put(session.slot(), now + this.helloGraceNanos + session.codTimeoutNanos());
            }
        }
    }

    /** A recovered session that hasn't said hello in time is gone: its orders are cancelled, as on a disconnect. */
    private void cancelOrphans(final long now) {
        final Iterator<Map.Entry<Long, Long>> it =
                this.orphanDeadlines.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Long, Long> deadline = it.next();
            if (now - deadline.getValue() < 0) {
                continue;
            }
            it.remove();
            final Recovered gone = this.recovered.remove(deadline.getKey());
            final GatewaySession session = this.sessions.bySlot(deadline.getKey());
            if (session == null || session.state == GatewaySession.ACTIVE) {
                continue;
            }
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Gateway: session %s didn't come back after the restart; cancelling its %d orders",
                    session.sessionId,
                    gone == null ? 0 : gone.counters().size());
            cancelAll(session);
            session.state = GatewaySession.UNLINKED;
            this.unlinkedAt.put(session.slot, now);
        }
    }

    private void closeLingering(final long now) {
        final Iterator<Linger> it = this.lingering.iterator();
        while (it.hasNext()) {
            final Linger linger = it.next();
            if (now - linger.closeAtNanos() >= 0) {
                linger.publication().close();
                it.remove();
            }
        }
    }

    private void updateState() {
        final GatewayState current = this.status.state();
        if (current == GatewayState.STOPPING) {
            return;
        }
        boolean allReady = true;
        for (final AccountLink account : this.accounts.values()) {
            allReady &= account.state() == AccountState.READY;
        }
        startOrphanTimers();
        this.everReady |= allReady;
        final GatewayState next =
                allReady ? GatewayState.READY : this.everReady ? GatewayState.DEGRADED : GatewayState.STARTING;
        if (next != current) {
            this.status.state(next);
            this.logger.logf(LogMessage.DEBUG, "Gateway: %s -> %s", current, next);
        }
    }

    // ----- planned stop -----

    /**
     * Stops taking orders, cancels every session's orders and says goodbye, then waits until the lanes hold nothing or
     * {@code timeoutNanos} passes. Call with the control thread stopped.
     *
     * @return whether every lane emptied in time
     */
    public boolean stop(final long timeoutNanos) throws InterruptedException {
        this.status.state(GatewayState.STOPPING);
        this.advertiser.advertise(GatewayState.STOPPING);
        for (final GatewaySession session : this.sessions.all()) {
            cancelAll(session);
            if (session.state == GatewaySession.ACTIVE) {
                final int length = MessageHeaderEncoder.ENCODED_LENGTH
                        + new SessionGoodbyeEncoder()
                                .wrapAndApplyHeader(this.outbound, 0, this.headerEncoder)
                                .sessionSeq(session.slot)
                                .reason(GoodbyeReason.PLANNED_STOP)
                                .encodedLength();
                toEgress(GatewayMessage.UNNUMBERED, session.slot, -1, length);
            }
            unlink(session);
        }
        final long deadline = this.nanoClock.getAsLong() + timeoutNanos;
        while (this.nanoClock.getAsLong() - deadline < 0) {
            if (this.accounts.values().stream().allMatch(account -> account.workingOrders() == 0)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private void toEgress(final int kind, final long slot, final long resumeAfter, final int length) {
        toEgress(kind, slot, resumeAfter, length, null);
    }

    /** @param reply for a welcome, the session's new publication, which egress switches to as it sends the welcome */
    private void toEgress(
            final int kind, final long slot, final long resumeAfter, final int length, final Publication reply) {
        int index;
        while ((index = this.toEgress.tryClaim()) < 0) {
            Thread.onSpinWait();
        }
        final GatewayMessage message = this.toEgress.indexAt(index);
        message.kind = kind;
        message.image = 0;
        message.slot = slot;
        message.resumeAfter = resumeAfter;
        message.reply = reply;
        message.buffer.putBytes(0, this.outbound, 0, length);
        message.length = length;
        this.toEgress.commit(index);
    }
}
