// Fixtures of the mobile pages' tests, typed by the generated types: the storm incident's Acknowledge page and its
// first approval request (SB-S05, SB-S06) with demo values. Times are UTC on 10 Oct 2026; NOW is 10:20:00.
import type { AcknowledgePage, ApprovalPage } from "../api/types";
import { at, DB_OPS, NOW, STORM_TITLE } from "./fixtures";

const id = (n: number) =>
  `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;

export const HIRA = { user_id: id(901), display_name: "Hira Baig (NOC L1)" };
export const USMAN = { user_id: id(902), display_name: "Usman Tariq (DB Ops)" };

export const STORM_SUMMARY =
  "Subscriber DB server /data is full; 3 services affected.";

export function acknowledgePage(
  over: Partial<AcknowledgePage> = {},
): AcknowledgePage {
  return {
    incident_id: id(2),
    number: 2,
    title: STORM_TITLE,
    severity: "P1",
    owner: DB_OPS,
    first_alert_fired_at: at("10:00:12"),
    alert_count: 126,
    summary: STORM_SUMMARY,
    state: "open",
    acknowledged: null,
    may_act: true,
    refusal: null,
    ...over,
  };
}

/** The request expires 900 s after NOW, so the countdown shows "in 14 min 59 s" at NOW + 1 s. */
export const EXPIRES_AT = new Date(NOW.getTime() + 900_000).toISOString();

export const LIMITS = [
  "Deletes only *.arc files directly under /data/txlog_archive",
  "Deletes only files last modified more than 24 hours ago",
  "Deletes at most 12 GiB, oldest files first",
  "Runs only on the Subscriber DB server (lab-db-01)",
];

export const CONDITIONS = [
  "Every alert of the incident is resolved at its source",
  "No alert of Synthetic USSD sessions is firing",
];

export const EVIDENCE_SUMMARIES = [
  "/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)",
  "Largest folder under /data: /data/txlog_archive (8.5 GiB)",
  "8.5 GiB written to 34 files under /data in the last 15 minutes",
  "AI investigation by Claude: confirms the hypothesis Subscriber DB server; cites RB-DB-01.",
  "Similar past incident PI-1001 'Subscriber DB /data full after archive catch-up run' (Fictional record), similarity 1.00; fix succeeded 4 times.",
];

export function approvalPage(over: Partial<ApprovalPage> = {}): ApprovalPage {
  const kinds: ApprovalPage["evidence"][number]["kind"][] = [
    "diagnostic_result",
    "diagnostic_result",
    "diagnostic_result",
    "ai_investigation",
    "similarity_match",
  ];
  const collected = [
    "10:01:02",
    "10:01:03",
    "10:01:04",
    "10:01:30",
    "10:07:40",
  ];
  return {
    approval_request_id: id(601),
    sequence_in_run: 1,
    incident: { incident_id: id(2), number: 2, title: STORM_TITLE },
    state: "pending",
    refusal_reason: null,
    snapshot_hash: "a1b2c3d4e5f60718293a4b5c6d7e8f90",
    action_name: "Purge archived transaction logs",
    parameters: [
      { name: "path", value: "/data/txlog_archive" },
      { name: "older_than_minutes", value: 1440 },
      { name: "max_bytes", value: 12884901888 },
    ],
    target: { target_key: "lab-db-01", name: "Subscriber DB server" },
    limits: LIMITS,
    attempt_number: 1,
    attempt_limit: 1,
    expires_at: EXPIRES_AT,
    based_on: {
      past_incident_key: "PI-1001",
      past_incident_title:
        "Subscriber DB /data full after archive catch-up run",
      record_label: "Fictional record",
      score: "1.00",
      success_count: 4,
    },
    evidence: kinds.map((kind, index) => ({
      evidence_entry_id: id(401 + index),
      kind,
      summary: EVIDENCE_SUMMARIES[index],
      collected_at: at(collected[index]),
    })),
    recovery_checks: {
      name: "Subscriber DB services recovered",
      conditions: CONDITIONS,
      observation_window_seconds: 60,
    },
    decided: null,
    may_act: true,
    refusal: null,
    ...over,
  };
}
