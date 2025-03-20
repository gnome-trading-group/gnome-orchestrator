package group.gnometrading.utils;

import org.agrona.concurrent.AgentRunner;

public class AgentUtils {

    public static Thread startRunnerWithShutdownProtection(final AgentRunner runner) {
        final Thread thread = new Thread(runner);
        thread.setName(runner.agent().roleName());

        thread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            runner.close();
            try {
                thread.join();
            } catch (InterruptedException ignored) {}
        }));
        return thread;
    }

}
