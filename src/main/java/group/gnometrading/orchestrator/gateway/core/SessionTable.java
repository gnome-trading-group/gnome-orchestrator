package group.gnometrading.orchestrator.gateway.core;

import group.gnometrading.gateways.account.reader.PublishedMap;
import java.util.Arrays;

/**
 * Every session the gateway serves, by slot (for routing reports out) and by the Aeron image its messages arrive on
 * (for telling who sent an order: core orders carry no session). The control thread changes it; ingress and egress
 * read published copies without locking or allocating.
 */
public final class SessionTable {

    private final PublishedMap<GatewaySession> bySlot = new PublishedMap<>();
    private final PublishedMap<GatewaySession> byImage = new PublishedMap<>();
    private volatile GatewaySession[] all = new GatewaySession[0];

    public GatewaySession bySlot(final long slot) {
        return this.bySlot.get(slot);
    }

    public GatewaySession byImage(final int image) {
        return this.byImage.get(image);
    }

    /** Every session, for egress's heartbeats; the array is never changed once published. */
    public GatewaySession[] all() {
        return this.all;
    }

    /** Control thread. */
    public synchronized void add(final GatewaySession session) {
        if (this.bySlot.get(session.slot) != null) {
            throw new IllegalStateException("Slot " + session.slot + " is already held by a session");
        }
        this.bySlot.put(session.slot, session);
        final GatewaySession[] grown = Arrays.copyOf(this.all, this.all.length + 1);
        grown[this.all.length] = session;
        this.all = grown;
    }

    /** Control thread: from now on messages on {@code image} are the session's. */
    public synchronized void link(final GatewaySession session, final int image) {
        final GatewaySession holder = this.byImage.get(image);
        if (holder != null && holder != session) {
            throw new IllegalStateException("Image " + image + " already carries session " + holder.slot);
        }
        unlink(session);
        session.image = image;
        this.byImage.put(image, session);
    }

    /** Control thread: messages on the session's image are no longer anyone's. */
    public synchronized void unlink(final GatewaySession session) {
        if (session.image != 0) {
            this.byImage.remove(session.image);
            session.image = 0;
        }
    }

    /** Control thread: the session is gone for good. */
    public synchronized void remove(final GatewaySession session) {
        unlink(session);
        this.bySlot.remove(session.slot);
        this.all = Arrays.stream(this.all).filter(s -> s != session).toArray(GatewaySession[]::new);
    }
}
