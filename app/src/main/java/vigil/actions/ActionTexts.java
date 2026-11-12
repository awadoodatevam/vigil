package vigil.actions;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import vigil.actions.ActionRecords.ExecutionState;
import vigil.actions.api.PolicyOutcome;
import vigil.actions.api.RefusalReason;
import vigil.actions.api.RequestState;
import vigil.actions.api.StateCheckOperator;
import vigil.incidents.api.IncidentState;

/**
 * The stored texts of vigil-actions (P-24 spec Sections 6.8 to 6.10): the names and states of
 * DC-TX-01, the evidence summaries and sources of DC-TX-03 and the audit summaries of DC-TX-10.
 */
final class ActionTexts {

  static final Comparator<String> CODE_POINTS = StateChecks::codePointOrder;
  static final String NO_PROPOSAL = "No action was proposed.";
  static final String POLICY_APPROVER = "policy";
  private static final DateTimeFormatter OUTBOUND =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter JSON_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final Map<String, String> RECHECK_NAMES =
      Map.of(
          "approval_valid", "Approval still valid",
          "unchanged", "Action, parameters and target unchanged",
          "incident_open", "Incident still open",
          "precondition", "Precondition");

  private ActionTexts() {}

  /** The outbound time format of DC-TX-01. */
  static String outbound(Instant instant) {
    return OUTBOUND.format(instant);
  }

  /** An instant as the details of a refusal write it (as P-14's acknowledgement). */
  static String jsonTime(Instant instant) {
    return JSON_TIME.format(instant);
  }

  /** A list placeholder: sorted in code point order, joined by ", ", empty as "none". */
  static String list(Collection<String> names) {
    if (names.isEmpty()) {
      return "none";
    }
    return String.join(", ", names.stream().sorted(CODE_POINTS).toList());
  }

  // DC-TX-01.

  static String requestState(RequestState state, RefusalReason reason) {
    return switch (state) {
      case PENDING -> "Waiting for approval";
      case APPROVED -> "Approved";
      case REJECTED -> "Rejected";
      case EXPIRED -> "Expired";
      case PREAUTHORIZED -> "Preauthorized by policy";
      case BLOCKED -> "Blocked by policy";
      case REFUSED ->
          reason == RefusalReason.ATTEMPT_LIMIT_REACHED
              ? "Refused: attempt limit reached"
              : "Refused: an earlier execution has an unknown result";
      case CANCELLED -> "Cancelled";
    };
  }

  static String policy(PolicyOutcome outcome) {
    return switch (outcome) {
      case APPROVAL_REQUIRED -> "Approval required";
      case PREAUTHORIZED -> "Preauthorized";
      case BLOCKED -> "Blocked";
    };
  }

  static String executionState(ExecutionState state) {
    return switch (state) {
      case RECORDED, RECHECKING -> "Checking conditions";
      case NOT_EXECUTED -> "Not executed: a recheck failed";
      case EXECUTING -> "Running";
      case RECONCILING -> "Checking the result";
      case SUCCEEDED -> "Succeeded";
      case FAILED -> "Failed";
      case UNRESOLVED -> "Result unknown";
    };
  }

  static String incidentState(IncidentState state) {
    return switch (state) {
      case FORMING -> "Forming";
      case OPEN -> "Open";
      case RESOLVED -> "Resolved";
      case CLOSED -> "Closed";
      case MERGED -> "Merged";
    };
  }

  static String recheckName(String check) {
    return RECHECK_NAMES.get(check);
  }

  static String symbol(StateCheckOperator operator) {
    return switch (operator) {
      case EQUALS -> "=";
      case NOT_EQUALS -> "≠";
      case GREATER_THAN -> ">";
      case GREATER_OR_EQUAL -> "≥";
      case LESS_THAN -> "<";
      case LESS_OR_EQUAL -> "≤";
    };
  }

  // Recheck details (DC-TX-01, CTX-D-075 (3)).

  /** {@code approverNames} hold "policy" for a preauthorized request; empty: no request. */
  static String approvalValid(List<String> approverNames, Instant earliestExpiry) {
    if (approverNames.isEmpty()) {
      return "no approval";
    }
    return "approved by "
        + list(approverNames.stream().distinct().toList())
        + "; valid until "
        + outbound(earliestExpiry);
  }

  /** {@code parts} in the order action, hypothesis component, target; empty when unchanged. */
  static String unchanged(List<String> parts) {
    return parts.isEmpty() ? "unchanged since approval" : "changed: " + String.join(", ", parts);
  }

