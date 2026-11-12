package vigil.api;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The answer of AR-I-27: the Response section of the latest live run (DC-S03-10). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record ResponseSection(
    Similar similar,
    Proposal proposal,
    boolean noProposal,
    List<Approval> approvals,
    List<Execution> executions) {

  /** The similar past incident (DM-E66-R2); {@code state} {@code done} or {@code unavailable}. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Similar(
      String state,
      String outcome,
      PastIncident pastIncident,
      String score,
      Long successCount,
      String minSimilarity) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record PastIncident(
      String pastIncidentKey, String title, String recordLabel, String fixDescription) {}

  /** The run's proposed action (DM-E-57). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Proposal(
      String actionName, List<ParameterValue> parameters, Target target, String policyOutcome) {}

  /** An approval request of the run (DM-E-58). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Approval(
      String approvalRequestId,
      long sequenceInRun,
      List<TeamName> eligibleTeams,
      String state,
      String refusalReason,
      IncidentPage.Acknowledged decided,
      String expiresAt) {}

  /** An action execution of the run (DM-E-60). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Execution(
      String actionExecutionId,
      long attemptNumber,
      Long attemptLimit,
      String state,
      List<Recheck> recheck,
      String resultSummary,
      String startedAt,
      String finishedAt) {}

  /** One recheck as stored (P-24 Section 6.9). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Recheck(String check, boolean passed, String detail) {}
}
