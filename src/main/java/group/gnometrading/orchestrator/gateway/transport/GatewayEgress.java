package group.gnometrading.orchestrator.gateway.transport;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.collections.buffer.MessageConsumer;
import group.gnometrading.concurrent.ThreadProfile;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.account.reader.AccountState;
import group.gnometrading.orchestrator.aeron.AeronPathAgent;
import group.gnometrading.orchestrator.aeron.PendingMessages;
import group.gnometrading.orchestrator.gateway.core.GatewayMessage;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.GatewayStatus;
import group.gnometrading.orchestrator.gateway.core.JournalRecorder;
import group.gnometrading.orchestrator.gateway.core.ReplayRing;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import group.gnometrading.schemas.SbeMessage;
import group.gnometrading.schemas.gateway.GatewayHeartbeatEncoder;
import group.gnometrading.schemas.gateway.MessageHeaderEncoder;
import group.gnometrading.schemas.gateway.OrderStatusDecoder;
import group.gnometrading.schemas.gateway.OrderStatusEncoder;
import io.aeron.Publication;
import io.aeron.ReservedValueSupplier;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Sends each session what is meant for it. Every account's reports (and the gateway's own refusals) are routed by slot,
 * numbered with the session's report sequence and kept in its replay ring; they go out on the session's publication with
 * the sequence as the frame's reserved value, at the session's own pace: a back-pressured publication leaves the rest in
 * the ring for the next pass and holds up no other session. A session with no link yet keeps collecting in its ring.
 *
 * <p>The control thread's messages for a session come through here too, so they stay in order with its reports: an
 * order status is numbered like a report; a welcome, re-hello request or goodbye goes ahead of anything numbered, and a
 * welcome says from which sequence to replay. A heartbeat goes to every linked session every 100 ms. Allocates nothing.
 */
public final class GatewayEgress implements AeronPathAgent {

    static final long HEARTBEAT_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final int REPORTS_PER_PASS = 256;

    private final SessionTable sessions;
    private final GatewayStatus status;
    private final List<ManyToOneRingBuffer<AccountReport>> reportRings;
    private final ManyToOneRingBuffer<GatewayMessage> fromControl;
    private final LongSupplier nanoClock;
    private final EpochNanoClock wallClock;
    private final JournalRecorder journal;
    private final MessageConsumer<AccountReport> reportHandler = this::onReport;
    private final MessageConsumer<GatewayMessage> controlHandler = this::onControl;
    private final SequenceValue reserved = new SequenceValue();
    private final UnsafeBuffer heartbeatBuffer = new UnsafeBuffer(new byte[GatewayMessage.MAX_LENGTH]);
    private final MessageHeaderEncoder header = new MessageHeaderEncoder();
    private final GatewayHeartbeatEncoder heartbeat = new GatewayHeartbeatEncoder();
    private final OrderStatusEncoder orderStatus = new OrderStatusEncoder();
    private final OrderStatusDecoder orderStatusDecoder = new OrderStatusDecoder();
    private final OrderStatuses statuses;
    private long nextHeartbeatNanos;
    private long heartbeatSeq;
    private int handled;

    // Written only here; volatile so the gateway's minute report reads them.
    private volatile long unrouted;
    private volatile long replayGaps;
    private volatile long unnumberedFull;

    /** The frame's reserved value: the report sequence of the message being offered. Held once; set before each offer. */
    private static final class SequenceValue implements ReservedValueSupplier {
        long value;

        @Override
        public long get(final DirectBuffer termBuffer, final int termOffset, final int frameLength) {
            return this.value;
        }
    }

    /** @param reportRings every account's out ring, and the gateway's own refusals */
    public GatewayEgress(
            final SessionTable sessions,
            final GatewayStatus status,
            final List<ManyToOneRingBuffer<AccountReport>> reportRings,
            final ManyToOneRingBuffer<GatewayMessage> fromControl,
            final LongSupplier nanoClock,
            final EpochNanoClock wallClock,
            final JournalRecorder journal) {
        this.sessions = sessions;
        this.status = status;
        this.reportRings = reportRings;
        this.fromControl = fromControl;
        this.nanoClock = nanoClock;
        this.wallClock = wallClock;
        this.journal = journal;
        this.statuses = new OrderStatuses();
    }

    @Override
    public int doWork() {
        this.handled = 0;
        this.fromControl.read(this.controlHandler, Integer.MAX_VALUE);
        for (int i = 0; i < this.reportRings.size(); i++) {
            this.reportRings.get(i).read(this.reportHandler, REPORTS_PER_PASS);
        }
        int work = this.handled;
        final long now = this.nanoClock.getAsLong();
        final boolean beat = now - this.nextHeartbeatNanos >= 0;
        if (beat) {
            this.nextHeartbeatNanos = now + HEARTBEAT_INTERVAL_NANOS;
            this.heartbeatSeq++;
        }
        final GatewaySession[] all = this.sessions.all();
        for (int i = 0; i < all.length; i++) {
            work += send(all[i], beat);
        }
        return work;
    }

    @Override
    public ThreadProfile threadProfile() {
        return ThreadProfile.HOT_PATH;
    }

    @Override
    public String roleName() {
        return "gateway-egress";
    }

    /** Reports for a slot no session holds, dropped. */
    public long unrouted() {
        return this.unrouted;
    }

    /** Times a session's next report had already left its replay ring, so it was sent its orders' statuses instead. */
    public long replayGaps() {
        return this.replayGaps;
    }

