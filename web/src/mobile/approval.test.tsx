import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import { ApiFailure } from "../api/client";
import type { ApprovalPage } from "../api/types";
import { viewerTime } from "../format/time";
import { at, NOW, STORM_TITLE, time } from "../test/fixtures";
import { blocks, buttons } from "../test/markup";
import {
  approvalPage,
  CONDITIONS,
  EVIDENCE_SUMMARIES,
  EXPIRES_AT,
  LIMITS,
  USMAN,
} from "../test/mobile";
import { texts, textOf } from "../test/render";
import { INITIAL, type Local } from "./actionArea";
import { APPROVE_DENIED, ApprovalView } from "./ApprovalPage";
import styles from "./mobile.module.css";
import { ReadState } from "./parts";
import { decidedText, type TimeFormat } from "./refusals";

const SECOND = NOW.getTime() + 1000;

const view = (
  page: ApprovalPage = approvalPage(),
  local: Local = INITIAL,
  now = SECOND,
  format: TimeFormat = time,
) =>
  renderToStaticMarkup(
    <ApprovalView
      page={page}
      local={local}
      now={now}
      format={format}
      onPress={() => {}}
    />,
  );

const kindTags = (html: string) => texts(html, "span", " data-kind=");

it("DC-S05-03 the content in order", () => {
  const evidence = EVIDENCE_SUMMARIES.flatMap((summary, index) => [
    `${["Observation", "Observation", "Observation", "AI analysis", "History match"][index]}${summary}`,
    ["10:01:02", "10:01:03", "10:01:04", "10:01:30", "10:07:40"][index],
  ]);
  expect(blocks(view())).toEqual([
    "Approve action",
    `INC-0002 ${STORM_TITLE}`,
    "State: Waiting for approval",
    "Action",
    "Purge archived transaction logs",
    "Parameters",
    "path = /data/txlog_archive",
    "older_than_minutes = 1440",
    "max_bytes = 12884901888",
    "Target server",
    "Subscriber DB server (lab-db-01)",
    "Limits",
    ...LIMITS,
    "Attempt 1 of 1",
    "Expires",
    time(EXPIRES_AT),
    "in 14 min 59 s",
    "Based on",
    "PI-1001 Subscriber DB /data full after archive catch-up runFictional record",
    "Similarity 1.00",
    "Fix succeeded 4 times",
    ...evidence,
    "Recovery checks that will be used",
    "Check: Subscriber DB services recovered",
    ...CONDITIONS,
    "Held for 60 s while every monitoring source is healthy.",
  ]);
  expect(view()).toContain(`<ul class="${styles.limits}"><li>`);
  expect(
    blocks(
      view(approvalPage({ target: { target_key: "lab-db-01", name: null } })),
    ),
  ).toContain("lab-db-01");
});

it("DC-S05-03 the line of a second approval", () => {
  expect(blocks(view()).slice(0, 2)).toEqual([
    "Approve action",
    `INC-0002 ${STORM_TITLE}`,
  ]);
  const html = view(approvalPage({ sequence_in_run: 2 }));
  expect(blocks(html).slice(0, 3)).toEqual([
    "Approve action",
    "Approval 2: your approval must differ from earlier approvals in this run.",
    `INC-0002 ${STORM_TITLE}`,
  ]);
  expect(html).toContain(`class="${styles.info}"`);
});

it("DC-S05-01 the buttons with approve_action, View only. without it, the permission text on 403", () => {
  const actor = view();
  expect(buttons(actor)).toEqual(["Approve", "Reject"]);
  expect(textOf(actor)).not.toContain("View only.");
  const viewer = view(approvalPage({ may_act: false }));
  expect(buttons(viewer)).toEqual([]);
  expect(blocks(viewer).at(-1)).toBe("View only.");
  const denied = renderToStaticMarkup(
    <ReadState
      data={undefined}
      error={new ApiFailure(403, "forbidden", "x")}
      onTryAgain={() => {}}
      denied={APPROVE_DENIED}
    >
      {() => <p>page</p>}
    </ReadState>,
  );
  expect(textOf(denied)).toBe("You do not have permission to approve actions.");
});

