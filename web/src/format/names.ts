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
