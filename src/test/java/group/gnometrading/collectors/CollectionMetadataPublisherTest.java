package group.gnometrading.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.gnometrading.SecurityMaster;
import group.gnometrading.schemas.SchemaType;
import group.gnometrading.sm.AssetClass;
import group.gnometrading.sm.ContractRelationship;
import group.gnometrading.sm.ContractType;
import group.gnometrading.sm.Event;
import group.gnometrading.sm.EventContract;
import group.gnometrading.sm.Exchange;
import group.gnometrading.sm.Listing;
import group.gnometrading.sm.ListingSpec;
import group.gnometrading.sm.Security;
import group.gnometrading.sm.SecurityType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class CollectionMetadataPublisherTest {

    @Test
    void publishesTimingListingsContractsAndGraphEdgesToBothBuckets() throws Exception {
        final S3Client s3Client = mock(S3Client.class);
        final SecurityMaster securityMaster = mock(SecurityMaster.class);
        final Listing listing = listing();
        final List<StoredObject> objects = new ArrayList<>();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    final PutObjectRequest request = invocation.getArgument(0);
                    final RequestBody body = invocation.getArgument(1);
                    objects.add(new StoredObject(
                            request, body.contentStreamProvider().newStream().readAllBytes()));
                    return PutObjectResponse.builder().build();
                });
        when(securityMaster.getListingSpec(532)).thenReturn(new ListingSpec(532, 1L, 2L, 3L, 4L));
        when(securityMaster.getEventContractBySecurity(499)).thenReturn(new EventContract(8, 9, 499, "Yes"));
        when(securityMaster.getEvent(9)).thenReturn(new Event(9, "Event", "Description", "category", false, 0L, 5L));
        when(securityMaster.getAllContractRelationships()).thenReturn(new ContractRelationship[] {
            new ContractRelationship(1, 499, 500, "COMPLEMENT", 1.0F, "registry"),
            new ContractRelationship(2, 600, 700, "UNRELATED", 1.0F, "registry")
        });

        final CollectionContext context = new CollectionMetadataPublisher(
                        s3Client,
                        Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC),
                        "normalized",
                        "venue-raw")
                .publish(List.of(listing), securityMaster);

        assertEquals(2, objects.size());
        assertTrue(context.contractMetadataKey().endsWith("/contract-metadata.json"));
        assertEquals(
                List.of("normalized", "venue-raw"),
                objects.stream().map(o -> o.request.bucket()).toList());
        final JsonNode metadata = new ObjectMapper().readTree(objects.get(0).body);
        assertEquals(context.collectionId(), metadata.get("collectionId").asText());
        assertEquals(
                "timestampEvent", metadata.at("/timingContract/eventTimeField").asText());
        assertEquals(
                "timestampRecv", metadata.at("/timingContract/receiveTimeField").asText());
        assertEquals(10, metadata.at("/timingContract/mbpLevels").asInt());
        assertEquals(532, metadata.at("/listings/0/listingId").asInt());
        assertEquals(
                "Yes", metadata.at("/listings/0/eventContract/outcomeLabel").asText());
        assertEquals(1, metadata.get("relationships").size());
        assertEquals(
                "COMPLEMENT", metadata.at("/relationships/0/relationshipType").asText());
    }

    private static Listing listing() {
        return new Listing(
                532,
                new Exchange(151, "Polymarket", "global", SchemaType.MBP_10),
                new Security(
                        499,
                        "TEST",
                        SecurityType.EVENT_CONTRACT,
                        ContractType.BINARY,
                        AssetClass.PREDICTION,
                        "",
                        "USD",
                        "USD",
                        false,
                        false,
                        0L,
                        0L,
                        true,
                        0),
                "condition:token",
                "TEST-YES");
    }

    private record StoredObject(PutObjectRequest request, byte[] body) {}
}
