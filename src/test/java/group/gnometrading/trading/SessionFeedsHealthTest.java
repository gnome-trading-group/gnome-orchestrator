package group.gnometrading.trading;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.gnometrading.gateways.feed.StreamOutput;
import group.gnometrading.gateways.feed.Subject;
import group.gnometrading.gateways.feed.SubjectState;
import group.gnometrading.gateways.feed.book.Projection;
import group.gnometrading.schemas.Mbp10Schema;
import group.gnometrading.sequencer.GlobalSequence;
import group.gnometrading.sequencer.SequencedRingBuffer;
import org.junit.jupiter.api.Test;

class SessionFeedsHealthTest {

    private final SequencedRingBuffer<Mbp10Schema> ring =
            new SequencedRingBuffer<>(Mbp10Schema::new, new GlobalSequence());
    private final Subject subject = new Subject("M", 16, new StreamOutput(1, Projection.IDENTITY, ring));
    private final SessionFeeds.Health health = new SessionFeeds.Health("M", subject);

    @Test
    void aMarketWaitingForItsBookIsReconnecting() {
        assertTrue(health.isReconnecting());
    }

    @Test
    void aLiveMarketIsNot() {
        assertTrue(subject.transition(SubjectState.SUBSCRIBING, SubjectState.LIVE));
        assertFalse(health.isReconnecting());
    }

    @Test
    void aMarketWithNoBookIsNotReconnecting() {
        // Connected and subscribed, just nobody quoting: a prediction market can sit like this for hours.
        assertTrue(subject.transition(SubjectState.SUBSCRIBING, SubjectState.EMPTY));
        assertFalse(health.isReconnecting());
    }

    @Test
    void aStaleMarketIs() {
        assertTrue(subject.transition(SubjectState.SUBSCRIBING, SubjectState.LIVE));
        assertTrue(subject.transition(SubjectState.LIVE, SubjectState.STALE));
        assertTrue(health.isReconnecting());
    }
}
