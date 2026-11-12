package vigil.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-34: the Approval page from the request's snapshot (DC-S05-03, DM-V-12). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record ApprovalPage(
    String approvalRequestId,
    long sequenceInRun,
    Incident incident,
    String state,
    String refusalReason,
    String snapshotHash,
    String actionName,
    List<ParameterValue> parameters,
    Target target,
    List<String> limits,
    long attemptNumber,
    long attemptLimit,
    String expiresAt,
    BasedOn basedOn,
    List<Evidence> evidence,
    RecoveryChecks recoveryChecks,
    IncidentPage.Acknowledged decided,
    boolean mayAct,
    Refusal refusal) {

  /** The request's incident with its number and title. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Incident(String incidentId, Long number, String title) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record BasedOn(
      String pastIncidentKey,
      String pastIncidentTitle,
      String recordLabel,
      String score,
      long successCount) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Evidence(String evidenceEntryId, String kind, String summary, String collectedAt) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record RecoveryChecks(String name, List<String> conditions, long observationWindowSeconds) {}
}
