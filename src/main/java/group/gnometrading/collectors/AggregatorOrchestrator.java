package group.gnometrading.collectors;

import com.amazonaws.services.lambda.runtime.Context;
import group.gnometrading.collector.MarketDataAggregator;
import group.gnometrading.di.Named;
import group.gnometrading.di.OrchestratorLambda;
import group.gnometrading.di.Provides;
import group.gnometrading.logging.Logger;
import group.gnometrading.shared.AWSModule;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.s3.S3Client;

public class AggregatorOrchestrator extends OrchestratorLambda<Void, Void> implements AWSModule {

    static {
        instanceClass = AggregatorOrchestrator.class;
    }

    @Provides
    @Named("OUTPUT_BUCKET")
    public String provideOutputBucket() {
        return System.getenv("OUTPUT_BUCKET");
    }

    @Provides
    @Named("INPUT_BUCKET")
    public String provideInputBucket() {
        return System.getenv("INPUT_BUCKET");
    }

    @Provides
    public MarketDataAggregator provideMarketDataAggregator(
            Logger logger,
            S3Client s3Client,
            CloudWatchClient cloudWatchClient,
            @Named("INPUT_BUCKET") String inputBucket,
            @Named("OUTPUT_BUCKET") String outputBucket
    ) {
        return new MarketDataAggregator(logger, s3Client, cloudWatchClient, inputBucket, outputBucket);
    }

    @Override
    protected Void execute(Void input, Context context) {
        MarketDataAggregator marketDataAggregator = getInstance(MarketDataAggregator.class);
        marketDataAggregator.runAggregator();
        return null;
    }
}
