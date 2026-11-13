import { expect, it } from "vitest";
import {
  aiTaskName,
  aiUnavailableReasonText,
  approvalStateName,
  componentKindName,
  criticalityName,
  executionStateName,
  listText,
  notifiedPurposeName,
  outputText,
  policyResultName,
  providerDisplayName,
  providerKindName,
  recheckName,
  runStateName,
  stepTypeName,
} from "./names";
import {
  count,
  incidentNumber,
  isAre,
  severityName,
  sourceKindName,
  stageName,
  stateName,
} from "./names";

it("DC-S02-04 incident numbers are INC- and at least 4 digits", () => {
  expect(incidentNumber(2)).toBe("INC-0002");
  expect(incidentNumber(1234)).toBe("INC-1234");
  expect(incidentNumber(12345)).toBe("INC-12345");
});

it("DC-S02-04 state, stage, severity and source kind names", () => {
  expect(
    ["forming", "open", "resolved", "closed", "merged"].map(stateName),
  ).toEqual(["Forming", "Open", "Resolved", "Closed", "Merged"]);
  expect(
    ["investigation", "response", "recovery", null].map(stageName),
  ).toEqual(["Investigation", "Response", "Recovery", ""]);
  expect(["P1", "P2", "P3", "P4", null].map(severityName)).toEqual([
    "P1",
    "P2",
    "P3",
    "P4",
    "",
  ]);
  expect(sourceKindName("zabbix")).toBe("Zabbix");
  expect(sourceKindName("alertmanager")).toBe("Alertmanager");
});

it("DC-S02-02 alert or alerts, is or are", () => {
  expect(`${count(1, "alert", "alerts")} ${isAre(1)}`).toBe("1 alert is");
  expect(`${count(126, "alert", "alerts")} ${isAre(126)}`).toBe(
    "126 alerts are",
  );
  expect(`${count(0, "alert", "alerts")} ${isAre(0)}`).toBe("0 alerts are");
});

it("DC-S02-05 monitoring source or monitoring sources", () => {
  expect(count(1, "monitoring source", "monitoring sources")).toBe(
    "1 monitoring source",
  );
  expect(count(2, "monitoring source", "monitoring sources")).toBe(
    "2 monitoring sources",
  );
});

const each = (f: (key: string) => string, keys: string[]) => keys.map(f);

it("DC-TX-01 the names and states the workspace shows", () => {
  expect(
    each(componentKindName, [
      "server",
      "application",
      "database",
      "queue",
      "check",
    ]),
  ).toEqual(["Server", "Application", "Database", "Queue", "Check"]);
  expect(
    [null, "critical", "high", "medium", "low"].map(criticalityName),
  ).toEqual(["not set", "Critical", "High", "Medium", "Low"]);
  expect(
    each(providerKindName, [
      "claude",
      "openai",
      "local_model",
      "local_embedding",
    ]),
  ).toEqual(["Claude", "OpenAI", "Local model", "Local embedding model"]);
  expect(providerDisplayName("claude", "claude-sonnet-5-5")).toBe(
    "Claude — claude-sonnet-5-5",
  );
  expect(providerDisplayName(null, "qwen3")).toBe("qwen3");
  expect(
    each(aiUnavailableReasonText, [
      "no_provider_reachable",
      "no_provider_allowed",
      "output_failed_checks",
    ]),
  ).toEqual([
    "no allowed AI provider was reachable",
    "data routing allows no provider for this data",
    "the AI output failed the checks and was not used",
  ]);
  expect(
    [
      "pending",
      "approved",
      "rejected",
      "expired",
      "preauthorized",
      "blocked",
      "cancelled",
    ].map((state) => approvalStateName(state, null)),
  ).toEqual([
    "Waiting for approval",
    "Approved",
    "Rejected",
    "Expired",
    "Preauthorized by policy",
    "Blocked by policy",
    "Cancelled",
  ]);
  expect(approvalStateName("refused", "attempt_limit_reached")).toBe(
    "Refused: attempt limit reached",
  );
  expect(approvalStateName("refused", "unresolved_execution")).toBe(
    "Refused: an earlier execution has an unknown result",
  );
  expect(
    each(policyResultName, ["approval_required", "preauthorized", "blocked"]),
  ).toEqual(["Approval required", "Preauthorized", "Blocked"]);
  expect(policyResultName(null)).toBe("");
  expect(
    each(executionStateName, [
      "recorded",
      "rechecking",
      "not_executed",
      "executing",
      "reconciling",
      "succeeded",
      "failed",
      "unresolved",
    ]),
  ).toEqual([
    "Checking conditions",
    "Checking conditions",
    "Not executed: a recheck failed",
    "Running",
    "Checking the result",
    "Succeeded",
    "Failed",
    "Result unknown",
  ]);
  expect(
    each(recheckName, [
      "approval_valid",
      "unchanged",
      "incident_open",
      "precondition",
    ]),
  ).toEqual([
    "Approval still valid",
    "Action, parameters and target unchanged",
    "Incident still open",
    "Precondition",
  ]);
  expect(
    each(notifiedPurposeName, [
      "incident_notification",
      "escalation",
      "approval_request",
    ]),
  ).toEqual(["incident notification", "escalation", "approval request"]);
  expect(
    each(runStateName, [
      "running",
      "waiting",
      "finished",
      "cancelled",
      "failed",
    ]),
  ).toEqual(["Running", "Waiting", "Finished", "Cancelled", "Failed"]);
  expect(
    each(stepTypeName, [
      "trigger",
      "condition",
      "diagnostic",
      "ai_task",
      "approval",
      "action",
      "recovery_check",
      "escalation",
      "notification",
      "ticket",
      "wait",
    ]),
  ).toEqual([
    "Trigger",
    "Condition",
    "Diagnostic",
    "AI task",
    "Approval",
    "Action",
    "Recovery check",
    "Escalation",
    "Notification",
    "Ticket",
    "Wait",
  ]);
  expect(each(aiTaskName, ["SL-T-1", "SL-T-2", "SL-T-3"])).toEqual([
    "Incident investigation",
    "Incident summary",
    "Similar-incident search",
  ]);
  expect(
    each(outputText, [
      "next",
      "done",
      "failed",
      "unavailable",
      "approved",
      "rejected",
      "expired",
      "succeeded",
      "recovered",
      "not_recovered",
      "sent",
      "event_received",
      "timed_out",
      "otherwise",
      "p1",
    ]),
  ).toEqual([
    "next",
    "done",
    "failed",
    "unavailable",
    "approved",
    "rejected",
    "expired",
    "succeeded",
    "recovered",
    "not recovered",
    "sent",
    "event received",
    "timed out",
    "otherwise",
    "p1",
  ]);
  expect(listText(["VAS Service Owners", "Database Operations"])).toBe(
    "Database Operations, VAS Service Owners",
  );
  expect(listText([])).toBe("none");
});
