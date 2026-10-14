package group.gnometrading.trading.recovery;

/**
 * The registry says this session has already been stopped or has ended, so it must not start: an instance that booted
 * after its session was stopped mid-launch. Not a failure, so the session exits without raising an alert.
 */
public final class SessionNotRunnableException extends IllegalStateException {

    public SessionNotRunnableException(final String message) {
        super(message);
    }
}
