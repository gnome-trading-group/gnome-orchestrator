package group.gnometrading.shared;

import group.gnometrading.di.Named;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

public interface AWSModule {

    @Provides
    @Named("AWS_PROFILE")
    default String provideAWSProfileName() {
        return System.getenv("AWS_PROFILE");
    }

    @Provides
    default Region provideAWSRegion() {
        return Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
    }

    @Provides
    @Singleton
    default S3Client provideS3Client(
            @Named("AWS_PROFILE") String awsProfile, Region awsRegion
    ) {
        var builder = S3Client.builder()
                .region(awsRegion)
                .crossRegionAccessEnabled(true);
        if (awsProfile != null && !awsProfile.isEmpty()) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(awsProfile));
        }
        return builder.build();
    }

    @Provides
    @Singleton
    default DynamoDbClient provideDynamoDbClient(
            @Named("AWS_PROFILE") String awsProfile,
            Region awsRegion
    ) {
        var builder = DynamoDbClient.builder().region(awsRegion);
        if (awsProfile != null && !awsProfile.isEmpty()) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(awsProfile));
        }
        return builder.build();
    }

}
