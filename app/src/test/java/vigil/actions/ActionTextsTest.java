package vigil.actions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vigil.actions.ActionRecords.ExecutionState;
import vigil.actions.api.PolicyOutcome;
import vigil.actions.api.RefusalReason;
import vigil.actions.api.RequestState;
import vigil.actions.api.StateCheckOperator;
import vigil.incidents.api.IncidentState;

class ActionTextsTest {

  static final String A = "Purge archived transaction logs";
  static final String T = "Subscriber DB server";
  static final String USER = "Usman Tariq (DB Ops)";

  private static JsonNode json(String text) {
    return ActionRecords.JSON.readTree(text);
  }

  @Test
  @DisplayName("DC-TX-03 the evidence summaries and sources of approvals and executions")
  void evidence() {
    String on = A + " on " + T + ".";
    assertThat(ActionTexts.approvalSummary(RequestState.APPROVED, null, USER, A, T))
        .isEqualTo("Approved by Usman Tariq (DB Ops): " + on);
    assertThat(ActionTexts.approvalSummary(RequestState.REJECTED, null, USER, A, T))
        .isEqualTo("Rejected by Usman Tariq (DB Ops): " + on);
    assertThat(ActionTexts.approvalSummary(RequestState.EXPIRED, null, null, A, T))
        .isEqualTo("Approval expired with no decision: " + on);
    assertThat(ActionTexts.approvalSummary(RequestState.PREAUTHORIZED, null, null, A, T))
        .isEqualTo("Preauthorized by policy: " + on);
    assertThat(ActionTexts.approvalSummary(RequestState.BLOCKED, null, null, A, T))
        .isEqualTo("Blocked by policy: " + on);
    assertThat(
            ActionTexts.approvalSummary(
                RequestState.REFUSED, RefusalReason.ATTEMPT_LIMIT_REACHED, null, A, T))
        .isEqualTo("Approval refused (attempt limit reached): " + on);
    assertThat(
            ActionTexts.approvalSummary(
                RequestState.REFUSED, RefusalReason.UNRESOLVED_EXECUTION, null, A, T))
        .isEqualTo("Approval refused (an earlier execution has an unknown result): " + on);
    assertThat(ActionTexts.approvalSource(2)).isEqualTo("Approval 2 of the run");

    String head = A + " on " + T + ": ";
    assertThat(ActionTexts.executionSummary(ExecutionState.SUCCEEDED, A, T, null, null))
        .isEqualTo(head + "Succeeded.");
    assertThat(ActionTexts.executionSummary(ExecutionState.FAILED, A, T, null, null))
        .isEqualTo(head + "Failed.");
    assertThat(ActionTexts.executionSummary(ExecutionState.UNRESOLVED, A, T, null, null))
        .isEqualTo(head + "Result unknown.");
    JsonNode many =
        json(
            "[{\"name\":\"files_removed\",\"value\":49},{\"name\":\"bytes_removed\",\"value\":1},"
                + "{\"name\":\"gib_removed\",\"value\":\"12.0\"}]");
    assertThat(ActionTexts.executionSummary(ExecutionState.SUCCEEDED, A, T, many, null))
        .isEqualTo(head + "Succeeded; 49 files removed (12.0 GiB).");
    JsonNode one =
        json(
            "[{\"name\":\"files_removed\",\"value\":1},{\"name\":\"bytes_removed\",\"value\":1},"
                + "{\"name\":\"gib_removed\",\"value\":\"0.3\"}]");
    assertThat(ActionTexts.executionSummary(ExecutionState.SUCCEEDED, A, T, one, null))
        .isEqualTo(head + "Succeeded; 1 file removed (0.3 GiB).");
    assertThat(
            ActionTexts.executionSummary(ExecutionState.NOT_EXECUTED, A, T, null, "approval_valid"))
        .isEqualTo(head + "not executed, recheck 'Approval still valid' failed.");
    assertThat(ActionTexts.executionSummary(ExecutionState.NOT_EXECUTED, A, T, null, "unchanged"))
        .isEqualTo(
            head + "not executed, recheck 'Action, parameters and target unchanged' failed.");
    assertThat(
            ActionTexts.executionSummary(ExecutionState.NOT_EXECUTED, A, T, null, "incident_open"))
        .isEqualTo(head + "not executed, recheck 'Incident still open' failed.");
    assertThat(
            ActionTexts.executionSummary(ExecutionState.NOT_EXECUTED, A, T, null, "precondition"))
        .isEqualTo(head + "not executed, recheck 'Precondition' failed.");
    assertThat(ActionTexts.executionSource("lab-db-01", "purge_old_archives"))
        .isEqualTo("lab-db-01 via runner, operation purge_old_archives");
  }

