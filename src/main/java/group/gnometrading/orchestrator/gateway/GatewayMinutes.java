package group.gnometrading.orchestrator.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.orchestrator.gateway.accounts.LaneAdmissions;
import group.gnometrading.orchestrator.gateway.simulated.SimulatedAccount;
import group.gnometrading.orchestrator.runtime.ClockReadings;
import group.gnometrading.utils.CauseChain;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongFunction;

/**
 * The gateway's recent minutes, as one JSON document the controller reads: per account, what its lane sent and how long
 * requests waited, what the venue refused or never answered, and how much each session sent in the last minute; for a
 * paper gateway, its simulated venue's books' lag. The last {@link #KEPT} minutes are kept, and the document is written
 * after each minute, from one side thread. Allocates freely.
 */
final class GatewayMinutes {

    /** Minutes of history kept in the document. */
    static final int KEPT = 60;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger logger;
    private final String gatewayKey;
    private final String mode;
    private final Consumer<String> writer;
    private final ClockReadings clock;
    private final Map<Integer, Account> accounts = new LinkedHashMap<>();
    private final ArrayDeque<ObjectNode> paperMinutes = new ArrayDeque<>();

    private static final class Account {
        final String name;
        final ArrayDeque<ObjectNode> minutes = new ArrayDeque<>();
        ArrayNode sessions = MAPPER.createArrayNode();

        Account(final String name) {
            this.name = name;
        }
    }

    /**
     * @param writer stores the document; a failure is logged and the next minute writes again
     * @param clock the host's clock, reported with each document: the venue answer times are its own, but what the
     *     gateway stamps for sessions to compare with theirs is only as good as its bound
     */
    GatewayMinutes(
            final Logger logger,
            final String gatewayKey,
            final String mode,
            final Consumer<String> writer,
            final ClockReadings clock) {
        this.logger = logger;
        this.gatewayKey = gatewayKey;
        this.mode = mode;
        this.writer = writer;
        this.clock = clock;
    }

    /**
     * One account's last full minute.
     *
     * @param endedAtMillis when it ended, epoch milliseconds
     * @param venueRefusals the venue's 429s in it
     * @param sessionIdOfSlot the session holding a slot, or null when none does now
     */
    void account(
            final int accountId,
            final String name,
            final LaneAdmissions.Minute minute,
            final long endedAtMillis,
            final long venueRefusals,
            final LongFunction<String> sessionIdOfSlot) {
        final Account account = this.accounts.computeIfAbsent(accountId, id -> new Account(name));
        final ObjectNode node = MAPPER.createObjectNode()
                .put("endedAt", endedAtMillis)
                .put("cancels", minute.cancels())
                .put("cancelWaitP99Us", minute.cancelWaitP99Micros())
                .put("cancelWaitMaxUs", minute.cancelWaitMaxMicros())
                .put("orders", minute.orders())
                .put("orderWaitP99Us", minute.orderWaitP99Micros())
                .put("orderWaitMaxUs", minute.orderWaitMaxMicros())
                .put("held", minute.held())
                .put("refusedWaiting", minute.refusedWaiting())
                .put("refusedWithoutConnection", minute.refusedWithoutConnection())
                .put("dropped", minute.dropped())
                .put("orderAnswerP50Us", minute.orderAnswerP50Micros())
                .put("orderAnswerP99Us", minute.orderAnswerP99Micros())
                .put("cancelAnswerP50Us", minute.cancelAnswerP50Micros())
                .put("cancelAnswerP99Us", minute.cancelAnswerP99Micros())
                .put("unanswered", minute.unanswered())
                .put("connectionsBusy", minute.connectionsBusy())
                .put("venueRefusals", venueRefusals);
        keep(account.minutes, node);
        final ArrayNode sessions = MAPPER.createArrayNode();
        minute.sentBySlot().entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .forEach(sent -> sessions.addObject()
                        .put("slot", sent.getKey())
                        .put("sessionId", sessionIdOfSlot.apply(sent.getKey()))
                        .put("sent", sent.getValue()));
        account.sessions = sessions;
    }

    /** A paper gateway's simulated venue over the last minute, ended at {@code endedAtMillis}. */
    void paper(final SimulatedAccount.Minute minute, final long endedAtMillis) {
        keep(
                this.paperMinutes,
                MAPPER.createObjectNode()
                        .put("endedAt", endedAtMillis)
                        .put("sessions", minute.sessions())
                        .put("workingOrders", minute.workingOrders())
                        .put("lagMaxMs", minute.lagMaxMillis())
                        .put("refusedNotReady", minute.refusedNotReady()));
    }

    /** The document as it stands. */
    String document(final long writtenAtMillis) {
        final ObjectNode root = MAPPER.createObjectNode()
                .put("gatewayKey", this.gatewayKey)
                .put("mode", this.mode)
                .put("writtenAt", writtenAtMillis);
        this.clock.sample();
        final ObjectNode clockNode =
                root.putObject("clock").put("source", this.clock.source().name());
        putOptional(clockNode, "errorBoundNanos", this.clock.errorBoundNanos());
        clockNode.put("offsetNanos", this.clock.offsetNanos());
        putOptional(clockNode, "ageMs", this.clock.ageMs());
        final ArrayNode accounts = root.putArray("accounts");
        for (final Map.Entry<Integer, Account> entry : this.accounts.entrySet()) {
            final ObjectNode account =
                    accounts.addObject().put("accountId", entry.getKey()).put("name", entry.getValue().name);
            account.putArray("minutes").addAll(List.copyOf(entry.getValue().minutes));
            account.set("sessions", entry.getValue().sessions);
        }
        if (!this.paperMinutes.isEmpty()) {
            root.putObject("paper").putArray("minutes").addAll(List.copyOf(this.paperMinutes));
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Gateway minutes couldn't be written as JSON", e);
        }
    }

    /** Writes the document; a failed write is logged, and the next minute's carries everything again. */
    void publish(final long writtenAtMillis) {
        try {
            this.writer.accept(document(writtenAtMillis));
        } catch (RuntimeException e) {
            this.logger.logf(
                    LogMessage.UNKNOWN_ERROR,
                    "Gateway %s: couldn't write its minutes: %s",
                    this.gatewayKey,
                    CauseChain.of(e));
        }
    }

    private static void putOptional(final ObjectNode node, final String name, final long value) {
        if (value == ClockReadings.UNAVAILABLE) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static void keep(final ArrayDeque<ObjectNode> minutes, final ObjectNode minute) {
        minutes.addLast(minute);
        while (minutes.size() > KEPT) {
            minutes.removeFirst();
        }
    }
}
