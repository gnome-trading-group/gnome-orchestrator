package group.gnometrading.orchestrator.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.gnometrading.strategies.StrategyAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where a Java strategy's class comes from: this jar, or the directory start-session.sh compiled a commit into. */
class SessionStrategyTest {

    private static final String STRATEGY =
            """
            package compiled;

            import group.gnometrading.SecurityMaster;
            import group.gnometrading.oms.position.PositionView;
            import group.gnometrading.schemas.Intent;
            import group.gnometrading.schemas.OrderExecutionReport;
            import group.gnometrading.schemas.StreamRecord;
            import group.gnometrading.sequencer.SequencedRingBuffer;
            import group.gnometrading.strategies.StrategyAgent;

            public final class FromCommit extends StrategyAgent {
                public FromCommit(
                        int strategyId,
                        SequencedRingBuffer<?> marketDataBuffer,
                        SequencedRingBuffer<OrderExecutionReport> execReportBuffer,
                        SequencedRingBuffer<Intent> intentBuffer,
                        PositionView positionView,
                        SecurityMaster securityMaster) {
                    super(strategyId, marketDataBuffer, execReportBuffer, intentBuffer, positionView, securityMaster);
                }

                @Override
                protected void onMarketData(StreamRecord data) {}

                @Override
                protected void onExecutionReport(OrderExecutionReport report) {}
            }
            """;

    @Test
    void withoutCompiledClassesTheStrategyComesFromThisJar() {
        assertSame(SessionStrategy.class.getClassLoader(), SessionStrategy.strategyLoader(null));
        assertSame(SessionStrategy.class.getClassLoader(), SessionStrategy.strategyLoader(""));
    }

    @Test
    void aCommitsCompiledClassesLoadOverThisJarsRuntime(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("src/compiled/FromCommit.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, STRATEGY);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        int status = ToolProvider.getSystemJavaCompiler()
                .run(
                        null,
                        null,
                        null,
                        "-parameters",
                        "-cp",
                        System.getProperty("java.class.path"),
                        "-d",
                        classes.toString(),
                        source.toString());
        assertEquals(0, status);

        ClassLoader loader = SessionStrategy.strategyLoader(classes.toString());
        Class<?> strategy = Class.forName("compiled.FromCommit", true, loader);

        assertSame(loader, strategy.getClassLoader());
        assertSame(StrategyAgent.class, strategy.getSuperclass(), "the session's own runtime, not a copy");
    }

    @Test
    void aMissingClassesDirectoryIsAClearError(@TempDir Path dir) {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> SessionStrategy.strategyLoader(dir.resolve("nope").toString()));
        assertEquals(true, error.getMessage().contains("not a directory"), error.getMessage());
    }
}
