package group.gnometrading.orchestrator.gateway.transport;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.AccountState;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.orchestrator.aeron.AeronPathAgent;
import group.gnometrading.orchestrator.gateway.core.GatewayMessage;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.GatewayStatus;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.orchestrator.gateway.core.Kills;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import group.gnometrading.schemas.CancelOrder;
import group.gnometrading.schemas.CancelOrderDecoder;
import group.gnometrading.schemas.MessageSchema;
import group.gnometrading.schemas.ModifyOrder;
import group.gnometrading.schemas.ModifyOrderDecoder;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderDecoder;
import group.gnometrading.schemas.RejectReason;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.gateway.SessionHeartbeatDecoder;
import io.aeron.Subscription;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.Header;
import java.util.function.LongSupplier;
import org.agrona.DirectBuffer;

/**
 * Reads every session's messages off the gateway's ingress subscription. Core orders carry no session, so the sender is
 * the session linked to the Aeron image the message arrived on; one from an image no session is linked to is dropped
 * and counted. Orders become commands on the owning account's lane, or are refused with a report straight back when the
 * session isn't trading, the listing isn't its lease, a kill covers it ({@link Kills}), the gateway or account can't
 * take them, or the order would trade against another session's ({@link CrossIndex}). Heartbeats are noted
 * on the session; hellos, goodbyes and queries go to the control thread, as does any message that can't be read, which
 * evicts its session.
 *
 * <p>A full lane or reject ring stops the poll and leaves the message in Aeron, to be read again, so no order is
 * dropped for want of room. A hello, goodbye or query the control thread's ring has no room for is dropped and counted:
 * the session sends it again when it hears nothing. Allocates nothing.
 */
public final class GatewayIngress implements AeronPathAgent {

    private static final int FRAGMENT_LIMIT = 64;

    private final Subscription subscription;
    private final SessionTable sessions;
    private final GatewayStatus status;
    private final GatewayRejects rejects;
    private final ManyToOneRingBuffer<GatewayMessage> toControl;
    private final LongSupplier nanoClock;
    private final JournalRecorder journal;
    private final CrossIndex cross;
    private final Kills kills;
    private final Order order = new Order();
    private final CancelOrder cancel = new CancelOrder();
    private final ModifyOrder modify = new ModifyOrder();
    private final SessionHeartbeatDecoder heartbeat = new SessionHeartbeatDecoder();
    private final ControlledFragmentHandler handler = this::onFragment;

    // Written only here; volatile so the gateway's minute report reads them.
    private volatile long unlinked;
    private volatile long fragmented;
    private volatile long controlFull;

    public GatewayIngress(
            final Subscription subscription,
            final SessionTable sessions,
            final GatewayStatus status,
            final GatewayRejects rejects,
            final ManyToOneRingBuffer<GatewayMessage> toControl,
            final LongSupplier nanoClock,
            final JournalRecorder journal,
            final CrossIndex cross,
            final Kills kills) {
        this.subscription = subscription;
        this.sessions = sessions;
        this.status = status;
        this.rejects = rejects;
        this.toControl = toControl;
        this.nanoClock = nanoClock;
        this.journal = journal;
        this.cross = cross;
        this.kills = kills;
    }

    @Override
    public int doWork() {
        this.cross.drain();
        return this.subscription.controlledPoll(this.handler, FRAGMENT_LIMIT);
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.HOT_PATH;
    }

    @Override
    public String roleName() {
        return "gateway-ingress";
    }

    /** Orders from an image no session is linked to, dropped. */
    public long unlinked() {
        return this.unlinked;
    }

    // Core v2 block lengths of the messages the gateway reads (see fits).
    private static final int ORDER_V2_BLOCK = 50;
    private static final int CANCEL_V2_BLOCK = 30;
    private static final int MODIFY_V2_BLOCK = 49;

    /** Messages that arrived in fragments, dropped: every gateway message fits one frame. */
    public long fragmented() {
        return this.fragmented;
    }

    /** Session messages dropped because the control thread's ring was full. */
    public long controlFull() {
        return this.controlFull;
    }

    /** Journals each message once it has been acted on: one left in Aeron for want of room is read again later. */
    private ControlledFragmentHandler.Action onFragment(
            final DirectBuffer buffer, final int offset, final int length, final Header header) {
        if ((header.flags() & FrameDescriptor.UNFRAGMENTED) != FrameDescriptor.UNFRAGMENTED) {
            this.fragmented = this.fragmented + 1;
            return ControlledFragmentHandler.Action.CONTINUE;
        }
        final ControlledFragmentHandler.Action action = handle(buffer, offset, length, header.sessionId());
        if (action == ControlledFragmentHandler.Action.CONTINUE
                && length >= SbeMessage.HEADER_LENGTH
                && SbeMessage.templateId(buffer, offset) != SessionHeartbeatDecoder.TEMPLATE_ID) {
            final GatewaySession session = this.sessions.byImage(header.sessionId());
            this.journal.record(session == null ? 0 : session.slot, buffer, offset, length);
        }
        return action;
    }

