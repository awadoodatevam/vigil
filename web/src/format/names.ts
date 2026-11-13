// Display names and counts (DC-TX-00 "Counts", DC-TX-01).
export function incidentNumber(n: number): string {
  return `INC-${String(n).padStart(4, "0")}`;
}

const STATES: Record<string, string> = {
  forming: "Forming",
  open: "Open",
  resolved: "Resolved",
  closed: "Closed",
  merged: "Merged",
};

export function stateName(state: string): string {
  return STATES[state] ?? "";
}

const STAGES: Record<string, string> = {
  investigation: "Investigation",
  response: "Response",
  recovery: "Recovery",
};

export function stageName(stage: string | null): string {
  return stage === null ? "" : (STAGES[stage] ?? "");
}

const SEVERITIES: Record<string, string> = {
  P1: "P1",
  P2: "P2",
  P3: "P3",
  P4: "P4",
};

export function severityName(severity: string | null): string {
  return severity === null ? "" : (SEVERITIES[severity] ?? "");
}

const SOURCE_KINDS: Record<string, string> = {
  zabbix: "Zabbix",
  alertmanager: "Alertmanager",
};

export function sourceKindName(kind: string): string {
  return SOURCE_KINDS[kind] ?? "";
}

/** "{n} {noun}": the singular noun for 1, else the plural. */
export function count(n: number, singular: string, plural: string): string {
  return `${n} ${n === 1 ? singular : plural}`;
}

/** The verb that agrees with a count. */
export function isAre(n: number): string {
  return n === 1 ? "is" : "are";
}

// The names of the incident workspace (DC-TX-01, DC-TX-05, DM Section 9; P-29 spec Section 6.8, complete list).
const name = (names: Record<string, string>) => (key: string | null) =>
  key === null ? "" : (names[key] ?? "");

export const componentKindName = name({
  server: "Server",
  application: "Application",
  database: "Database",
  queue: "Queue",
  check: "Check",
});

const CRITICALITIES: Record<string, string> = {
  critical: "Critical",
  high: "High",
  medium: "Medium",
  low: "Low",
};

/** A criticality; an empty one is "not set". */
export function criticalityName(criticality: string | null): string {
  return criticality === null
    ? "not set"
    : (CRITICALITIES[criticality] ?? "");
}

export const providerKindName = name({
  claude: "Claude",
  openai: "OpenAI",
  local_model: "Local model",
  local_embedding: "Local embedding model",
});

/** "{provider kind name} — {model_name}"; the model name alone without a provider kind (CTX-D-089 (5)). */
export function providerDisplayName(
  kind: string | null,
  model: string,
): string {
  return kind === null ? model : `${providerKindName(kind)} — ${model}`;
}

export const aiUnavailableReasonText = name({
  no_provider_reachable: "no allowed AI provider was reachable",
  no_provider_allowed: "data routing allows no provider for this data",
  output_failed_checks: "the AI output failed the checks and was not used",
});

const REFUSALS: Record<string, string> = {
  attempt_limit_reached: "Refused: attempt limit reached",
  unresolved_execution: "Refused: an earlier execution has an unknown result",
};

const APPROVAL_STATES: Record<string, string> = {
  pending: "Waiting for approval",
  approved: "Approved",
  rejected: "Rejected",
  expired: "Expired",
  preauthorized: "Preauthorized by policy",
  blocked: "Blocked by policy",
  cancelled: "Cancelled",
};

/** An approval request state; `refused` by its refusal reason. */
export function approvalStateName(
  state: string,
  refusal: string | null,
): string {
  return state === "refused"
    ? (REFUSALS[refusal ?? ""] ?? "")
    : (APPROVAL_STATES[state] ?? "");
}

export const policyResultName = name({
  approval_required: "Approval required",
  preauthorized: "Preauthorized",
  blocked: "Blocked",
});

export const executionStateName = name({
  recorded: "Checking conditions",
  rechecking: "Checking conditions",
  not_executed: "Not executed: a recheck failed",
  executing: "Running",
  reconciling: "Checking the result",
  succeeded: "Succeeded",
  failed: "Failed",
  unresolved: "Result unknown",
});

export const recheckName = name({
  approval_valid: "Approval still valid",
  unchanged: "Action, parameters and target unchanged",
  incident_open: "Incident still open",
  precondition: "Precondition",
});

export const notifiedPurposeName = name({
  incident_notification: "incident notification",
  escalation: "escalation",
  approval_request: "approval request",
});

export const runStateName = name({
  running: "Running",
  waiting: "Waiting",
  finished: "Finished",
  cancelled: "Cancelled",
  failed: "Failed",
});

export const stepTypeName = name({
  trigger: "Trigger",
  condition: "Condition",
  diagnostic: "Diagnostic",
  ai_task: "AI task",
  approval: "Approval",
  action: "Action",
  recovery_check: "Recovery check",
  escalation: "Escalation",
  notification: "Notification",
  ticket: "Ticket",
  wait: "Wait",
});

export const aiTaskName = name({
  "SL-T-1": "Incident investigation",
  "SL-T-2": "Incident summary",
  "SL-T-3": "Similar-incident search",
});

const OUTPUTS: Record<string, string> = {
  next: "next",
  done: "done",
  failed: "failed",
  unavailable: "unavailable",
  approved: "approved",
  rejected: "rejected",
  expired: "expired",
  succeeded: "succeeded",
  recovered: "recovered",
  not_recovered: "not recovered",
  sent: "sent",
  event_received: "event received",
  timed_out: "timed out",
  otherwise: "otherwise",
};

/** A named output's display text; any other output is a Condition branch, shown by its label. */
export function outputText(output: string): string {
  return OUTPUTS[output] ?? output;
}

/** A list placeholder (DC-TX-00 "Lists"): alphabetical by the text shown, joined by ", ", "none" when empty. */
export function listText(texts: readonly string[], empty = "none"): string {
  return texts.length === 0
    ? empty
    : [...texts].sort((a, b) => a.localeCompare(b, "en")).join(", ");
}
