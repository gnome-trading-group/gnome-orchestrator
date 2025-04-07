package group.gnometrading.di;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import group.gnometrading.annotations.VisibleForTesting;

public abstract class OrchestratorLambda<I, O> extends Orchestrator implements RequestHandler<I, O> {

    @Override
    public O handleRequest(I input, Context context) {
        this.configure();
        return execute(input, context);
    }

    protected abstract O execute(I input, Context context);

    @VisibleForTesting
    public static void main(String[] args) throws Exception {
        OrchestratorLambda orchestrator = (OrchestratorLambda) instanceClass.getDeclaredConstructor().newInstance();
        orchestrator.handleRequest(null, null);
    }
}