  @Test
  @DisplayName("DC-TX-10 the audit summaries of vigil-actions")
  void audit() {
    String on = A + " on " + T + ".";
    assertThat(ActionTexts.policyCheckAudit(A, PolicyOutcome.APPROVAL_REQUIRED))
        .isEqualTo("Policy for " + A + ": Approval required.");
    assertThat(ActionTexts.policyCheckAudit(A, PolicyOutcome.PREAUTHORIZED))
        .isEqualTo("Policy for " + A + ": Preauthorized.");
    assertThat(ActionTexts.policyCheckAudit(A, PolicyOutcome.BLOCKED))
        .isEqualTo("Policy for " + A + ": Blocked.");
    assertThat(
            ActionTexts.approvalRequestedAudit(
                1, A, T, List.of("Database Operations", "Approvers")))
        .isEqualTo(
            "Approval 1 requested for Purge archived transaction logs on Subscriber DB server"
                + " from Approvers, Database Operations.");
    assertThat(ActionTexts.approvalDecidedAudit(1, RequestState.APPROVED, null, USER, A, T))
        .isEqualTo(
            "Approval 1: Approved by Usman Tariq (DB Ops) — Purge archived transaction logs on"
                + " Subscriber DB server.");
    assertThat(ActionTexts.approvalDecidedAudit(2, RequestState.REJECTED, null, USER, A, T))
        .isEqualTo("Approval 2: Rejected by Usman Tariq (DB Ops) — " + on);
    assertThat(ActionTexts.approvalDecidedAudit(1, RequestState.EXPIRED, null, null, A, T))
        .isEqualTo("Approval 1: Expired — " + on);
    assertThat(ActionTexts.approvalDecidedAudit(1, RequestState.PREAUTHORIZED, null, null, A, T))
        .isEqualTo("Approval 1: Preauthorized by policy — " + on);
    assertThat(ActionTexts.approvalDecidedAudit(1, RequestState.BLOCKED, null, null, A, T))
        .isEqualTo("Approval 1: Blocked by policy — " + on);
    assertThat(
            ActionTexts.approvalDecidedAudit(
                1, RequestState.REFUSED, RefusalReason.UNRESOLVED_EXECUTION, null, A, T))
        .isEqualTo("Approval 1: Refused: an earlier execution has an unknown result — " + on);
    assertThat(ActionTexts.NO_PROPOSAL).isEqualTo("No action was proposed.");
    assertThat(ActionTexts.actionExecutedAudit(A, T, 1, ExecutionState.SUCCEEDED))
        .isEqualTo(A + " on " + T + ", attempt 1: Succeeded.");
    assertThat(ActionTexts.actionExecutedAudit(A, T, 2, ExecutionState.NOT_EXECUTED))
        .isEqualTo(A + " on " + T + ", attempt 2: Not executed: a recheck failed.");
    assertThat(ActionTexts.actionReconciledAudit(A, T, 1, ExecutionState.SUCCEEDED))
        .isEqualTo(A + " on " + T + ", attempt 1: result checked, Succeeded.");
    assertThat(ActionTexts.actionReconciledAudit(A, T, 1, ExecutionState.FAILED))
        .isEqualTo(A + " on " + T + ", attempt 1: result checked, Failed.");
    assertThat(ActionTexts.actionReconciledAudit(A, T, 1, ExecutionState.UNRESOLVED))
        .isEqualTo(A + " on " + T + ", attempt 1: result checked, Result unknown.");
  }

