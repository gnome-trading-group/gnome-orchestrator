package group.gnometrading.orchestrator;

import group.gnometrading.orchestrator.di.Orchestrator;
import group.gnometrading.orchestrator.gateway.OrderGatewayOrchestrator;
import group.gnometrading.orchestrator.session.SessionOrchestrator;
import group.gnometrading.orchestrator.shard.ShardOrchestrator;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The one entry point everything outside this repo starts: {@code Main <role> [args]}, or {@link #start} from a JVM
 * that embeds it (gnomepy's session runner). Callers name a role, never a class, so classes can move between releases
 * without every launcher, image and script that pins a version having to follow.
 */
public final class Main {

    /** The processes this jar runs. */
    public enum Role {
        SESSION(SessionOrchestrator.class),
        SHARD(ShardOrchestrator.class),
        GATEWAY(OrderGatewayOrchestrator.class);

        private final Class<? extends Orchestrator> type;

        Role(final Class<? extends Orchestrator> type) {
            this.type = type;
        }

        /** {@code session}, {@code shard} or {@code gateway}. */
        public String argument() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        static Role of(final String argument) {
            for (final Role role : values()) {
                if (role.argument().equals(argument)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("Unknown role " + argument + "; expected one of "
                    + Arrays.stream(values()).map(Role::argument).collect(Collectors.joining(", ")));
        }

        Class<? extends Orchestrator> type() {
            return type;
        }
    }

    private Main() {}

    /** Starts {@code role} and returns it running, so the caller can close it. */
    public static Orchestrator start(final String role, final String[] args) throws Exception {
        return Orchestrator.start(Role.of(role).type(), args);
    }

    public static void main(final String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Usage: Main <role> [args]; roles: "
                    + Arrays.stream(Role.values()).map(Role::argument).collect(Collectors.joining(", ")));
        }
        start(args[0], Arrays.copyOfRange(args, 1, args.length));
    }
}
