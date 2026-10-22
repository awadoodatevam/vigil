package vigil.incidents.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An incident (DM-E-32). Lists are sorted in code point order ({@code affectedServices} by {@code
 * service_key}) and empty when empty; other empty values are {@code null}.
 */
public record IncidentView(
    UUID id,
    long sequence,
    Long number,
    String title,
    IncidentState state,
    Instant stateChangedAt,
    Instant firstAlertFiredAt,
    Instant lastAlertJoinedAt,
    Instant stableAt,
    long alertCount,
    List<String> alertTypes,
    List<String> alertSources,
    String hypothesisComponentKey,
    HypothesisCopy hypothesis,
    UUID hypothesisComputationId,
    List<AffectedServiceCopy> affectedServices,
    Severity severity,
    String ownerTeamKey,
    List<String> informedTeamKeys,
    UUID assignmentEvaluationId,
    boolean assignmentFrozen,
    long escalationLevel,
    Instant acknowledgedAt,
    UUID acknowledgedBy,
    AiStatus aiStatus,
    AiUnavailableReason aiUnavailableReason,
    Instant aiStatusChangedAt,
    UUID aiLastRequestGroupId,
    Instant resolvedAt,
    UUID resolvedByVerificationId,
    Instant closedAt,
    UUID mergedIntoIncidentId,
    long reopenCount,
    Instant createdAt,
    Instant updatedAt) {}
