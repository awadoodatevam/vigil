package vigil.incidents;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vigil.incidents.api.AffectedServiceCopy;
import vigil.incidents.api.AiStatus;
import vigil.incidents.api.AiUnavailableReason;
import vigil.incidents.api.HypothesisCopy;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.incidents.api.Severity;

/** An incident row as a request reads, changes and writes it back (DM-E-32). */
final class IncidentRow {

  UUID id;
  long sequence;
  Long number;
  String title;
  IncidentState state;
  Instant stateChangedAt;
  Instant firstAlertFiredAt;
  Instant lastAlertJoinedAt;
  Instant stableAt;
  long alertCount;
  List<String> alertTypes = List.of();
  List<String> alertSources = List.of();
  HypothesisCopy hypothesis;
  UUID hypothesisComputationId;
  List<AffectedServiceCopy> affectedServices = List.of();
  Severity severity;
  String ownerTeamKey;
  List<String> informedTeamKeys = List.of();
  UUID assignmentEvaluationId;
  boolean assignmentFrozen;
  long escalationLevel;
  Instant acknowledgedAt;
  UUID acknowledgedBy;
  AiStatus aiStatus;
  AiUnavailableReason aiUnavailableReason;
  Instant aiStatusChangedAt;
  UUID aiLastRequestGroupId;
  Instant resolvedAt;
  UUID resolvedByVerificationId;
  Instant closedAt;
  UUID mergedIntoIncidentId;
  long reopenCount;
  Instant createdAt;
  Instant updatedAt;

  /** The current episode (DM-E-32 {@code reopen_count}). */
  long episode() {
    return reopenCount + 1;
  }

  IncidentView view() {
    return new IncidentView(
        id,
        sequence,
        number,
        title,
        state,
        stateChangedAt,
        firstAlertFiredAt,
        lastAlertJoinedAt,
        stableAt,
        alertCount,
        alertTypes,
        alertSources,
        hypothesis == null ? null : hypothesis.componentKey(),
        hypothesis,
        hypothesisComputationId,
        affectedServices,
        severity,
        ownerTeamKey,
        informedTeamKeys,
        assignmentEvaluationId,
        assignmentFrozen,
        escalationLevel,
        acknowledgedAt,
        acknowledgedBy,
        aiStatus,
        aiUnavailableReason,
        aiStatusChangedAt,
        aiLastRequestGroupId,
        resolvedAt,
        resolvedByVerificationId,
        closedAt,
        mergedIntoIncidentId,
        reopenCount,
        createdAt,
        updatedAt);
  }
}
