package group.gnometrading.collectors;

import group.gnometrading.RegistryConnection;
import group.gnometrading.SecurityMaster;
import group.gnometrading.collector.MarketUpdateCollector;
import group.gnometrading.di.Named;
import group.gnometrading.di.Orchestrator;
import group.gnometrading.di.Provides;
import group.gnometrading.di.Singleton;
import group.gnometrading.ipc.IPCManager;
import group.gnometrading.resources.Properties;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sm.Listing;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;

public abstract class DefaultCollectorOrchestrator extends Orchestrator {

    protected static final String STREAM_NAME = "collector";

    @Provides
    @Named("PROPERTIES_PATH")
    public String providePropertiesPath() {
        return System.getenv("PROPERTIES_PATH");
    }

    @Provides
    @Named("BUCKET_NAME")
    public String provideBucketName() {
        return System.getenv("BUCKET_NAME");
    }

    @Provides
    @Named("IDENTIFIER")
    public String provideIdentifier() {
        return System.getenv("IDENTIFIER");
    }

    @Provides
    @Named("AWS_PROFILE")
    public String provideAWSProfileName() {
        return System.getenv("AWS_PROFILE");
    }

    @Provides
    public SchemaType provideSchemaType() {
        return SchemaType.findById(System.getenv("SCHEMA_TYPE"));
    }

    @Provides
    @Singleton
    public Properties provideProperties(@Named("PROPERTIES_PATH") String path) throws IOException {
        return new Properties(path);
    }

    @Provides
    @Singleton
    public Aeron provideAeron() {
        final MediaDriver.Context mediaDriverCtx = new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .aeronDirectoryName("/dev/shm/aeron");

        MediaDriver driver = MediaDriver.launch(mediaDriverCtx);
        return Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
    }

    @Provides
    @Singleton
    public IPCManager provideIPCManager(Aeron aeron) {
        return new IPCManager(aeron);
    }

    @Provides
    public S3Client provideS3Client(@Named("AWS_PROFILE") String awsProfile) {
        var builder = S3Client.builder();
        if (awsProfile != null && !awsProfile.isEmpty()) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(awsProfile));
        }
        return builder.build();
    }

    @Provides
    @Singleton
    public SecurityMaster provideSecurityMaster(Properties properties) {
        return new SecurityMaster(new RegistryConnection(properties));
    }

    @Provides
    public Listing provideListing(Properties properties, SecurityMaster securityMaster) {
        int listingId = Integer.parseInt(System.getenv("LISTING_ID"));
        return securityMaster.getListing(listingId);
    }

    @Provides
    @Singleton
    public MarketUpdateCollector provideMarketUpdateCollector(
            IPCManager ipcManager,
            S3Client s3Client,
            Listing listing,
            @Named("BUCKET_NAME") String bucketName,
            @Named("IDENTIFIER") String identifier,
            SchemaType schemaType
    ) {
        return new MarketUpdateCollector(
                ipcManager,
                STREAM_NAME,
                s3Client,
                listing,
                bucketName,
                identifier,
                schemaType
        );
    }
}
