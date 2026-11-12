package group.gnometrading.orchestrator.session.rewards;

import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.schemas.ExecType;
import group.gnometrading.schemas.Side;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The rewards agent's poll loop allocates nothing once warm: books, orders, modifies, fills and cancels. */
@Tag("allocation")
class RewardsAgentAllocationTest {

    private static final long SECOND = 1_000_000_000L;
    private static final int ROUNDS = 2_000;

    @Test
    void scoringTheSessionsRingsAllocatesNothing() throws Exception {
        final RewardsAgentTest session = new RewardsAgentTest();
        churn(session, 0, ROUNDS);

        final com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long thread = Thread.currentThread().getId();
        final long before = threads.getThreadAllocatedBytes(thread);
        final int events = churn(session, ROUNDS, ROUNDS);
        final long allocated = threads.getThreadAllocatedBytes(thread) - before;

        System.out.println("#### rewards agent allocated " + allocated + "B over " + events + " events");
        assertTrue(allocated < 16_384, "the rewards agent allocated " + allocated + " bytes");
    }

    /** Publishes rounds of a book, a quote, a modify, a partial fill and a cancel, polling after each round. */
    private static int churn(final RewardsAgentTest session, final int from, final int rounds) throws Exception {
        int events = 0;
        for (int i = from; i < from + rounds; i++) {
            final long at = RewardsAgentTest.T0 + i * SECOND;
            final long oid = i % 8 + 1;
            session.publishBook(at);
            session.publishOrder(oid, Side.Bid, 51, 500);
            session.publishReport(oid, ExecType.NEW, 500, at);
            session.publishModify(oid, 50, 500);
            session.publishReport(oid, ExecType.PARTIAL_FILL, 250, at);
            session.publishReport(oid, ExecType.CANCEL, 0, at);
            events += session.agent().pollRings();
        }
        return events;
    }
}
