package group.gnometrading.orchestrator.session.marketdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.SecurityMaster;
import group.gnometrading.logging.LogMessage;
import group.gnometrading.logging.Logger;
import group.gnometrading.md.StreamDirectory;
import group.gnometrading.orchestrator.aeron.AeronProfile;
import group.gnometrading.orchestrator.aeron.AeronRuntime;
import group.gnometrading.orchestrator.aeron.HostAddress;
import group.gnometrading.orchestrator.aeron.MarketDataIdle;
import group.gnometrading.orchestrator.runtime.AgentRuntimeInstaller;
import group.gnometrading.orchestrator.runtime.GatewayHealth;
import group.gnometrading.resources.Properties;
import group.gnometrading.sequencer.SequencedRingBuffer;
import group.gnometrading.sm.Listing;
import group.gnometrading.utils.CauseChain;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/** Builds a session's market data from its config: the streams it reads, from the shards that publish them. */
public final class SessionMarketData {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionMarketData() {}

    /** The book streams of the session's listings, and the data-only streams it reads, which its market data names. */
    public static StreamDirectory streams(SecurityMaster securityMaster, List<Listing> listings, int[] dataStreams) {
        return StreamDirectory.of(
                securityMaster, listings.stream().mapToInt(Listing::listingId).toArray(), dataStreams);
    }

    /** {@code streams}: market-data streams the session reads but doesn't trade (JSON ids); none when absent. */
    public static int[] dataStreams(Properties properties) {
        if (!properties.hasProperty("streams")) {
            return new int[0];
        }
        try {
            return MAPPER.readValue(properties.getStringProperty("streams"), int[].class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse the streams property", e);
        }
    }

    /** Every listing's book stream and every data-only stream, read from shards into {@code buffer}. */
    public static ShardMarketData create(
            Logger logger,
            Properties properties,
            List<Listing> listings,
            StreamDirectory streams,
            int[] dataStreams,
            SequencedRingBuffer<?> buffer) {
        List<StreamFeed> feeds = new ArrayList<>();
        List<GatewayHealth> health = new ArrayList<>();
        for (Listing listing : listings) {
            StreamFeed feed =
                    new StreamFeed(streams.streamId(listing.listingId()), "market data " + listing.listingId());
            feeds.add(feed);
            health.add(feed);
        }
        for (int streamId : dataStreams) {
            feeds.add(new StreamFeed(streamId, "stream " + streamId));
        }
        AeronRuntime aeron = AeronRuntime.launch(
                "session",
                AgentRuntimeInstaller.pinsHotAgents(properties) ? AeronProfile.LATENCY : AeronProfile.STANDARD,
                MarketDataIdle.from(properties),
                error -> logger.logf(LogMessage.UNKNOWN_ERROR, "Aeron error: %s", CauseChain.of(error)));
        long startTimeout = properties.getLongProperty("market.data.start.timeout.seconds");
        return new ShardMarketData(
                logger,
                aeron,
                HostAddress.privateIp(),
                leaseSource(properties, feeds),
                feeds,
                health,
                new ShardFeedAgent(buffer, feeds, System::nanoTime),
                TimeUnit.SECONDS.toNanos(startTimeout),
                System::nanoTime);
    }

    /**
     * Where the session's streams are published: its lease from the market-data API (the launcher leased them), or for
     * a local run, {@code market.data.local.shard} ({@code host:port}), one shard publishing them all.
     */
    private static ShardMarketData.LeaseSource leaseSource(Properties properties, List<StreamFeed> feeds) {
        if (properties.hasProperty("session.id") && properties.hasProperty("market.data.api.url")) {
            String sessionId = properties.getStringProperty("session.id");
            MarketDataLeases client = new MarketDataLeases(
                    HttpClient.newHttpClient(),
                    URI.create(properties.getStringProperty("market.data.api.url")),
                    marketDataApiKey(properties.getStringProperty("market.data.api.key.secret")),
                    MAPPER);
            return () -> client.get(sessionId);
        }
        if (properties.hasProperty("market.data.local.shard")) {
            MarketDataLeases.Lease lease = MarketDataLeases.local(
                    properties.getStringProperty("market.data.local.shard"),
                    feeds.stream().mapToInt(feed -> feed.streamId).toArray());
            return () -> lease;
        }
        throw new IllegalArgumentException("The session's market data comes from shards: it needs a lease (session.id"
                + " and market.data.api.url, set by the launcher) or market.data.local.shard for a local run");
    }

    /** The market-data API key from its Secrets Manager copy, which lives in us-east-1 only; read again after a rotation. */
    private static MarketDataLeases.ApiKey marketDataApiKey(String secretName) {
        AtomicReference<String> key = new AtomicReference<>();
        return new MarketDataLeases.ApiKey() {
            @Override
            public String get() {
                String known = key.get();
                if (known == null) {
                    try (SecretsManagerClient secrets = SecretsManagerClient.builder()
                            .region(Region.US_EAST_1)
                            .build()) {
                        known = secrets.getSecretValue(request -> request.secretId(secretName))
                                .secretString();
                    }
                    key.set(known);
                }
                return known;
            }

            @Override
            public void invalidate() {
                key.set(null);
            }
        };
    }
}
