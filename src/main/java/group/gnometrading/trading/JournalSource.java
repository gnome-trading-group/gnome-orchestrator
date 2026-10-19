package group.gnometrading.trading;

/**
 * Which buffer a journal record was read from. Several buffers carry the same message (the OMS's order and the
 * router's copy of it to one venue), and replay tells them apart by this id, so ids must never be reused.
 */
public enum JournalSource {
    STRATEGY_INTENT(1),
    STRATEGY_EXEC_REPORT(2),
    OMS_ORDER(3),
    OMS_EXEC_REPORT(4),
    ROUTED_ORDER(5),
    ROUTED_EXEC_REPORT(6),
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