    private ControlledFragmentHandler.Action handle(
            final DirectBuffer buffer, final int offset, final int length, final int image) {
        if (length < SbeMessage.HEADER_LENGTH) {
            return toControl(GatewayMessage.UNREADABLE, image, buffer, offset, 0);
        }
        final int schemaId = SbeMessage.schemaId(buffer, offset);
        final int templateId = SbeMessage.templateId(buffer, offset);
        if (schemaId == MessageSchema.CORE.schemaId()) {
            return onOrder(templateId, buffer, offset, length, image);
        }
        if (schemaId != MessageSchema.GATEWAY.schemaId()) {
            return toControl(GatewayMessage.UNREADABLE, image, buffer, offset, 0);
        }
        if (templateId == SessionHeartbeatDecoder.TEMPLATE_ID) {
            onHeartbeat(buffer, offset, length, image);
            return ControlledFragmentHandler.Action.CONTINUE;
        }
        return toControl(GatewayMessage.FROM_SESSION, image, buffer, offset, length);
    }

    private ControlledFragmentHandler.Action onOrder(
            final int templateId, final DirectBuffer buffer, final int offset, final int length, final int image) {
        final boolean done;
        if (templateId == OrderDecoder.TEMPLATE_ID && fits(this.order, buffer, offset, length, ORDER_V2_BLOCK)) {
            this.order.buffer.putBytes(0, buffer, offset, length);
            done = onNew(image);
        } else if (templateId == CancelOrderDecoder.TEMPLATE_ID
                && fits(this.cancel, buffer, offset, length, CANCEL_V2_BLOCK)) {
            this.cancel.buffer.putBytes(0, buffer, offset, length);
            done = onCancel(image);
        } else if (templateId == ModifyOrderDecoder.TEMPLATE_ID
                && fits(this.modify, buffer, offset, length, MODIFY_V2_BLOCK)) {
            this.modify.buffer.putBytes(0, buffer, offset, length);
            done = onAmend(image);
        } else {
            return toControl(GatewayMessage.UNREADABLE, image, buffer, offset, 0);
        }
        return done ? ControlledFragmentHandler.Action.CONTINUE : ControlledFragmentHandler.Action.ABORT;
    }

    /**
     * Whether a session's message can be read: whole by its own header, and holding every field the gateway reads.
     * Sessions and gateways move between core schema versions apart, and a version only appends to a block, so a
     * message from an older or newer session is read as long as its block reaches the core v2 fields: the gateway reads
     * no field added since. Fixed numbers, not the decoders' {@code BLOCK_LENGTH}, which grows with each version.
     */
    private static boolean fits(
            final SbeMessage message,
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final int minimumBlock) {
        final int block = SbeMessage.blockLength(buffer, offset);
        return block >= minimumBlock
                && length >= SbeMessage.HEADER_LENGTH + block
                && length <= message.buffer.capacity();
    }

    /** @return false if there was no room to act on it: the message is read again on the next poll */
    private boolean onNew(final int image) {
        final OrderDecoder decoder = this.order.decoder;
        final GatewaySession session = this.sessions.byImage(image);
        if (session == null) {
            this.unlinked = this.unlinked + 1;
            return true;
        }
        final long counter = this.order.getClientOidCounter();
        final int strategyId = this.order.getClientOidStrategyId();
        final int exchangeId = decoder.exchangeId();
        final long securityId = decoder.securityId();
        final long listingKey = PublishedMap.listingKey(exchangeId, securityId);
        final RejectReason refusal = refusal(session, listingKey);
        if (refusal != null) {
            return this.rejects.reject(session.slot, counter, strategyId, exchangeId, securityId, refusal);
        }
        final Side side = decoder.side();
        final long price = decoder.price();
        final int cross = this.cross.checkNew(session.slot, listingKey, side, price);
        if (cross != CrossIndex.CLEAR) {
            return this.rejects.reject(
                    session.slot,
                    counter,
                    strategyId,
                    exchangeId,
                    securityId,
                    cross == CrossIndex.CROSSES ? RejectReason.SELF_TRADE : RejectReason.SESSION_CAP);
        }
        final ManyToOneRingBuffer<LaneCommand> lane = session.account.commands();
        final int index = lane.tryClaim();
        if (index < 0) {
            return false;
        }
        final LaneCommand command = lane.indexAt(index);
        command.reset();
        command.kind = LaneCommand.NEW;
        command.sessionSlot = session.slot;
        command.clientOidCounter = counter;
        command.clientOidStrategyId = strategyId;
        command.exchangeId = exchangeId;
        command.securityId = securityId;
        command.side = side;
        command.price = price;
        command.size = decoder.size();
        command.timeInForce = decoder.timeInForce();
        command.orderType = decoder.orderType();
        command.postOnly = decoder.flags().postOnly();
        this.cross.add(session.slot, counter, listingKey, side, price);
        lane.commit(index);
        return true;
    }

