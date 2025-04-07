package group.gnometrading.shared;

import group.gnometrading.di.Named;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.s3.S3Client;

public interface AWSModule {

    @Provides
    @Named("AWS_PROFILE")
    default String provideAWSProfileName() {
        return System.getenv("AWS_PROFILE");
    }

    @Provides
    @Singleton
    default S3Client provideS3Client(@Named("AWS_PROFILE") String awsProfile) {
        var builder = S3Client.builder();
        if (awsProfile != null && !awsProfile.isEmpty()) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(awsProfile));
        }
        return builder.build();
    }

    @Provides
    @Singleton
    default CloudWatchClient provideCloudWatchClient(@Named("AWS_PROFILE") String awsProfile) {
        var builder = CloudWatchClient.builder();
        if (awsProfile != null && !awsProfile.isEmpty()) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(awsProfile));
        }
        return builder.build();
    }
}
