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
import java.util.HashMap;
import java.util.Map;

/**
 * The session's strategy, by {@code strategy.type}: {@code python} bridges to the callback gnomepy sets through
 * {@link PythonStrategyAgent#setCallback}; {@code java} builds {@code strategy.class} with its arguments.
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
                properties.getStringProperty("strategy.class"),
                strategyId,
                mdBuf,
                erBuf,
                intentBuf,
                positionView,
                securityMaster,
                arguments(properties));
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
