package group.gnometrading.collectors;

import com.amazonaws.services.lambda.runtime.Context;
import group.gnometrading.SecurityMaster;
import group.gnometrading.collector.MarketDataAggregator;
import group.gnometrading.collector.merger.MarketDataMerger;
import group.gnometrading.collector.transformer.MarketDataTransformer;
import group.gnometrading.di.Named;
import group.gnometrading.di.OrchestratorLambda;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.logging.ConsoleLogger;
import group.gnometrading.logging.Logger;
import group.gnometrading.resources.Properties;
import group.gnometrading.shared.AwsModule;
import group.gnometrading.shared.SecurityMasterModule;
import java.time.Clock;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.SystemEpochNanoClock;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

public class AggregatorOrchestrator extends OrchestratorLambda<Object, Void>
        implements AwsModule, SecurityMasterModule {

    static {
        instanceClass = AggregatorOrchestrator.class;
    }

    @Provides
    @Named("OUTPUT_BUCKET")
    public final String provideOutputBucket(Properties properties) {
        return properties.getStringProperty("output.bucket");
    }

    @Provides
    @Named("INPUT_BUCKET")
    public final String provideInputBucket(Properties properties) {
        return properties.getStringProperty("input.bucket");
    }

    @Provides
    @Named("ARCHIVE_BUCKET")
    public final String provideArchiveBucket(Properties properties) {
        return properties.getStringProperty("archive.bucket");
    }

    @Provides
    @Named("COLLECTORS_METADATA_TABLE")
    public final String provideCollectorsMistingMetadataTable(Properties properties) {
        return properties.getStringProperty("collectors.metadata.table");
    }

    @Provides
    public final EpochNanoClock provideEpochNanoClock() {
        return new SystemEpochNanoClock();
    }

    @Provides
    @Singleton
    public final Logger provideLogger(EpochNanoClock epochClock) {
        return new ConsoleLogger(epochClock);
    }

    @Provides
    public final Clock provideClock() {
        return Clock.systemUTC();
    }

    @Provides
    public final MarketDataMerger provideMarketDataMerger(
            Logger logger,
            Clock clock,
            S3Client s3Client,
            SecurityMaster securityMaster,
            @Named("INPUT_BUCKET") String inputBucket,
            @Named("OUTPUT_BUCKET") String outputBucket,
            @Named("ARCHIVE_BUCKET") String archiveBucket) {
        return new MarketDataMerger(logger, clock, s3Client, securityMaster, inputBucket, outputBucket, archiveBucket);
    }

    @Provides
    public final MarketDataTransformer provideMarketDataTransformer(
            Logger logger,
            Clock clock,
            S3Client s3Client,
            DynamoDbClient dynamoDbClient,
            @Named("COLLECTORS_METADATA_TABLE") String collectorsMetadataTable,
            @Named("OUTPUT_BUCKET") String outputBucket) {
        return new MarketDataTransformer(
                logger, clock, s3Client, dynamoDbClient, collectorsMetadataTable, outputBucket);
    }

    @Provides
    public final MarketDataAggregator provideMarketDataAggregator(
            Logger logger, MarketDataMerger marketDataMerger, MarketDataTransformer marketDataTransformer) {
        return new MarketDataAggregator(logger, marketDataMerger, marketDataTransformer);
    }

    @Override
    protected final Void execute(Object input, Context context) {
        MarketDataAggregator marketDataAggregator = getInstance(MarketDataAggregator.class);
        marketDataAggregator.run();
        return null;
    }
}
