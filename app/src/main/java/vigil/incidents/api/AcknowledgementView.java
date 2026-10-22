package vigil.incidents.api;

import java.time.Instant;
import java.util.UUID;

/** An acknowledgement (DM-E-35). */
public record AcknowledgementView(
    UUID id, UUID incidentId, UUID userId, String teamKey, long episode, Instant at) {}
