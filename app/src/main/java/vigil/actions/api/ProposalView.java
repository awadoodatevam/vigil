package vigil.actions.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An action proposal (DM-E-57); {@code actionSnapshotJson} is the action snapshot. */
public record ProposalView(
    UUID id,
    UUID incidentId,
    UUID runId,
    String actionKey,
    String actionSnapshotJson,
    String componentKey,
    String targetKey,
    UUID similarityResultId,
    String pastIncidentKey,
    BigDecimal score,
    long successCount,
    Instant createdAt) {}
