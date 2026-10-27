package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.logging.NullLogger;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MarketDataCollectorAgentTest {

    private final StreamTestKit kit = new StreamTestKit();
    private final AtomicLong now = new AtomicLong(1_000);
    private final MarketDataCollectorAgent agent = new MarketDataCollectorAgent(new NullLogger(), now::get);

    @Test
    void drainsEveryStreamInOnePass() throws Exception {
        ShardStream first = kit.stream(1);
        ShardStream second = kit.stream(2);
        agent.add(first);
        agent.add(second);

        StreamTestKit.publish(first, 2);
        StreamTestKit.publish(second, 1);

        assertEquals(3, agent.doWork());
        assertEquals(0, agent.doWork(), "idle, so the agent can sleep");
        assertEquals(2, first.records());
    }

    @Test
    void aRetiredStreamIsDrainedThenFlushedAfterTheGrace() throws Exception {
        ShardStream stream = kit.stream(1);
        agent.add(stream);
        agent.doWork();
        CountDownLatch done = new CountDownLatch(1);
        agent.retire(stream, done);

        StreamTestKit.publish(stream, 3);
        assertEquals(3, agent.doWork(), "what its reader was publishing as it left is kept");
        now.addAndGet(MarketDataCollectorAgent.RETIRE_GRACE_NANOS);
        agent.doWork();

        assertEquals(0, done.getCount());
        assertEquals(List.of(StreamTestKit.MARKET_BUCKET), kit.uploads());
    }

    @Test
    void aStreamOutsideItsWriteWindowRecordsButUploadsNothing() throws Exception {
        ShardStream stream = kit.stream(1);
        stream.writeWindow(Long.MAX_VALUE, Long.MAX_VALUE);
        agent.add(stream);
        StreamTestKit.publish(stream, 3);
        assertEquals(3, agent.doWork(), "a warming stream still drains its ring");
        agent.retire(stream, null);
        now.addAndGet(MarketDataCollectorAgent.RETIRE_GRACE_NANOS);
        agent.doWork();

        assertEquals(List.of(), kit.uploads());
    }

    @Test
    void aFailedCollectorStopsRecordingButNeverHoldsItsRingBack() throws Exception {
        ShardStream stream = kit.stream(1, (sequence, templateId, buffer, length) -> {
            throw new IllegalStateException("bad record");
        });
        agent.add(stream);

        // Several rings' worth: a reader blocked on a full ring would hang here.
        for (int i = 0; i < 10; i++) {
            StreamTestKit.publish(stream, 64);
            agent.doWork();
        }

        assertNotNull(stream.failure());
        assertEquals(1_000, stream.failedAtNanos());
        assertEquals(0, stream.records());
        assertTrue(stream.failure().getMessage().contains("bad record"));
    }
}
