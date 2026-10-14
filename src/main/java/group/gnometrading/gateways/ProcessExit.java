package group.gnometrading.gateways;

/**
 * Ends the process when a gateway can't go on. A trading session binds its own, which reports why before exiting; a
 * collector or a gateway run on its own just exits.
 */
@FunctionalInterface
public interface ProcessExit {

    ProcessExit SYSTEM = (status, cause) -> System.exit(status);

    void exit(int status, Throwable cause);
}
