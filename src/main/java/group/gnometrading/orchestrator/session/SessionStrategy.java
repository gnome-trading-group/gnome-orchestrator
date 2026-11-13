package group.gnometrading.orchestrator.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.SecurityMaster;
import group.gnometrading.oms.position.PositionView;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.Intent;
import group.gnometrading.schemas.OrderExecutionReport;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.strategies.PythonStrategyAgent;
import group.gnometrading.strategies.PythonStrategyAgent.PythonStrategyCallback;
import group.gnometrading.strategies.StrategyAgent;
import group.gnometrading.strategies.StrategyFactory;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The session's strategy, by {@code strategy.type}: {@code python} bridges to the callback gnomepy sets through
 * {@link PythonStrategyAgent#setCallback}; {@code java} builds {@code strategy.class} with its arguments.
 *
 * <p>A Java strategy run by commit is compiled by {@code start-session.sh} into the directory {@code STRATEGY_CLASSES}
 * names, outside this jar, and loaded from there through a loader whose parent is this jar's, so the runtime, OMS and
 * schema classes it uses are the session's own. Without {@code STRATEGY_CLASSES} the class comes from this jar.
 */
final class SessionStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionStrategy() {}

    static StrategyAgent create(
            Properties properties,
            int strategyId,
            SequencedRingBuffer<?> mdBuf,
            SequencedRingBuffer<OrderExecutionReport> erBuf,
            SequencedRingBuffer<Intent> intentBuf,
            PositionView positionView,
            SecurityMaster securityMaster) {
        if ("python".equals(properties.getStringProperty("strategy.type"))) {
            PythonStrategyCallback callback = PythonStrategyAgent.getCallback();
            if (callback == null) {
                throw new IllegalStateException(
                        "Python strategy callback not set. Call PythonStrategyAgent.setCallback() before Main.start().");
            }
            return PythonStrategyAgent.createWithBuffers(
                    strategyId, mdBuf, erBuf, intentBuf, positionView, securityMaster, callback);
        }
        return StrategyFactory.create(
                strategyLoader(System.getenv("STRATEGY_CLASSES")),
                properties.getStringProperty("strategy.class"),
                strategyId,
                mdBuf,
                erBuf,
                intentBuf,
                positionView,
                securityMaster,
                arguments(properties));
    }

    /** The loader for a strategy compiled into {@code classesDir}, or this jar's own when there is none. */
    static ClassLoader strategyLoader(String classesDir) {
        ClassLoader host = SessionStrategy.class.getClassLoader();
        if (classesDir == null || classesDir.isEmpty()) {
            return host;
        }
        Path dir = Path.of(classesDir);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                    "STRATEGY_CLASSES " + classesDir + " is not a directory of compiled classes");
        }
        try {
            return new URLClassLoader(new URL[] {dir.toUri().toURL()}, host);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("STRATEGY_CLASSES " + classesDir + " is not a usable path", e);
        }
    }

    /** {@code STRATEGY_ARGS_JSON} when set (structured values survive it), else the {@code strategy.args.*} keys. */
    private static Map<String, Object> arguments(Properties properties) {
        String argsJson = System.getenv("STRATEGY_ARGS_JSON");
        if (argsJson == null || argsJson.isEmpty()) {
            return new HashMap<>(properties.getPropertiesByPrefix("strategy.args."));
        }
        try {
            return MAPPER.readValue(argsJson, new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse STRATEGY_ARGS_JSON", e);
        }
    }
}
