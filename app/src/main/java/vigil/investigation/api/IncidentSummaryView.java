package vigil.investigation.api;

import java.time.Instant;
import java.util.UUID;

/** An incident summary (DM-E-69); {@code runId} and {@code providerKey} {@code null} when empty. */
public record IncidentSummaryView(
    UUID id,
    UUID incidentId,
    UUID runId,
    String text,
    SummarySource source,
    String providerKey,
    Instant createdAt) {}
