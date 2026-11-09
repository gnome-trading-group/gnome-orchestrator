package group.gnometrading.orchestrator.gateway;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.collections.buffer.ManyToOneRingBuffer;
import group.gnometrading.concurrent.GnomeAgent;
import group.gnometrading.concurrent.GnomeAgentRunner;
import group.gnometrading.gateways.account.lane.LaneCommand;
import group.gnometrading.gateways.account.reader.AccountReport;
import group.gnometrading.gateways.account.reader.AccountState;
import group.gnometrading.gateways.account.reader.PublishedMap;
import group.gnometrading.gateways.orders.recovery.VenueOrderQuery;
import group.gnometrading.logging.NullLogger;
import group.gnometrading.orchestrator.aeron.AeronChannels;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.AeronStreamIds;
import group.gnometrading.orchestrator.gateway.core.AccountLink;
import group.gnometrading.orchestrator.gateway.core.GatewayMessage;
import group.gnometrading.orchestrator.gateway.core.GatewaySession;
import group.gnometrading.orchestrator.gateway.core.GatewayStatus;
import group.gnometrading.orchestrator.gateway.core.SessionTable;
import group.gnometrading.orchestrator.gateway.journal.GatewayJournal;
import group.gnometrading.orchestrator.gateway.transport.GatewayEgress;
import group.gnometrading.orchestrator.gateway.transport.GatewayIngress;
import group.gnometrading.orchestrator.gateway.transport.GatewayRejects;
import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Order;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.schemas.Side;
import group.gnometrading.schemas.gateway.GatewayState;
import io.aeron.Publication;
import io.aeron.Subscription;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Once warm, the threads every order and report pass through must not allocate: ingress, egress, and the journal
 * reading behind them. A GC pause on any of them delays every session's orders.
 */
@Tag("allocation")
class GatewayAllocationTest {

    private static final int ORDERS = 100_000;
    private static final long SLOT = 42;
    private static final long LISTING = PublishedMap.listingKey(1, 7);

    private static final class Account implements AccountLink {
        final FakeOrderQuery query = new FakeOrderQuery();
        final ManyToOneRingBuffer<LaneCommand> commands =
                new ManyToOneRingBuffer<>(LaneCommand[]::new, LaneCommand::new, 4096);

        @Override
        public int accountId() {
            return 5;
        }

        @Override
        public ManyToOneRingBuffer<LaneCommand> commands() {
            return this.commands;
        }

        @Override
        public AccountState state() {
            return AccountState.READY;
        }

        @Override
        public int workingOrders() {
            return 0;
        }

        @Override
        public VenueOrderQuery orderQuery() {
            return this.query;
        }

        @Override
        public AccountLink.Markets markets() {
            return (listing, fees) -> {};
        }
    }

    /** A lane that answers each command with a report, as the account's reader would. */
    private static final class AnsweringLane implements GnomeAgent {
        private final ManyToOneRingBuffer<LaneCommand> commands;
        private final ManyToOneRingBuffer<AccountReport> reports;
        private final OrderExecutionReport report = new OrderExecutionReport();

        AnsweringLane(
                final ManyToOneRingBuffer<LaneCommand> commands, final ManyToOneRingBuffer<AccountReport> reports) {
            this.commands = commands;
            this.reports = reports;
            this.report.encoder.exchangeId(1).securityId(7).execType(ExecType.NEW);
        }

        @Override
        public int doWork() {
            this.commands.read(
                    command -> {
                        int index;
                        while ((index = this.reports.tryClaim()) < 0) {
                            Thread.onSpinWait();
                        }
                        final AccountReport entry = this.reports.indexAt(index);
                        entry.sessionSlot = command.sessionSlot;
                        this.report.encodeClientOid(command.clientOidCounter, command.clientOidStrategyId);
                        entry.report.buffer.putBytes(0, this.report.buffer, 0, this.report.totalMessageSize());
                        this.reports.commit(index);
                    },
                    256);
            return 0;
        }
    }

    /** Runs {@code agent}, noting the thread it runs on. */
    private static GnomeAgentRunner run(final GnomeAgent agent, final AtomicLong threadId) {
        final GnomeAgentRunner runner = new GnomeAgentRunner(
                new GnomeAgent() {
                    @Override
                    public int doWork() throws Exception {
                        threadId.compareAndSet(0, Thread.currentThread().getId());
                        return agent.doWork();
                    }
                },
                Throwable::printStackTrace);
        GnomeAgentRunner.startOnThread(runner);
        return runner;
    }

