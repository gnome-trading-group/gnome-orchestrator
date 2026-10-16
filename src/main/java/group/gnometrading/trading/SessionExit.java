package group.gnometrading.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.sm.Listing;
import group.gnometrading.strategies.ExitListener;
import group.gnometrading.strategies.ExitResult;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Ends the session once its strategy has asked to exit and been taken out of the market: says why (an INFO alert, or
 * a WARNING when it was asked to close everything and couldn't), marks the session STOPPING so the instance going
 * away reads as a stop rather than a failure, then closes the session and ends the process.
 *
 * <p>Runs on a thread of its own: closing the session closes the strategy agent whose thread reports the exit.
 */
public final class SessionExit implements ExitListener {

    static final String STOPPED_BY = "strategy";
    private static final int UPDATE_ATTEMPTS = 3;
    private static final int CONFLICT = 409;
    private static final BigDecimal SIZE_SCALE = BigDecimal.valueOf(1_000_000L);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger logger;
    private final SessionReports reports;
    private final int strategyId;
    private final String mode;
    private final Map<Integer, Listing> listings = new HashMap<>();
    private final Runnable close;
    private final IntConsumer halt;
    private final AtomicBoolean exiting = new AtomicBoolean();

    /**
     * @param reports null outside a registry session, which then only closes and exits
     * @param close closes the session's agents, cancelling whatever is still working
     * @param halt ends the process; {@code System::exit} outside tests
     */
    public SessionExit(
            final Logger logger,
            final SessionReports reports,
            final int strategyId,
            final String mode,
            final List<Listing> listings,
            final Runnable close,
            final IntConsumer halt) {
        this.logger = logger;
        this.reports = reports;
        this.strategyId = strategyId;
        this.mode = mode;
        for (Listing listing : listings) {
            this.listings.put(listing.listingId(), listing);
        }
        this.close = close;
        this.halt = halt;
    }

    @Override
    public void onExitComplete(final ExitResult result) {
        if (exiting.compareAndSet(false, true)) {
            Thread thread = new Thread(() -> exit(result), "session-exit");
            thread.setDaemon(false);
            thread.start();
        }
    }

    void exit(final ExitResult result) {
        logger.logf(
                LogMessage.DEBUG,
                "Strategy exited: %s (timed out: %s, still held: %d)",
                result.reason(),
                result.timedOut(),
                result.held().size());
        if (reports != null) {
            report(result);
        }
        close.run();
        halt.accept(0);
    }

    private void report(final ExitResult result) {
        final CompletableFuture<HttpResponse<String>> alert = reports.alert(alertBody(result));
        markStopping(result.reason());
        if (alert == null) {
            return;
        }
        try {
            alert.get(SessionReports.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.logf(LogMessage.UNKNOWN_ERROR, "Couldn't raise the exit alert: %s", e.toString());
        }
    }

    /**
     * Retried, because a session whose instance goes away while it is still RUNNING is marked FAILED and pages. A 409
     * means it is already STOPPING (an operator's stop got there first) or has ended: either way, nothing to do.
     */
    private void markStopping(final String reason) {
        final String body = MAPPER.createObjectNode()
                .put("status", "STOPPING")
                .put("stopReason", reason)
                .put("stoppedBy", STOPPED_BY)
                .set(
                        "expectedStatus",
                        MAPPER.createArrayNode()
                                .add("SUBMITTED")
                                .add("STARTING")
                                .add("RUNNING"))
                .toString();
        for (int attempt = 1; attempt <= UPDATE_ATTEMPTS; attempt++) {
            try {
                final HttpResponse<String> response =
                        reports.updateSession(body).get(SessionReports.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (response.statusCode() / 100 == 2 || response.statusCode() == CONFLICT) {
                    return;
                }
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Marking the session STOPPING failed (attempt %d): HTTP %d %s",
                        attempt,
                        response.statusCode(),
                        response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.logf(
                        LogMessage.UNKNOWN_ERROR,
                        "Marking the session STOPPING failed (attempt %d): %s",
                        attempt,
                        e.toString());
            }
        }
    }

    String alertBody(final ExitResult result) {
        final String sessionId = reports.sessionId();
        final boolean warn = result.leftPositions();
        return MAPPER.createObjectNode()
                .put("type", "SESSION_EXITED")
                .put("key", "SESSION_EXITED:" + sessionId)
                .put("severity", warn ? "WARNING" : "INFO")
                .put(
                        "title",
                        (warn
                                        ? "Session " + sessionId + " exited still holding positions: "
                                        : "Session " + sessionId + " exited: ")
                                + result.reason())
                .put("body", describe(result))
                .put("actor", STOPPED_BY)
                .put("sessionId", sessionId)
                .put("strategyId", strategyId)
                .put("mode", mode)
                .toString();
    }

    private String describe(final ExitResult result) {
        final List<String> lines = new ArrayList<>();
        lines.add("Reason: " + result.reason());
        lines.add(result.flatten() ? "Asked to close every position." : "Kept its positions.");
        if (result.timedOut()) {
            lines.add("Ran out of time before everything was done.");
        }
        if (result.held().isEmpty()) {
            lines.add("Holds nothing.");
        } else {
            lines.add("Still held:");
            for (ExitResult.HeldPosition held : result.held()) {
                lines.add("  " + label(held.listingId()) + ": " + quantity(held.netQuantity()) + why(held.why()));
            }
        }
        return String.join("\n", lines);
    }

    private static String why(final ExitResult.Why why) {
        return switch (why) {
            case KEPT -> "";
            case TOO_SMALL -> " (below the venue's minimum order, so no single order can close it)";
            case REJECTED -> " (the close was refused every time it was sent)";
            case TIMED_OUT -> " (still closing when the time ran out)";
        };
    }

    private String label(final int listingId) {
        final Listing listing = listings.get(listingId);
        return listing == null
                ? "listing " + listingId
                : listing.exchange().exchangeCode() + " " + listing.exchangeSecuritySymbol() + " (listing " + listingId
                        + ")";
    }

    private static String quantity(final long sizeUnits) {
        return BigDecimal.valueOf(sizeUnits)
                .divide(SIZE_SCALE)
                .stripTrailingZeros()
                .toPlainString();
    }
}
