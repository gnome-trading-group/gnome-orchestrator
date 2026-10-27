package group.gnometrading.gateways;

/**
 * What a gateway does when it can't go on. A trading session binds its own, which reports why before exiting; a shard
 * binds one that only logs, as a connection that can't connect keeps retrying and one market's failures are its own
 * quarantine; a gateway run on its own just exits.
 */
@FunctionalInterface
public interface ProcessExit {

    ProcessExit SYSTEM = (status, cause) -> System.exit(status);

    void exit(int status, Throwable cause);
}
