package vigil.incidents.api;

import java.time.Instant;
import java.util.UUID;

/** An evidence entry (DM-E-38); {@code contentJson} is the stored JSON object as text. */
public record EvidenceView(
    UUID id,
    UUID incidentId,
    EvidenceKind kind,
    String producerModule,
    UUID producerRecordId,
    String sourceSystem,
    Instant collectedAt,
    String summary,
    String contentJson,
    DataCategory dataCategory) {}
