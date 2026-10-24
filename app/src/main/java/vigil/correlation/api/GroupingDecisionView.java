package vigil.correlation.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A grouping decision (DM-E-29). {@code componentKey} is {@code null} for an unknown component;
 * {@code relatedAlertId}, {@code relation}, {@code relationPath} and {@code timeDeltaSeconds} are
 * all {@code null} for {@code created_incident}; {@code relationPath} is empty for {@code
 * same_unknown_identity}.
 */
public record GroupingDecisionView(
    UUID id,
    UUID alertId,
    long alertSequence,
    String componentKey,
    long generation,
    Decision decision,
    UUID incidentId,
    List<UUID> mergedIncidentIds,
    UUID relatedAlertId,
    GroupingRelation relation,
    List<String> relationPath,
    Long timeDeltaSeconds,
    Instant decidedAt) {}
