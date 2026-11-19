package group.gnometrading.orchestrator.gateway.bench;

import java.util.Map;

/** A bench stand-in for a venue: what it answers, and what it saw, for the bench's report. */
public interface BenchVenue extends TlsVenueServer.Model {

    Map<String, Long> counts();

    void close();
}
