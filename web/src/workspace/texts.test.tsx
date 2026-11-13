import { expect, it } from "vitest";
import {
  answered,
  header,
  investigation,
  stormParts,
  stormRun,
  timeline,
  verification,
} from "../test/fixtures";
import { render, textOf, texts } from "../test/render";
import { BandBar } from "../canvas/RunCanvas";
import styles from "./workspace.module.css";
import { WorkspaceView } from "./WorkspaceView";
import { collapse } from "./timeline";

const view = (parts = stormParts(), incident = header()) =>
  render(
    <WorkspaceView
      incident={incident}
      parts={parts}
      product="Techfinity"
      withCanvas={false}
    />,
  );
const classTexts = (html: string, name: string) =>
  texts(html, "span", ` class="${name}"`);

it("VD-R-01 the workspace's fixed texts are those of 40-demo-cut", () => {
  const html = view();
  expect(classTexts(html, styles.pairLabel)).toEqual([
    "State:",
    "Stage:",
    "Severity:",
    "Owner:",
    "Informed:",
    "Rule:",
    "Acknowledged:",
    "Detected:",
    "Ticket:",
  ]);
  expect(texts(html, "h2")).toEqual([
    "Workflow",
    "Hypothesis and customer impact",
    "Alerts (126)",
    "Evidence (8)",
    "AI investigation",
    "Response",
    "Recovery",
    "Run steps",
    "Timeline",
    "Notified",
  ]);
  expect(texts(html, "a").filter((text) => !text.startsWith("Jira"))).toEqual([
    "Hypothesis and customer impact",
    "Alerts (126)",
    "Evidence (8)",
    "AI investigation",
    "Response",
    "Recovery",
  ]);
  expect(classTexts(html, styles.lead)).toEqual([
    "Customer impact:",
    "Likely cause:",
    "Hypothesis:",
    "Evidence used:",
    "Still uncertain:",
    "Runbooks given:",
    "Runbooks cited:",
    "Provider:",
    "Summary (AI):",
    "Fix:",
    "Target:",
    "Policy:",
    "Approval 1",
    "Eligible:",
    "Check:",
  ]);
  const empty = view(
    stormParts({
      evidence: answered({ entries: [] }),
      investigation: answered(
        investigation({ result: null, summary: null, ai_status: "not_run" }),
      ),
      run: answered(null),
      notified: answered({ teams: [] }),
    }),
    header({ hypothesis: null, affected_services: [] }),
  );
  expect(texts(empty, "p", ` class="${styles.empty}"`)).toEqual([
    "No evidence yet.",
    "No AI investigation yet.",
    "No summary yet.",
    "No workflow run.",
    "No team notified yet.",
  ]);
  expect(textOf(empty)).toContain(
    "No hypothesis: no alert matched a known component.",
  );
  expect(textOf(empty)).toContain("Customer impact: none identified.");
  const bar = render(
    <BandBar
      run={stormRun()}
      follow
      expanded={false}
      onFollow={() => {}}
      onZoomIn={() => {}}
      onZoomOut={() => {}}
      onFit={() => {}}
      onExpand={() => {}}
    />,
  );
  expect(texts(bar, "button")).toEqual(["Follow", "+", "−", "Fit", "Expand"]);
  expect(textOf(view())).not.toMatch(/React Flow|s-trigger|s-notify/);
});

it("DC-TX-00 the counts and lists of the workspace", () => {
  expect(textOf(view(stormParts(), header({ reopen_count: 1 })))).toContain(
    "Reopened: 1 time",
  );
  expect(textOf(view(stormParts(), header({ reopen_count: 2 })))).toContain(
    "Reopened: 2 times",
  );
  expect(textOf(view(stormParts(), header({ informed: [] })))).toContain(
    "Informed: none",
  );
  expect(collapse(timeline())[collapse(timeline()).length - 1].text).toContain(
    "126 alerts joined",
  );
  expect(
    collapse({
      stable_grouping: {
        stable_at: "2026-10-10T10:00:00.000Z",
        alert_count: 1,
        merged_group_count: 1,
        hypothesis_name: null,
      },
      entries: [],
    })[0].text,
  ).toBe("Grouping: 1 alert joined; hypothesis none; 1 group merged");
  const informed = textOf(
    view(
      stormParts(),
      header({
        informed: [
          { team_key: "z", name: "VAS Service Owners" },
          { team_key: "a", name: "IT Applications" },
        ],
      }),
    ),
  );
  expect(informed).toContain("Informed: IT Applications, VAS Service Owners");
  expect(textOf(view())).toContain("Runbooks cited: RB-DB-01, RB-USSD-01");
  expect(textOf(view())).toContain("Fix succeeded 4 times");
  expect(
    textOf(
      view(
        stormParts({
          response: answered({
            similar: {
              state: "done",
              outcome: "match",
              score: "0.91",
              success_count: 1,
              min_similarity: "0.80",
              past_incident: {
                past_incident_key: "PI-1002",
                title: "x",
                record_label: "Fictional record",
                fix_description: "y",
              },
            },
            proposal: null,
            no_proposal: false,
            approvals: [],
            executions: [],
          }),
          recovery: answered({
            verifications: [
              verification({
                sources_healthy: false,
                unhealthy_sources: ["Lab Zabbix", "Lab Prometheus"],
                window_started_at: null,
              }),
            ],
          }),
        }),
      ),
    ),
  ).toMatch(
    /Fix succeeded 1 time(?!s).*Monitoring sources: Lab Prometheus, Lab Zabbix not healthy/,
  );
});