  /** The parameters as {@code name=value}, by name in code point order, joined by ", ". */
  static String parameters(JsonNode parameters) {
    return String.join(
        ", ",
        parameters.propertyNames().stream()
            .sorted(CODE_POINTS)
            .map(name -> name + "=" + parameters.get(name).asString())
            .toList());
  }

  /** The precondition's detail; {@code value} {@code null} when none was read. */
  static String precondition(
      String operationName,
      JsonNode parameters,
      String value,
      StateCheckOperator operator,
      String threshold) {
    String head = operationName + " (" + parameters(parameters) + "): ";
    return value == null
        ? head + "no value read"
        : head + value + " " + symbol(operator) + " " + threshold;
  }

  // Evidence (DC-TX-03).

  static String approvalSummary(
      RequestState state,
      RefusalReason reason,
      String userName,
      String actionName,
      String targetName) {
    String on = actionName + " on " + targetName + ".";
    return switch (state) {
      case APPROVED -> "Approved by " + userName + ": " + on;
      case REJECTED -> "Rejected by " + userName + ": " + on;
      case EXPIRED -> "Approval expired with no decision: " + on;
      case PREAUTHORIZED -> "Preauthorized by policy: " + on;
      case BLOCKED -> "Blocked by policy: " + on;
      case REFUSED ->
          "Approval refused ("
              + (reason == RefusalReason.ATTEMPT_LIMIT_REACHED
                  ? "attempt limit reached"
                  : "an earlier execution has an unknown result")
              + "): "
              + on;
      default -> throw new IllegalArgumentException("no evidence for " + state.key());
    };
  }

  static String approvalSource(long sequenceInRun) {
    return "Approval " + sequenceInRun + " of the run";
  }

  /**
   * The execution summary; {@code removedFiles} the output values of a {@code removed_files} run,
   * or {@code null}; {@code failedRecheck} the key of the failed recheck of {@code not_executed}.
   */
  static String executionSummary(
      ExecutionState state,
      String actionName,
      String targetName,
      JsonNode removedFiles,
      String failedRecheck) {
    String head = actionName + " on " + targetName + ": ";
    if (state == ExecutionState.NOT_EXECUTED) {
      return head + "not executed, recheck '" + recheckName(failedRecheck) + "' failed.";
    }
    String result = "";
    if (removedFiles != null) {
      String files = valueOf(removedFiles, "files_removed");
      String gib = valueOf(removedFiles, "gib_removed");
      result =
          "; " + files + ("1".equals(files) ? " file" : " files") + " removed (" + gib + " GiB)";
    }
    return head + executionState(state) + result + ".";
  }

  static String executionSource(String targetKey, String operationKey) {
    return targetKey + " via runner, operation " + operationKey;
  }

  private static String valueOf(JsonNode values, String name) {
    for (JsonNode entry : values) {
      if (name.equals(entry.path("name").asString())) {
        return entry.get("value").asString();
      }
    }
    return "";
  }

  // Audit (DC-TX-10).

  static String policyCheckAudit(String actionName, PolicyOutcome outcome) {
    return "Policy for " + actionName + ": " + policy(outcome) + ".";
  }

  static String approvalRequestedAudit(
      long sequenceInRun, String actionName, String targetName, List<String> teamNames) {
    return "Approval "
        + sequenceInRun
        + " requested for "
        + actionName
        + " on "
        + targetName
        + " from "
        + list(teamNames)
        + ".";
  }

  /** {@code userName} {@code null} unless a person decided. */
  static String approvalDecidedAudit(
      long sequenceInRun,
      RequestState state,
      RefusalReason reason,
      String userName,
      String actionName,
      String targetName) {
    return "Approval "
        + sequenceInRun
        + ": "
        + requestState(state, reason)
        + (userName == null ? "" : " by " + userName)
        + " — "
        + actionName
        + " on "
        + targetName
        + ".";
  }

  static String actionExecutedAudit(
      String actionName, String targetName, long attemptNumber, ExecutionState state) {
    return actionName
        + " on "
        + targetName
        + ", attempt "
        + attemptNumber
        + ": "
        + executionState(state)
        + ".";
  }

  static String actionReconciledAudit(
      String actionName, String targetName, long attemptNumber, ExecutionState state) {
    return actionName
        + " on "
        + targetName
        + ", attempt "
        + attemptNumber
        + ": result checked, "
        + executionState(state)
        + ".";
  }
}