    /** Control messages dropped because a session's unnumbered queue was full. */
    public long unnumberedFull() {
        return this.unnumberedFull;
    }

    /** The newest gateway heartbeat sequence sent. */
    public long heartbeatSeq() {
        return this.heartbeatSeq;
    }

    private void onReport(final AccountReport report) {
        this.handled++;
        final GatewaySession session = this.sessions.bySlot(report.sessionSlot);
        if (session == null) {
            this.unrouted = this.unrouted + 1;
            return;
        }
        final long sequence = session.replay.append(
                report.report.buffer, 0, report.report.totalMessageSize(), report.report.getClientOidCounter());
        session.totals.report(report.report, sequence);
        this.journal.record(session.slot, report.report.buffer, 0, report.report.totalMessageSize());
    }

    private void onControl(final GatewayMessage message) {
        this.handled++;
        final GatewaySession session = this.sessions.bySlot(message.slot);
        if (session == null) {
            this.unrouted = this.unrouted + 1;
            return;
        }
        if (message.kind == GatewayMessage.NUMBERED) {
            final ReplayRing replay = session.replay;
            if (SbeMessage.templateId(message.buffer, 0) != OrderStatusEncoder.TEMPLATE_ID) {
                replay.append(message.buffer, 0, message.length, 0);
            } else {
                this.orderStatusDecoder.wrap(
                        message.buffer,
                        SbeMessage.HEADER_LENGTH,
                        OrderStatusDecoder.BLOCK_LENGTH,
                        OrderStatusDecoder.SCHEMA_VERSION);
                final long sequence =
                        replay.append(message.buffer, 0, message.length, this.orderStatusDecoder.clientOidCounter());
                this.orderStatus
                        .wrap(replay.buffer(), replay.offset(sequence) + SbeMessage.HEADER_LENGTH)
                        .reportSeq(sequence);
                session.totals.status(this.orderStatusDecoder, sequence);
            }
            final long sequence = replay.newest();
            this.journal.record(session.slot, replay.buffer(), replay.offset(sequence), replay.length(sequence));
            return;
        }
        this.journal.record(session.slot, message.buffer, 0, message.length);
        if (message.reply != null) {
            session.reply = message.reply;
            message.reply = null;
        }
        if (message.kind == GatewayMessage.RESYNC) {
            session.unnumbered.clear();
            if (!session.unnumbered.offer(message.buffer, 0, message.length)) {
                this.unnumberedFull = this.unnumberedFull + 1;
            }
            session.sentSeq = session.replay.newest();
            this.statuses.summarise(session, this.journal);
            return;
        }
        if (message.resumeAfter >= 0) {
            // A new link: whatever waited for the old one is stale, and the replay starts after what the session has.
            session.unnumbered.clear();
            session.sentSeq = Math.min(message.resumeAfter, session.replay.newest());
        }
        if (!session.unnumbered.offer(message.buffer, 0, message.length)) {
            this.unnumberedFull = this.unnumberedFull + 1;
        }
    }

    /** @return how many messages went out */
    private int send(final GatewaySession session, final boolean beat) {
        final Publication reply = session.reply;
        if (reply == null) {
            return 0;
        }
        int sent = 0;
        final PendingMessages unnumbered = session.unnumbered;
        while (!unnumbered.isEmpty()) {
            if (reply.offer(unnumbered.buffer(), unnumbered.headOffset(), unnumbered.headLength()) < 0) {
                return sent;
            }
            unnumbered.pop();
            sent++;
        }
        final ReplayRing replay = session.replay;
        while (session.sentSeq < replay.newest()) {
            final long next = session.sentSeq + 1;
            if (!replay.holds(next)) {
                // Fell past its ring: the reports it missed are gone, so it is sent where each order stands instead.
                this.replayGaps = this.replayGaps + 1;
                session.sentSeq = replay.newest();
                this.statuses.summarise(session, this.journal);
                continue;
            }
            this.reserved.value = next;
            if (reply.offer(replay.buffer(), replay.offset(next), replay.length(next), this.reserved) < 0) {
                return sent;
            }
            session.sentSeq = next;
            session.totals.sent(next, replay.counter(next));
            sent++;
        }
        if (beat && session.state == GatewaySession.ACTIVE) {
            final int length = encodeHeartbeat(session);
            if (reply.offer(this.heartbeatBuffer, 0, length) >= 0) {
                sent++;
            }
        }
        return sent;
    }

    private int encodeHeartbeat(final GatewaySession session) {
        this.heartbeat
                .wrapAndApplyHeader(this.heartbeatBuffer, 0, this.header)
                .gatewayEpoch(this.status.epoch)
                .heartbeatSeq(this.heartbeatSeq)
                .sentNanos(this.wallClock.nanoTime())
                .state(this.status.state());
        final AccountState state = session.account.state();
        this.heartbeat
                .accountsCount(1)
                .next()
                .venueAccountId(session.account.accountId())
                .state(wireState(state))
                .budgetUseBps(0)
                .queued(0);
        return MessageHeaderEncoder.ENCODED_LENGTH + this.heartbeat.encodedLength();
    }

    private static group.gnometrading.schemas.gateway.AccountState wireState(final AccountState state) {
        return switch (state) {
            case READY -> group.gnometrading.schemas.gateway.AccountState.READY;
            case CATCHING_UP -> group.gnometrading.schemas.gateway.AccountState.CATCHING_UP;
            case CONNECTING, DOWN -> group.gnometrading.schemas.gateway.AccountState.DOWN;
        };
    }
}