    @Test
    @Timeout(180)
    void ingressEgressAndTheJournalDoNotAllocateOnceWarm() throws Exception {
        final Path journalDirectory = Files.createTempDirectory("gateway-journal");
        try (AeronRuntime gatewayAeron =
                        AeronRuntime.launch("alloc-gateway", AeronProfile.GATEWAY, Throwable::printStackTrace);
                AeronRuntime sessionAeron = AeronRuntime.launch(
                        "alloc-gateway-session", AeronProfile.GATEWAY, Throwable::printStackTrace)) {
            final Subscription ingressSubscription = gatewayAeron
                    .aeron()
                    .addSubscription(AeronChannels.gatewayIngress("localhost", 40974), AeronStreamIds.GATEWAY_INGRESS);
            final Publication toGateway = sessionAeron
                    .aeron()
                    .addPublication(AeronChannels.gatewayIngress("localhost", 40974), AeronStreamIds.GATEWAY_INGRESS);
            final Subscription replies = sessionAeron
                    .aeron()
                    .addSubscription(AeronChannels.gatewayReply("localhost", 40975), AeronStreamIds.GATEWAY_REPLY);
            final Publication replyPublication = gatewayAeron
                    .aeron()
                    .addPublication(AeronChannels.gatewayReply("localhost", 40975), AeronStreamIds.GATEWAY_REPLY);

            final Account account = new Account();
            final ManyToOneRingBuffer<AccountReport> reports =
                    new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 4096);
            final ManyToOneRingBuffer<AccountReport> rejects =
                    new ManyToOneRingBuffer<>(AccountReport[]::new, AccountReport::new, 64);
            final SessionTable sessions = new SessionTable();
            final GatewayStatus status = new GatewayStatus(1);
            status.state(GatewayState.READY);
            final GatewayJournal journal = new GatewayJournal(
                    new NullLogger(), journalDirectory, 64L << 20, Long.MAX_VALUE, System::nanoTime, 0);
            final GatewayIngress ingress = new GatewayIngress(
                    ingressSubscription,
                    sessions,
                    status,
                    new GatewayRejects(rejects, new SystemEpochNanoClock()),
                    new ManyToOneRingBuffer<>(GatewayMessage[]::new, GatewayMessage::new, 16),
                    System::nanoTime,
                    journal.ingress());
            final GatewayEgress egress = new GatewayEgress(
                    sessions,
                    status,
                    List.of(reports, rejects),
                    new ManyToOneRingBuffer<>(GatewayMessage[]::new, GatewayMessage::new, 16),
                    System::nanoTime,
                    new SystemEpochNanoClock(),
                    journal.egress());

            final GatewaySession session = new GatewaySession(SLOT, "s", 3, account, 8192);
            final LongHashSet leases = new LongHashSet();
            leases.add(LISTING);
            session.leases = leases;
            session.state = GatewaySession.ACTIVE;
            sessions.add(session);
            while (!toGateway.isConnected()) {
                Thread.sleep(1);
            }
            sessions.link(session, toGateway.sessionId());
            session.reply = replyPublication;

            final AtomicLong ingressThread = new AtomicLong();
            final AtomicLong egressThread = new AtomicLong();
            final AtomicLong journalThread = new AtomicLong();
            final List<GnomeAgentRunner> runners = List.of(
                    run(ingress, ingressThread),
                    run(egress, egressThread),
                    run(journal, journalThread),
                    run(new AnsweringLane(account.commands, reports), new AtomicLong()));
            final AtomicLong received = new AtomicLong();
            try {
                sendAndAwait(toGateway, replies, received, ORDERS / 2);
                final com.sun.management.ThreadMXBean threads =
                        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                final long ingressBefore = threads.getThreadAllocatedBytes(ingressThread.get());
                final long egressBefore = threads.getThreadAllocatedBytes(egressThread.get());
                final long journalBefore = threads.getThreadAllocatedBytes(journalThread.get());
                final long lappedBefore = journal.lapped();
                sendAndAwait(toGateway, replies, received, ORDERS);
                final long ingressAllocated = threads.getThreadAllocatedBytes(ingressThread.get()) - ingressBefore;
                final long egressAllocated = threads.getThreadAllocatedBytes(egressThread.get()) - egressBefore;
                final long journalAllocated = threads.getThreadAllocatedBytes(journalThread.get()) - journalBefore;
                System.out.printf(
                        "#### over %d orders: ingress %d B, egress %d B, journal %d B, lapped %d%n",
                        ORDERS / 2,
                        ingressAllocated,
                        egressAllocated,
                        journalAllocated,
                        journal.lapped() - lappedBefore);
                assertTrue(ingressAllocated < 16_384, "ingress allocated " + ingressAllocated + " bytes");
                assertTrue(egressAllocated < 16_384, "egress allocated " + egressAllocated + " bytes");
                // A lap is records lost, logged as it happens; that error path may allocate. This test sends far faster
                // than any gateway sees, on a host shared with every other thread, so the journal can fall behind.
                if (journal.lapped() == lappedBefore) {
                    assertTrue(journalAllocated < 16_384, "the journal allocated " + journalAllocated + " bytes");
                }
            } finally {
                for (final GnomeAgentRunner runner : runners) {
                    runner.close();
                }
                journal.close();
            }
        }
    }

    /** Sends orders until {@code upTo} reports have come back, a window at a time so no ring overflows. */
    private static void sendAndAwait(
            final Publication toGateway, final Subscription replies, final AtomicLong received, final long upTo)
            throws InterruptedException {
        final Order order = new Order();
        order.encoder.exchangeId(1).securityId(7).price(1).size(1).side(Side.Bid);
        long sent = received.get();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (received.get() < upTo) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("only " + received.get() + " reports came back");
            }
            if (sent < upTo && sent - received.get() < 1024) {
                order.encodeClientOid(sent + 1, 3);
                if (toGateway.offer(order.buffer, 0, order.totalMessageSize()) > 0) {
                    sent++;
                }
            }
            replies.poll(
                    (buffer, offset, length, header) -> {
                        if (header.reservedValue() > 0) {
                            received.incrementAndGet();
                        }
                    },
                    64);
        }
    }
}