  @Test
  @DisplayName("DC-TX-01 the state texts, recheck names and recheck details")
  void namesAndDetails() {
    assertThat(ActionTexts.requestState(RequestState.PENDING, null))
        .isEqualTo("Waiting for approval");
    assertThat(ActionTexts.requestState(RequestState.APPROVED, null)).isEqualTo("Approved");
    assertThat(ActionTexts.requestState(RequestState.REJECTED, null)).isEqualTo("Rejected");
    assertThat(ActionTexts.requestState(RequestState.EXPIRED, null)).isEqualTo("Expired");
    assertThat(ActionTexts.requestState(RequestState.PREAUTHORIZED, null))
        .isEqualTo("Preauthorized by policy");
    assertThat(ActionTexts.requestState(RequestState.BLOCKED, null)).isEqualTo("Blocked by policy");
    assertThat(ActionTexts.requestState(RequestState.REFUSED, RefusalReason.ATTEMPT_LIMIT_REACHED))
        .isEqualTo("Refused: attempt limit reached");
    assertThat(ActionTexts.requestState(RequestState.REFUSED, RefusalReason.UNRESOLVED_EXECUTION))
        .isEqualTo("Refused: an earlier execution has an unknown result");
    assertThat(ActionTexts.requestState(RequestState.CANCELLED, null)).isEqualTo("Cancelled");
    assertThat(ActionTexts.policy(PolicyOutcome.APPROVAL_REQUIRED)).isEqualTo("Approval required");
    assertThat(ActionTexts.executionState(ExecutionState.RECORDED))
        .isEqualTo("Checking conditions");
    assertThat(ActionTexts.executionState(ExecutionState.RECHECKING))
        .isEqualTo("Checking conditions");
    assertThat(ActionTexts.executionState(ExecutionState.NOT_EXECUTED))
        .isEqualTo("Not executed: a recheck failed");
    assertThat(ActionTexts.executionState(ExecutionState.EXECUTING)).isEqualTo("Running");
    assertThat(ActionTexts.executionState(ExecutionState.RECONCILING))
        .isEqualTo("Checking the result");
    assertThat(ActionTexts.recheckName("approval_valid")).isEqualTo("Approval still valid");
    assertThat(ActionTexts.recheckName("unchanged"))
        .isEqualTo("Action, parameters and target unchanged");
    assertThat(ActionTexts.recheckName("incident_open")).isEqualTo("Incident still open");
    assertThat(ActionTexts.recheckName("precondition")).isEqualTo("Precondition");

    Instant until = Instant.parse("2026-11-12T09:15:00Z");
    assertThat(ActionTexts.approvalValid(List.of(USER), until))
        .isEqualTo("approved by Usman Tariq (DB Ops); valid until 2026-11-12 09:15:00 UTC");
    assertThat(ActionTexts.approvalValid(List.of("policy"), until))
        .isEqualTo("approved by policy; valid until 2026-11-12 09:15:00 UTC");
    assertThat(ActionTexts.approvalValid(List.of(USER, "Farah Naqvi (DB Ops Lead)"), until))
        .isEqualTo(
            "approved by Farah Naqvi (DB Ops Lead), Usman Tariq (DB Ops); valid until"
                + " 2026-11-12 09:15:00 UTC");
    assertThat(ActionTexts.approvalValid(List.of(), null)).isEqualTo("no approval");
    assertThat(ActionTexts.unchanged(List.of())).isEqualTo("unchanged since approval");
    assertThat(ActionTexts.unchanged(List.of("action", "hypothesis component", "target")))
        .isEqualTo("changed: action, hypothesis component, target");
    assertThat(ActionTexts.incidentState(IncidentState.OPEN)).isEqualTo("Open");
    assertThat(ActionTexts.incidentState(IncidentState.RESOLVED)).isEqualTo("Resolved");
    String operation = "Used percentage of one filesystem";
    JsonNode mount = json("{\"mount\": \"/data\"}");
    assertThat(
            ActionTexts.precondition(
                operation, mount, "100", StateCheckOperator.GREATER_OR_EQUAL, "90"))
        .isEqualTo("Used percentage of one filesystem (mount=/data): 100 ≥ 90");
    assertThat(
            ActionTexts.precondition(
                operation, mount, null, StateCheckOperator.GREATER_OR_EQUAL, "90"))
        .isEqualTo("Used percentage of one filesystem (mount=/data): no value read");
    assertThat(
            ActionTexts.precondition(
                "Op",
                json("{\"path\": \"/data\", \"count\": 3}"),
                "7",
                StateCheckOperator.LESS_THAN,
                "8"))
        .isEqualTo("Op (count=3, path=/data): 7 < 8");
    assertThat(List.of(StateCheckOperator.values()).stream().map(ActionTexts::symbol).toList())
        .containsExactly("=", "≠", ">", "≥", "<", "≤");
  }
}