    /** Why a NEW can't go to the lane, or null if it can. */
    private RejectReason refusal(final GatewaySession session, final long listingKey) {
        if (session.state != GatewaySession.ACTIVE) {
            return RejectReason.SESSION_NOT_REGISTERED;
        }
        if (!this.status.takesOrders() || !this.kills.read()) {
            return RejectReason.GATEWAY_NOT_READY;
        }
        if (!session.leases(listingKey)) {
            return RejectReason.LISTING_NOT_LEASED;
        }
        if (this.kills.rules().covers(session.account.accountId(), listingKey, session.strategyId, session.slot)) {
            return RejectReason.HALTED;
        }
        if (session.account.state() != AccountState.READY) {
            return RejectReason.GATEWAY_DEGRADED;
        }
        return null;
    }

    private boolean onCancel(final int image) {
        final CancelOrderDecoder decoder = this.cancel.decoder;
        final GatewaySession session = this.sessions.byImage(image);
        if (session == null) {
            this.unlinked = this.unlinked + 1;
            return true;
        }
        final long counter = this.cancel.getClientOidCounter();
        final int strategyId = this.cancel.getClientOidStrategyId();
        if (session.state != GatewaySession.ACTIVE) {
            return this.rejects.cancelReject(
                    session.slot,
                    counter,
                    strategyId,
                    decoder.exchangeId(),
                    decoder.securityId(),
                    RejectReason.SESSION_NOT_REGISTERED);
        }
        // Cancels go out whatever the account's state: they are what a degraded account still takes.
        final ManyToOneRingBuffer<LaneCommand> lane = session.account.commands();
        final int index = lane.tryClaim();
        if (index < 0) {
            return false;
        }
        final LaneCommand command = lane.indexAt(index);
        command.reset();
        command.kind = LaneCommand.CANCEL;
        command.sessionSlot = session.slot;
        this.cross.cancelSent(session.slot, counter);
        command.clientOidCounter = counter;
        command.clientOidStrategyId = strategyId;
        command.exchangeId = decoder.exchangeId();
        command.securityId = decoder.securityId();
        lane.commit(index);
        return true;
    }

    private boolean onAmend(final int image) {
        final ModifyOrderDecoder decoder = this.modify.decoder;
        final GatewaySession session = this.sessions.byImage(image);
        if (session == null) {
            this.unlinked = this.unlinked + 1;
            return true;
        }
        final long counter = this.modify.getClientOidCounter();
        final int strategyId = this.modify.getClientOidStrategyId();
        final int exchangeId = decoder.exchangeId();
        final long securityId = decoder.securityId();
        final RejectReason refusal = refusal(session, PublishedMap.listingKey(exchangeId, securityId));
        if (refusal != null) {
            return this.rejects.cancelReject(session.slot, counter, strategyId, exchangeId, securityId, refusal);
        }
        final long price = decoder.price();
        if (this.cross.checkAmend(session.slot, counter, price) != CrossIndex.CLEAR) {
            return this.rejects.cancelReject(
                    session.slot, counter, strategyId, exchangeId, securityId, RejectReason.SELF_TRADE);
        }
        final ManyToOneRingBuffer<LaneCommand> lane = session.account.commands();
        final int index = lane.tryClaim();
        if (index < 0) {
            return false;
        }
        final LaneCommand command = lane.indexAt(index);
        command.reset();
        command.kind = LaneCommand.AMEND;
        command.sessionSlot = session.slot;
        command.clientOidCounter = counter;
        command.clientOidStrategyId = strategyId;
        command.exchangeId = exchangeId;
        command.securityId = securityId;
        command.price = price;
        command.size = decoder.size();
        command.postOnly = decoder.flags().postOnly();
        this.cross.amend(session.slot, counter, price);
        lane.commit(index);
        return true;
    }

    private void onHeartbeat(final DirectBuffer buffer, final int offset, final int length, final int image) {
        final GatewaySession session = this.sessions.byImage(image);
        if (session == null || length < SbeMessage.HEADER_LENGTH + SessionHeartbeatDecoder.BLOCK_LENGTH) {
            return;
        }
        this.heartbeat.wrap(
                buffer,
                offset + SbeMessage.HEADER_LENGTH,
                SessionHeartbeatDecoder.BLOCK_LENGTH,
                SessionHeartbeatDecoder.SCHEMA_VERSION);
        if (this.heartbeat.sessionSeq() != session.slot) {
            return;
        }
        session.echoedHeartbeatSeq = this.heartbeat.lastGatewayHeartbeatSeq();
        session.lastHeardNanos = this.nanoClock.getAsLong();
    }

    private ControlledFragmentHandler.Action toControl(
            final int kind, final int image, final DirectBuffer buffer, final int offset, final int length) {
        if (length > GatewayMessage.MAX_LENGTH) {
            return toControl(GatewayMessage.UNREADABLE, image, buffer, offset, 0);
        }
        final int index = this.toControl.tryClaim();
        if (index < 0) {
            this.controlFull = this.controlFull + 1;
            return ControlledFragmentHandler.Action.CONTINUE;
        }
        final GatewayMessage message = this.toControl.indexAt(index);
        message.kind = kind;
        message.image = image;
        message.slot = 0;
        message.resumeAfter = -1;
        message.buffer.putBytes(0, buffer, offset, length);
        message.length = length;
        this.toControl.commit(index);
        return ControlledFragmentHandler.Action.CONTINUE;
    }
}
