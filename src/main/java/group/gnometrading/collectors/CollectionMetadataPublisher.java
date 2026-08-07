package group.gnometrading.collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.SecurityMaster;
import group.gnometrading.sm.ContractRelationship;
import group.gnometrading.sm.Event;
import group.gnometrading.sm.EventContract;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.ListingSpec;
import group.gnometrading.sm.Security;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Publishes an immutable registry and data-contract snapshot before collection starts. */
final class CollectionMetadataPublisher {

    private static final int METADATA_VERSION = 1;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private record TimingContract(
            String eventTimeField,
            String receiveTimeField,
            String timestampUnit,
            String normalizedRecordOrdering,
            String sequencePolicy,
            String depthPolicy,
            int mbpLevels) {}

    private record SecurityMetadata(
            int securityId,
            String symbol,
            String securityType,
            String contractType,
            String assetClass,
            String baseCurrency,
            String quoteCurrency,
            String settleCurrency,
            boolean inverse,
            boolean quanto,
            long expiry,
            long strikePrice,
            boolean active,
            int underlyingSecurityId) {}

    private record ExchangeMetadata(int exchangeId, String exchangeName, String region, String schemaType) {}

    private record ListingMetadata(
            int listingId,
            ExchangeMetadata exchange,
            SecurityMetadata security,
            String exchangeSecurityId,
            String exchangeSecuritySymbol,
            ListingSpec listingSpec,
            EventContract eventContract,
            Event event,
            String normalizedPathPrefix,
            String venueRawPathPrefix) {}

    private record MetadataSnapshot(
            int metadataVersion,
            String collectionId,
            long capturedAtNanos,
            TimingContract timingContract,
            List<ListingMetadata> listings,
            List<ContractRelationship> relationships) {}

    private final S3Client s3Client;
    private final Clock clock;
    private final String normalizedBucket;
    private final String venueRawBucket;

    CollectionMetadataPublisher(S3Client s3Client, Clock clock, String normalizedBucket, String venueRawBucket) {
        this.s3Client = s3Client;
        this.clock = clock;
        this.normalizedBucket = normalizedBucket;
        this.venueRawBucket = venueRawBucket;
    }

    CollectionContext publish(final List<Listing> listings, final SecurityMaster securityMaster) {
        final String collectionId = UUID.randomUUID().toString();
        final String key = "v1/collections/" + collectionId + "/contract-metadata.json";
        final Set<Integer> selectedSecurityIds = new HashSet<>();
        final List<ListingMetadata> listingMetadata = new ArrayList<>(listings.size());

        for (Listing listing : listings) {
            final Security security = listing.security();
            selectedSecurityIds.add(security.securityId());
            final EventContract eventContract = securityMaster.getEventContractBySecurity(security.securityId());
            final Event event = eventContract == null ? null : securityMaster.getEvent(eventContract.eventId());
            final ListingSpec listingSpec = securityMaster.getListingSpec(listing.listingId());
            final String idPrefix = "v1/exchange=%d/security=%d/listing=%d/"
                    .formatted(listing.exchange().exchangeId(), security.securityId(), listing.listingId());
            listingMetadata.add(new ListingMetadata(
                    listing.listingId(),
                    new ExchangeMetadata(
                            listing.exchange().exchangeId(),
                            listing.exchange().exchangeName(),
                            listing.exchange().region(),
                            listing.exchange().schemaType().name()),
                    new SecurityMetadata(
                            security.securityId(),
                            security.symbol(),
                            security.type().name(),
                            security.contractType().name(),
                            security.assetClass().name(),
                            security.baseCurrency(),
                            security.quoteCurrency(),
                            security.settleCurrency(),
                            security.inverse(),
                            security.isQuanto(),
                            security.expiry(),
                            security.strikePrice(),
                            security.active(),
                            security.underlyingSecurityId()),
                    listing.exchangeSecurityId(),
                    listing.exchangeSecuritySymbol(),
                    listingSpec,
                    eventContract,
                    event,
                    "%d/%d/".formatted(security.securityId(), listing.exchange().exchangeId()),
                    idPrefix));
        }

        final LinkedHashMap<Integer, ContractRelationship> relationships = new LinkedHashMap<>();
        for (ContractRelationship relationship : securityMaster.getAllContractRelationships()) {
            if (selectedSecurityIds.contains(relationship.securityIdA())
                    || selectedSecurityIds.contains(relationship.securityIdB())) {
                relationships.putIfAbsent(relationship.relationshipId(), relationship);
            }
        }

        final MetadataSnapshot snapshot = new MetadataSnapshot(
                METADATA_VERSION,
                collectionId,
                TimeUnit.MILLISECONDS.toNanos(this.clock.millis()),
                new TimingContract(
                        "timestampEvent",
                        "timestampRecv",
                        "epoch_nanoseconds",
                        "per-listing gateway order; graph replay performs an explicit cross-listing merge",
                        "venue sequence is preserved verbatim; zero means unavailable; gaps are derived only from non-zero values",
                        "MBP10 stores ten complete bid/ask levels; depth retains the venue update-depth value or its null sentinel",
                        10),
                List.copyOf(listingMetadata),
                List.copyOf(relationships.values()));

        final byte[] body;
        try {
            body = OBJECT_MAPPER.writeValueAsBytes(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize collection contract metadata", e);
        }

        put(this.normalizedBucket, key, body);
        if (!this.normalizedBucket.equals(this.venueRawBucket)) {
            put(this.venueRawBucket, key, body);
        }
        return new CollectionContext(collectionId, key);
    }

    private void put(final String bucket, final String key, final byte[] body) {
        this.s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/json")
                        .metadata(java.util.Map.of("format", "gnome-collection-metadata-v1"))
                        .build(),
                RequestBody.fromBytes(body));
    }
}
