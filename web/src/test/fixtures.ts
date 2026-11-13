// Fixtures of the incident workspace's tests, typed by the generated types: the storm incident of SB-S02 to SB-S08
// with demo values, and helpers for variants. Times are UTC on 10 Oct 2026; NOW is 10:20:00.
import type {
  EvidenceLines,
  IncidentAlerts,
  IncidentHeader,
  IncidentTimeline,
  InvestigationSection,
  LiveRun,
  NotifiedTeams,
  RecoverySection,
  ResponseSection,
  StepExecution,
} from "../api/types";
import { viewerTime } from "../format/time";
import type { PartState } from "../workspace/Section";
import type { WorkspaceParts } from "../workspace/WorkspaceView";
import { CONTENT } from "./content";

export const NOW = new Date("2026-10-10T10:20:00.000Z");
export const at = (time: string) => `2026-10-10T${time}.000Z`;
export const time = (iso: string) => viewerTime(iso, NOW, "UTC");
const id = (n: number) =>
  `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;

export const DB_OPS = { team_key: "db-ops", name: "Database Operations" };
export const NOC_L1 = { team_key: "noc-l1", name: "NOC L1" };
export const VAS = { team_key: "vas-owners", name: "VAS Service Owners" };
export const IT_APPS = { team_key: "it-apps", name: "IT Applications" };
const HIRA = { user_id: id(901), display_name: "Hira Baig (NOC L1)" };
const USMAN = { user_id: id(902), display_name: "Usman Tariq (DB Ops)" };

export const STORM_TITLE =
  "Subscriber DB server: Disk full — 3 services affected";

export function header(over: Partial<IncidentHeader> = {}): IncidentHeader {
  return {
    incident_id: id(2),
    number: 2,
    title: STORM_TITLE,
    state: "open",
    stage: "response",
    severity: "P1",
    owner: DB_OPS,
    informed: [VAS],
    rule_name: "Critical service affected",
    acknowledged: { user: HIRA, at: at("10:07:30") },
    escalation: null,
    first_alert_fired_at: at("10:00:12"),
    resolved_at: null,
    ticket: { key: "NOC-2", url: "https://jira.example/browse/NOC-2" },
    reopen_count: 0,
    alert_count: 126,
    hypothesis: {
      component_key: "srv-db-01",
      name: "Subscriber DB server",
      kind: "server",
      criticality: "critical",
      other_affected_count: 6,
      dependent_count: 6,
      tie_break: "none",
    },
    affected_services: [
      {
        service_key: "svc-balance",
        name: "Balance Check (USSD)",
        criticality: "critical",
        via: [
          { component_key: "app-ussd-gw", name: "USSD gateway" },
          { component_key: "app-balance-api", name: "Balance-check API" },
        ],
      },
      {
        service_key: "svc-bundle",
        name: "Bundle Purchase",
        criticality: "critical",
        via: [{ component_key: "app-bundle", name: "Bundle service" }],
      },
      {
        service_key: "svc-sms",
        name: "SMS Notifications",
        criticality: "high",
        via: [{ component_key: "q-sms", name: "SMS queue" }],
      },
    ],
    ...over,
  };
}

const DISK_FULL = "Disk full: /data free space below 1 GiB";

export function alerts(): IncidentAlerts {
  return {
    firing: 120,
    resolved: 6,
    grouping_window_seconds: 300,
    groups: [
      {
        alert_type: "api_errors",
        alert_type_name: "API error rate high",
        count: 2,
        alerts: [
          {
            alert_id: id(301),
            fired_at: at("10:00:26"),
            source_kind: "alertmanager",
            title: "Balance-check API error rate above 5 %",
            component_name: "Balance-check API",
            status: "firing",
            reason: {
              relation: "depends_on",
              reopened: false,
              related: { title: DISK_FULL, source_kind: "zabbix" },
              path: [
                { component_key: "app-balance-api", name: "Balance-check API" },
                { component_key: "db-subscriber", name: "Subscriber database" },
                { component_key: "srv-db-01", name: "Subscriber DB server" },
              ],
              time_delta_seconds: 14,
              identity: null,
            },
          },
          {
            alert_id: id(302),
            fired_at: at("10:00:40"),
            source_kind: "alertmanager",
            title: "Unknown job error rate above 5 %",
            component_name: null,
            status: "resolved",
            reason: {
              relation: "same_unknown_identity",
              reopened: false,
              related: {
                title: "Unknown job latency high",
                source_kind: "alertmanager",
              },
              path: [],
              time_delta_seconds: 3,
              identity: { name: "job", value: "batch-7" },
            },
          },
        ],
      },
      {
        alert_type: "disk_full",
        alert_type_name: "Disk full",
        count: 1,
        alerts: [
          {
            alert_id: id(303),
            fired_at: at("10:00:12"),
            source_kind: "zabbix",
            title: DISK_FULL,
            component_name: "Subscriber DB server",
            status: null,
            reason: {
              relation: "only_alert",
              reopened: false,
              related: null,
              path: [],
              time_delta_seconds: null,
              identity: null,
            },
          },
        ],
      },
    ],
  };
}

export function evidence(): EvidenceLines {
  const line = (
    n: number,
    kind: EvidenceLines["entries"][number]["kind"],
    summary: string,
    source: string,
    collected: string,
    output_values: EvidenceLines["entries"][number]["output_values"] = null,
  ) => ({
    evidence_entry_id: id(400 + n),
    kind,
    summary,
    source_system: source,
    collected_at: at(collected),
    output_values,
  });
  return {
    entries: [
      line(
        1,
        "diagnostic_result",
        "/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)",
        "lab-db-01 via runner, operation fs_usage",
        "10:01:02",
        [
          { name: "used_pct", value: 100 },
          { name: "used_gib", value: 15.9 },
          { name: "mount", value: "/data" },
        ],
      ),
      line(
        2,
        "diagnostic_result",
        "Largest folder under /data: /data/txlog_archive (8.5 GiB)",
        "lab-db-01 via runner, operation largest_dirs",
        "10:01:03",
        [{ name: "top1_path", value: "/data/txlog_archive" }],
      ),
      line(
        3,
        "diagnostic_result",
        "8.5 GiB written to 34 files under /data in the last 15 minutes",
        "lab-db-01 via runner, operation recent_growth",
        "10:01:04",
        [{ name: "gib", value: 8.5 }],
      ),
      line(
        4,
        "ai_investigation",
        "AI investigation by Claude: confirms the hypothesis Subscriber DB server; cites RB-DB-01.",
        "Claude, model claude-sonnet-5-5",
        "10:01:30",
      ),
      line(
        5,
        "similarity_match",
        "Similar past incident PI-1001 'Subscriber DB /data full after archive catch-up run' (Fictional record), similarity 1.00; fix succeeded 4 times.",
        "Incident history, Local embedding model",
        "10:07:40",
      ),
      line(
        6,
        "approval",
        "Approved by Usman Tariq (DB Ops): Purge archived transaction logs on Subscriber DB server.",
        "Approval 1 of the run",
        "10:09:00",
      ),
      line(
        7,
        "action_execution",
        "Purge archived transaction logs on Subscriber DB server: Succeeded; 34 files removed (8.5 GiB).",
        "lab-db-01 via runner, operation purge_archive",
        "10:09:05",
      ),
      line(
        8,
        "recovery_verification",
        "Recovered: 'Subscriber DB services recovered' held for 60 s with every monitoring source healthy.",
        "Monitoring: Lab Prometheus, Lab Zabbix",
        "10:11:00",
      ),
    ],
  };
}

export function investigation(
  over: Partial<InvestigationSection> = {},
): InvestigationSection {
  return {
    ai_status: "available",
    ai_unavailable_reason: null,
    ai_status_changed_at: null,
    ai_last_request_group_id: id(500),
    result: {
      likely_cause:
        "Archive catch-up filled /data on the Subscriber DB server.",
      verdict: "confirms",
      evidence_used: [
        {
          evidence_entry_id: id(401),
          summary: "/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)",
        },
      ],
      uncertainty: "Whether the database is read-only.",
      runbooks_supplied: ["RB-API-01", "RB-DB-01", "RB-USSD-01"],
      runbooks_cited: ["RB-USSD-01", "RB-DB-01"],
      provider_kind: "claude",
      model_name: "claude-sonnet-5-5",
      created_at: at("10:01:30"),
      origin: "workflow_step",
      requested_by: null,
    },
    summary: { text: "Subscriber DB /data is full.", source: "ai" },
    ...over,
  };
}

export function response(over: Partial<ResponseSection> = {}): ResponseSection {
  return {
    similar: {
      state: "done",
      outcome: "match",
      past_incident: {
        past_incident_key: "PI-1001",
        title: "Subscriber DB /data full after archive catch-up run",
        record_label: "Fictional record",
        fix_description:
          "Purged archived transaction logs older than 24 hours.",
      },
      score: "1.00",
      success_count: 4,
      min_similarity: "0.80",
    },
    proposal: {
      action_name: "Purge archived transaction logs",
      parameters: [
        { name: "path", value: "/data/txlog_archive" },
        { name: "older_than_minutes", value: 1440 },
        { name: "max_bytes", value: 12884901888 },
      ],
      target: { target_key: "lab-db-01", name: "Subscriber DB server" },
      policy_outcome: "approval_required",
    },
    no_proposal: false,
    approvals: [
      {
        approval_request_id: id(601),
        sequence_in_run: 1,
        eligible_teams: [DB_OPS],
        state: "approved",
        refusal_reason: null,
        decided: { user: USMAN, at: at("10:09:00") },
        expires_at: at("10:22:40"),
      },
    ],
    executions: [
      {
        action_execution_id: id(701),
        attempt_number: 1,
        attempt_limit: 1,
        state: "succeeded",
        recheck: [
          {
            check: "approval_valid",
            passed: true,
            detail:
              "approved by Usman Tariq (DB Ops); valid until 2026-10-10 10:22:40 UTC",
          },
          {
            check: "unchanged",
            passed: true,
            detail: "unchanged since approval",
          },
          { check: "incident_open", passed: true, detail: "open" },
          {
            check: "precondition",
            passed: true,
            detail: "Used percentage of one filesystem (mount=/data): 100 ≥ 90",
          },
        ],
        result_summary:
          "Purge archived transaction logs on Subscriber DB server: Succeeded; 34 files removed (8.5 GiB).",
        started_at: at("10:09:01"),
        finished_at: at("10:09:05"),
      },
    ],
    ...over,
  };
}

type Verification = RecoverySection["verifications"][number];

export function verification(over: Partial<Verification> = {}): Verification {
  return {
    recovery_verification_id: id(801),
    check_name: "Subscriber DB services recovered",
    state: "observing",
    observation_window_seconds: 60,
    started_at: at("10:09:06"),
    max_wait_until: at("10:14:06"),
    window_started_at: at("10:19:43"),
    decided_at: null,
    conditions: [
      {
        detail: "Every alert of the incident is resolved at its source",
        held: true,
      },
      {
        detail: "No alert of Synthetic USSD sessions is firing",
        held: true,
      },
    ],
    sources_healthy: true,
    unhealthy_sources: [],
    ...over,
  };
}

const PATH: [string, string | null][] = [
  ["s-trigger", "next"],
  ["s-severity", "p1"],
  ["s-diagnose", "done"],
  ["s-investigate", "done"],
  ["s-summarise", "done"],
  ["s-ticket", "done"],
  ["s-notify", "sent"],
  ["s-wait-ack", "next"],
  ["s-similar", "done"],
  ["s-approve", "approved"],
  ["s-action", "succeeded"],
  ["s-recover", "recovered"],
  ["s-resolve", "done"],
];

/** The storm run: the 13 steps of the P1 path, each completed, the run finished. */
export function stormRun(over: Partial<LiveRun> = {}): LiveRun {
  return {
    run_id: id(900),
    state: "finished",
    run_number: 1,
    run_count: 1,
    current_step_execution_id: null,
    content: CONTENT as unknown as LiveRun["content"],
    team_names: [DB_OPS, NOC_L1],
    step_executions: PATH.map(([stepId, output], index) =>
      execution(index + 1, stepId, {
        state: "completed",
        named_output: output,
      }),
    ),
    ...over,
  };
}

export function execution(
  n: number,
  stepId: string,
  over: Partial<StepExecution> = {},
): StepExecution {
  const step = CONTENT.steps.find((s) => s.step_id === stepId)!;
  return {
    step_execution_id: id(1000 + n),
    step_id: stepId,
    step_type: step.type,
    visit: 1,
    state: "running",
    started_at: new Date(Date.parse(at("10:00:30")) + n * 1000).toISOString(),
    named_output: null,
    timer_due_at: null,
    ...over,
  };
}

export function timeline(): IncidentTimeline {
  const entry = (
    n: number,
    when: string,
    entry_type: IncidentTimeline["entries"][number]["entry_type"],
    text: string,
    user: typeof HIRA | null = null,
  ): IncidentTimeline["entries"][number] => ({
    timeline_entry_id: id(1100 + n),
    at: at(when),
    actor:
      user === null
        ? { kind: "module", user_id: null, display_name: null }
        : {
            kind: "user",
            user_id: user.user_id,
            display_name: user.display_name,
          },
    entry_type,
    text,
  });
  return {
    stable_grouping: {
      stable_at: at("10:00:50"),
      alert_count: 126,
      merged_group_count: 3,
      hypothesis_name: "Subscriber DB server",
    },
    entries: [
      entry(9, "10:11:00", "state_changed", "Resolved: recovery verified."),
      entry(
        8,
        "10:07:30",
        "acknowledged",
        "Acknowledged by Hira Baig (NOC L1).",
        HIRA,
      ),
      entry(
        7,
        "10:02:02",
        "alert_joined",
        "Alert 'SMS queue backlog' (Alertmanager) joined.",
      ),
      entry(
        6,
        "10:02:01",
        "alert_joined",
        "Alert 'SMS queue slow' (Alertmanager) joined.",
      ),
      entry(
        5,
        "10:01:00",
        "notified_team_added",
        "Database Operations notified (incident notification).",
      ),
      entry(
        4,
        "10:00:55",
        "alert_joined",
        "Alert 'USSD timeouts' (Zabbix) joined.",
      ),
      entry(
        3,
        "10:00:50",
        "state_changed",
        "Opened as INC-0002: the alert group is stable.",
      ),
      entry(
        2,
        "10:00:20",
        "alert_joined",
        "Alert 'Balance API errors' (Alertmanager) joined.",
      ),
      entry(
        1,
        "10:00:12",
        "created",
        `Incident created from alert '${DISK_FULL}' (Zabbix).`,
      ),
    ],
  };
}

export function notified(): NotifiedTeams {
  return {
    teams: [
      {
        ...VAS,
        purpose: "incident_notification",
        first_notified_at: at("10:01:00"),
      },
      {
        ...DB_OPS,
        purpose: "incident_notification",
        first_notified_at: at("10:01:00"),
      },
      {
        ...NOC_L1,
        purpose: "incident_notification",
        first_notified_at: at("10:00:59"),
      },
    ],
  };
}

/** A part that has answered. */
export const answered = <T>(data: T): PartState<T> => ({
  data,
  failure: null,
  onTryAgain: () => {},
});

export const loading = <T>(): PartState<T> => ({
  data: undefined,
  failure: null,
  onTryAgain: () => {},
});

export const failed = <T>(reason = "No connection."): PartState<T> => ({
  data: undefined,
  failure: reason,
  onTryAgain: () => {},
});

/** Every part of the storm workspace, answered. */
export function stormParts(over: Partial<WorkspaceParts> = {}): WorkspaceParts {
  return {
    alerts: answered(alerts()),
    evidence: answered(evidence()),
    investigation: answered(investigation()),
    response: answered(response()),
    recovery: answered({ verifications: [verification()] }),
    run: answered(stormRun()),
    timeline: answered(timeline()),
    notified: answered(notified()),
    ...over,
  };
}