it("DC-S05-05 the approved and rejected outcomes", () => {
  const approved = decidedText(
    { state: "approved", user: USMAN, at: at("10:09:00") },
    "Techfinity",
    time,
  );
  expect(approved).toBe(
    "Approved by Usman Tariq (DB Ops) at 10:09:00. Techfinity will check the conditions again before it runs the action.",
  );
  const rejected = decidedText(
    { state: "rejected", user: USMAN, at: at("10:09:00") },
    "Techfinity",
    time,
  );
  expect(rejected).toBe("Rejected by Usman Tariq (DB Ops) at 10:09:00.");
  const done = view(approvalPage(), {
    ...INITIAL,
    outcome: { text: approved, tone: "success", refusalKey: "already_decided" },
  });
  expect(buttons(done)).toEqual([]);
  expect(blocks(done).at(-1)).toBe(approved);
  expect(done).toContain(`class="${styles.success}"`);
  const no = view(
    approvalPage({
      state: "rejected",
      decided: { user: USMAN, at: at("10:09:00") },
      refusal: { key: "already_decided", message: "Already rejected … UTC." },
    }),
    {
      ...INITIAL,
      outcome: { text: rejected, tone: "info", refusalKey: "already_decided" },
    },
  );
  expect(blocks(no).at(-1)).toBe(rejected);
  expect(no).toContain(`class="${styles.outcomeInfo}"`);
});

it("DC-S05-07 the countdown changes each second and is not shown once due or decided", () => {
  expect(blocks(view(approvalPage(), INITIAL, SECOND))).toContain(
    "in 14 min 59 s",
  );
  expect(blocks(view(approvalPage(), INITIAL, SECOND + 1000))).toContain(
    "in 14 min 58 s",
  );
  const due = Date.parse(EXPIRES_AT);
  expect(blocks(view(approvalPage(), INITIAL, due - 1000))).toContain(
    "in 0 min 1 s",
  );
  expect(textOf(view(approvalPage(), INITIAL, due))).not.toContain(" min ");
  expect(
    textOf(
      view(
        approvalPage({
          state: "approved",
          decided: { user: USMAN, at: at("10:20:01") },
        }),
      ),
    ),
  ).not.toContain(" min ");
});

it("DC-U-10 the kind tags of the evidence lines", () => {
  const html = view();
  expect(kindTags(html)).toEqual([
    "Fictional record",
    "Observation",
    "Observation",
    "Observation",
    "AI analysis",
    "History match",
  ]);
  expect(html).toContain('data-kind="observation"');
  expect(html).toContain('data-kind="ai-analysis"');
  expect(html).toContain('data-kind="history-match"');
});

it("SL-DR-03 the record label Fictional record is a tag beside the past incident", () => {
  const html = view();
  const line = texts(html, "p", ` class="${styles.tagged}"`)[0];
  expect(line).toBe(
    "PI-1001 Subscriber DB /data full after archive catch-up runFictional record",
  );
  expect(html).toMatch(
    /<span>PI-1001 Subscriber DB \/data full after archive catch-up run<\/span><span class="[^"]+" data-kind="record">Fictional record<\/span>/,
  );
});

it("DC-TX-00 Fix succeeded 1 time and 4 times", () => {
  const one = approvalPage();
  one.based_on = { ...one.based_on, success_count: 1 };
  expect(blocks(view(one))).toContain("Fix succeeded 1 time");
  expect(blocks(view())).toContain("Fix succeeded 4 times");
});

it("DC-TX-01 the State line for every request state, the two refused texts included", () => {
  const cases: [
    ApprovalPage["state"],
    ApprovalPage["refusal_reason"],
    string,
  ][] = [
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
  ];
  for (const [state, reason, text] of cases) {
    expect(
      blocks(view(approvalPage({ state, refusal_reason: reason })))[2],
    ).toBe(`State: ${text}`);
  }
});

it("DC-U-07 the times are viewer times and Held for follows rule (2)", () => {
  const karachi: TimeFormat = (iso) => viewerTime(iso, NOW, "Asia/Karachi");
  const html = view(approvalPage(), INITIAL, SECOND, karachi);
  expect(blocks(html)).toContain("15:35:00");
  expect(blocks(html)).toContain("15:01:02");
  const other = approvalPage({ expires_at: "2026-10-09T10:35:00.000Z" });
  expect(blocks(view(other, INITIAL, SECOND, karachi))).toContain(
    "9 Oct 2026, 15:35",
  );
  const held = (seconds: number) => {
    const page = approvalPage();
    page.recovery_checks = {
      ...page.recovery_checks,
      observation_window_seconds: seconds,
    };
    return blocks(view(page)).at(-1);
  };
  expect(held(60)).toBe(
    "Held for 60 s while every monitoring source is healthy.",
  );
  expect(held(120)).toBe(
    "Held for 2 min while every monitoring source is healthy.",
  );
  expect(held(150)).toBe(
    "Held for 2 min 30 s while every monitoring source is healthy.",
  );
});
