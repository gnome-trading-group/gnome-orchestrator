package group.gnometrading.orchestrator.gateway.control;

import group.gnometrading.schemas.gateway.Mode;
import group.gnometrading.schemas.gateway.OrderState;
import java.util.List;

/** A session's hello and its open orders, gathered across pages by {@link HelloPages}. */
record Hello(
        long sessionSeq,
        String sessionId,
        int strategyId,
        Mode mode,
        int venueAccountId,
        int protocolVersion,
        String replyIp,
        int replyPort,
        long lastReportSeq,
        long lastReportEpoch,
        long codTimeoutNanos,
        long maxQueueNanos,
        int maxOpenOrders,
        int maxNewPerSecond,
        int pages,
        List<long[]> listings,
        long receivedAtNanos,
        int[] pagesSeen,
        List<Order> orders) {

    /** One open order as the session's hello lists it. */
    record Order(long counter, int exchangeId, long securityId, OrderState state, long size, String exchangeOrderId) {}
}
