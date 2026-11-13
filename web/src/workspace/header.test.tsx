import { expect, it } from "vitest";
import badges from "../badges/badges.module.css";
import { at, DB_OPS, header, IT_APPS } from "../test/fixtures";
import { render, textOf } from "../test/render";
import { WorkspaceHeader } from "./Header";
import styles from "./workspace.module.css";

/** The labelled values, each as its text. */
const values = (html: string) =>
  [
    ...html.matchAll(
      new RegExp(`<span class="${styles.pair}">(.*?)</span></span>`, "g"),
    ),
  ].map((match) => textOf(match[1] + "</span>"));

const pairs = (over: Parameters<typeof header>[0] = {}) =>
  values(render(<WorkspaceHeader incident={header(over)} />));

it("DC-S03-02 every labelled value in order", () => {
  const html = render(<WorkspaceHeader incident={header()} />);
  expect(html).toContain("<h1");
  expect(textOf(html.match(/<h1[^>]*>(.*?)<\/h1>/)![1])).toBe(
    "INC-0002 Subscriber DB server: Disk full — 3 services affected",
  );
  expect(values(html)).toEqual([
    "State: Open",
    "Stage: Response",
    "Severity: P1",
    "Owner: Database Operations",
    "Informed: VAS Service Owners",
    "Rule: Critical service affected",
    "Acknowledged: Hira Baig (NOC L1), 10:07:30",
    "Detected: 10:00:12",
    "Ticket: Jira NOC-2",
  ]);
  const link = html.match(/<a [^>]*>/)![0];
  expect(link).toContain('href="https://jira.example/browse/NOC-2"');
  expect(link).toContain('target="_blank"');
  expect(link).toContain('rel="noopener noreferrer"');
});

it("DC-S03-02 the conditional values", () => {
  expect(pairs({ stage: null })).not.toContain("Stage: Response");
  expect(pairs({ stage: null })[1]).toBe("Severity: P1");
  expect(
    pairs({ escalation: { level: 1, teams: [IT_APPS, DB_OPS] } }),
  ).toContain("Escalated: level 1, Database Operations, IT Applications");
  for (const state of ["resolved", "closed"] as const) {
    expect(
      pairs({ state, stage: null, resolved_at: at("10:11:00") }),
    ).toContain("Resolved: 10:11:00");
  }
  expect(pairs().some((value) => value.startsWith("Resolved:"))).toBe(false);
  expect(pairs({ reopen_count: 1 })).toContain("Reopened: 1 time");
  expect(pairs({ reopen_count: 2 })).toContain("Reopened: 2 times");
  expect(pairs().some((value) => value.startsWith("Reopened:"))).toBe(false);
  const none = pairs({
    informed: [],
    acknowledged: null,
    ticket: null,
    severity: null,
    owner: null,
    rule_name: null,
    stage: null,
  });
  expect(none).toEqual([
    "State: Open",
    "Severity: ",
    "Owner: ",
    "Informed: none",
    "Rule: ",
    "Acknowledged: no",
    "Detected: 10:00:12",
    "Ticket: none",
  ]);
  expect(
    pairs({ state: "resolved", resolved_at: null, stage: null }),
  ).toContain("Resolved: ");
});

it("VD-C-17 the state chip, stage chip and severity badge in the header", () => {
  const html = render(<WorkspaceHeader incident={header()} />);
  expect(html).toContain(
    `<span class="${badges.state}" data-state="open">Open</span>`,
  );
  expect(html).toContain(`<span class="${badges.stage}">Response</span>`);
  expect(html).toContain(
    `<span class="${badges.severity}" data-severity="P1">P1</span>`,
  );
  const unacknowledged = render(
    <WorkspaceHeader incident={header({ acknowledged: null })} />,
  );
  expect(unacknowledged).toContain(`<span class="${styles.no}">no</span>`);
  expect(html).toContain(`<span class="${styles.pairLabel}">Severity:</span>`);
});

it("SL-A2-03 the escalation is shown in the header", () => {
  expect(pairs({ escalation: { level: 2, teams: [IT_APPS] } })).toContain(
    "Escalated: level 2, IT Applications",
  );
  expect(pairs().some((value) => value.startsWith("Escalated:"))).toBe(false);
});
