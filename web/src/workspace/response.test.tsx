import { expect, it } from "vitest";
import tags from "../tags/tags.module.css";
import { at, DB_OPS, response } from "../test/fixtures";
import { render, texts } from "../test/render";
import { ResponseContent } from "./Response";

const lines = (over: Parameters<typeof response>[0] = {}) =>
  texts(render(<ResponseContent section={response(over)} />), "p");
const base = response();
const approval = base.approvals[0];
const execution = base.executions[0];

it("DC-S03-10 the storm's Response", () => {
  expect(lines()).toEqual([
    "PI-1001 Subscriber DB /data full after archive catch-up runFictional record",
    "Similarity 1.00",
    "Fix succeeded 4 times",
    "Fix: Purged archived transaction logs older than 24 hours.",
    "Proposed actionPurge archived transaction logs",
    "path = /data/txlog_archive",
    "older_than_minutes = 1440",
    "max_bytes = 12884901888",
    "Target: Subscriber DB server (lab-db-01)",
    "Policy: Approval required",
    "Approval 1",
    "Eligible: Database Operations",
    "Approved",
    "by Usman Tariq (DB Ops) at 10:09:00",
    "Execution resultAttempt 1 of 1",
    "Succeeded",
    "Approval still valid: passed",
    "approved by Usman Tariq (DB Ops); valid until 2026-10-10 10:22:40 UTC",
    "Action, parameters and target unchanged: passed",
    "unchanged since approval",
    "Incident still open: passed",
    "open",
    "Precondition: passed",
    "Used percentage of one filesystem (mount=/data): 100 ≥ 90",
    "Purge archived transaction logs on Subscriber DB server: Succeeded; 34 files removed (8.5 GiB).",
    "Started: 10:09:01",
    "Finished: 10:09:05",
  ]);
});

it("DC-S03-10 no match with and without a score, unavailable, no proposal, each approval state, a pending expiry, a failed recheck", () => {
  const similar = base.similar!;
  const first = (over: Parameters<typeof response>[0]) =>
    lines({ proposal: null, approvals: [], executions: [], ...over });
  expect(
    first({
      similar: {
        ...similar,
        outcome: "no_match",
        past_incident: null,
        score: "0.42",
        success_count: null,
      },
    }),
  ).toEqual([
    "No similar past incident (best score 0.42 is below the minimum 0.80).",
  ]);
  expect(
    first({
      similar: {
        ...similar,
        outcome: "no_match",
        past_incident: null,
        score: null,
      },
    }),
  ).toEqual(["No similar past incident."]);
  expect(
    first({ similar: { ...similar, state: "unavailable", outcome: null } }),
  ).toEqual(["Similar-incident search unavailable."]);
  expect(first({ similar: { ...similar, outcome: null } })).toEqual([]);
  expect(first({ similar: { ...similar, past_incident: null } })).toEqual([]);
  expect(
    first({
      similar: { ...similar, state: "unavailable", outcome: null },
      no_proposal: true,
    }),
  ).toEqual([
    "Similar-incident search unavailable.",
    "No action was proposed.",
  ]);
  expect(
    lines({
      proposal: {
        ...base.proposal!,
        target: { target_key: "lab-db-01", name: null },
        policy_outcome: null,
      },
    }),
  ).toEqual(expect.arrayContaining(["Target: lab-db-01", "Policy: "]));
  const states = (
    [
      ["pending", null, "Waiting for approval"],
      ["approved", null, "Approved"],
      ["rejected", null, "Rejected"],
      ["expired", null, "Expired"],
      ["preauthorized", null, "Preauthorized by policy"],
      ["blocked", null, "Blocked by policy"],
      ["refused", "attempt_limit_reached", "Refused: attempt limit reached"],
      [
        "refused",
        "unresolved_execution",
        "Refused: an earlier execution has an unknown result",
      ],
      ["cancelled", null, "Cancelled"],
    ] as const
  ).map(([state, refusal_reason, text]) => {
    const shown = first({
      similar: null,
      approvals: [{ ...approval, state, refusal_reason, decided: null }],
    });
    expect(shown[2]).toBe(text);
    return shown;
  });
  expect(states[0]).toEqual([
    "Approval 1",
    "Eligible: Database Operations",
    "Waiting for approval",
    "expires 10:22:40",
  ]);
  expect(states[1]).toEqual([
    "Approval 1",
    "Eligible: Database Operations",
    "Approved",
  ]);
  expect(
    first({
      similar: null,
      approvals: [
        {
          ...approval,
          eligible_teams: [{ team_key: "z", name: "Zeta" }, DB_OPS],
        },
      ],
    })[1],
  ).toBe("Eligible: Database Operations, Zeta");
  const failedRecheck = first({
    similar: null,
    executions: [
      {
        ...execution,
        attempt_limit: null,
        state: "not_executed",
        recheck: [
          {
            check: "precondition",
            passed: false,
            detail: "Used percentage of one filesystem (mount=/data): 40 ≥ 90",
          },
        ],
        result_summary: null,
        finished_at: null,
        started_at: at("10:09:01"),
      },
    ],
  });
  expect(failedRecheck).toEqual([
    "Execution resultAttempt 1",
    "Not executed: a recheck failed",
    "Precondition: failed",
    "Used percentage of one filesystem (mount=/data): 40 ≥ 90",
    "Started: 10:09:01",
  ]);
});

it("SL-DR-03 the record label is shown", () => {
  const html = render(<ResponseContent section={response()} />);
  expect(html).toContain(
    `<span class="${tags.tag}" data-kind="record">Fictional record</span>`,
  );
});
