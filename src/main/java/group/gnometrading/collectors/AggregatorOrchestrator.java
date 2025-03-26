package group.gnometrading.collectors;

import com.amazonaws.services.lambda.runtime.Context;
import group.gnometrading.di.OrchestratorLambda;

public class AggregatorOrchestrator extends OrchestratorLambda<Void, Void> {

    @Override
    protected Void execute(Void input, Context context) {
        System.out.println("The lambda has been executed!");
        return null;
    }
    
}
