// The card summaries and named outputs of the step types on a run canvas (DC-TX-05, DC-S06-05, DM Section 9; P-29 spec
// Section 6.6 items 2 and 4): durations in seconds (DC-U-07 rule (3)), team names from the run's team_names.
import type { StepType, TeamName } from "../api/types";
import { aiTaskName, count, listText } from "../format/names";

/** A step of the stored content (DM-V-05); parameters as `WorkflowPartCheck.stored` writes them. */
export interface ContentStep {
  step_id: string;
  type: StepType;
  label: string;
  position: { x: number; y: number };
  parameters: Record<string, unknown>;
}

const list = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);
const text = (value: unknown): string =>
  value === undefined || value === null ? "" : String(value);

/** The name of a team key from team_names; a key it does not hold is shown as the key (DC-U-06). */
export function teamName(key: string, teams: readonly TeamName[]): string {
  return teams.find((team) => team.team_key === key)?.name ?? key;
}

const teamNames = (value: unknown, teams: readonly TeamName[]) =>
  list(value).map((key) => teamName(String(key), teams));

export function cardSummary(
  step: ContentStep,
  teams: readonly TeamName[],
): string {
  const p = step.parameters;
  switch (step.type) {
    case "trigger":
      return "On incident created or reopened";
    case "condition":
      return `${count(list(p.branches).length, "branch", "branches")} and otherwise`;
    case "diagnostic":
      return `${count(list(p.diagnostics).length, "check", "checks")} on the hypothesis component's server`;
    case "ai_task":
      return aiTaskName(text(p.task));
    case "approval":
      return `Eligible: ${listText(teamNames(p.eligible_teams, teams))}; expires after ${text(p.expiry)} s`;
    case "action":
      return "Runs the approved action";
    case "recovery_check":
      return `Gives up after ${text(p.max_wait)} s`;
    case "escalation":
      return p.mode === "escalate_now"
        ? "Escalates one level now"
        : `Escalates every ${text(p.timer)} s until acknowledged`;
    case "notification": {
      const recipients = list(p.recipients);
      const parts = [
        ...(recipients.includes("owner_team") ? ["Owner team"] : []),
        ...(recipients.includes("informed_parties") ? ["Informed teams"] : []),
        ...(recipients.includes("named_teams")
          ? [listText(teamNames(p.named_teams, teams))]
          : []),
      ];
      return `To ${parts.join(", ")}`;
    }
    case "ticket":
      return p.operation === "resolve" ? "Resolve ticket" : "Create ticket";
    case "wait":
      return p.mode === "until_event"
        ? `Wait for acknowledgement, up to ${text(p.timeout)} s`
        : `Wait ${text(p.duration)} s`;
  }
}

const OUTPUTS: Record<Exclude<StepType, "condition">, string[]> = {
  trigger: ["next"],
  diagnostic: ["done", "failed"],
  ai_task: ["done", "unavailable"],
  approval: ["approved", "rejected", "expired"],
  action: ["succeeded", "failed"],
  recovery_check: ["recovered", "not_recovered"],
  escalation: ["next"],
  notification: ["sent", "failed"],
  ticket: ["done", "failed"],
  wait: ["event_received", "timed_out"],
};

/** The named outputs in DM Section 9's order; a Condition's branch labels in order, then `otherwise`. */
export function namedOutputs(step: ContentStep): string[] {
  if (step.type === "condition") {
    return [
      ...list(step.parameters.branches).map((branch) =>
        text((branch as { label?: unknown }).label),
      ),
      "otherwise",
    ];
  }
  return OUTPUTS[step.type] ?? [];
}
