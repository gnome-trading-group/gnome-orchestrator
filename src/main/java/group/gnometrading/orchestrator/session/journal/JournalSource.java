package group.gnometrading.orchestrator.session.journal;

/**
 * Which buffer a journal record was read from. Several buffers carry the same message type (the strategy's intent and
 * the OMS's order), and replay tells them apart by this id, so ids must never be reused: 5 and 6 were the copies a
 * router made for each listing's simulated exchange, and older journals still carry them.
 */
public enum JournalSource {
    STRATEGY_INTENT(1),
    STRATEGY_EXEC_REPORT(2),
    OMS_ORDER(3),
    OMS_EXEC_REPORT(4),
    STRATEGY_MARKET_DATA(7),
    STRATEGY_METRIC(8);

    private final int id;

    JournalSource(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }
}
