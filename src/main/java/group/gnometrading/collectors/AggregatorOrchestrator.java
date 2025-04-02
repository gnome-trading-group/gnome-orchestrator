package group.gnometrading.collectors;

import com.amazonaws.services.lambda.runtime.Context;
import group.gnometrading.di.OrchestratorLambda;
import group.gnometrading.shared.AWSModule;

public class AggregatorOrchestrator extends OrchestratorLambda<Void, Void> implements AWSModule {

    static {
        instanceClass = AggregatorOrchestrator.class;
    }

    @Override
    protected Void execute(Void input, Context context) {
        System.out.println("The lambda has been executed!");
        return null;
    }
}
