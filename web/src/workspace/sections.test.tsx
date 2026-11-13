import { expect, it } from "vitest";
import {
  answered,
  failed,
  header,
  loading,
  response,
  stormParts,
} from "../test/fixtures";
import { render, textOf, texts } from "../test/render";
import styles from "./workspace.module.css";
import {
  mainSections,
  shownParts,
  WorkspaceView,
  type WorkspaceParts,
} from "./WorkspaceView";

const view = (parts: Partial<WorkspaceParts>) =>
  render(
    <WorkspaceView
      incident={header()}
      parts={stormParts(parts)}
      product="Techfinity"
      withCanvas={false}
    />,
  );
const headings = (html: string) => texts(html, "h2");

it("DC-S03-06 the shown sections", () => {
  const hidden = view({
    response: loading(),
    recovery: answered({ verifications: [] }),
    run: answered(null),
  });
  expect(headings(hidden)).toEqual([
    "Hypothesis and customer impact",
    "Alerts (126)",
    "Evidence (8)",
    "AI investigation",
    "Run steps",
    "Timeline",
    "Notified",
  ]);
  expect(
    shownParts(stormParts({ response: answered(response({ similar: null })) }))
      .response,
  ).toBe(false);
  const failing = view({
    response: failed(),
    recovery: failed(),
    run: failed(),
  });
  expect(headings(failing)).toEqual([
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
  expect(textOf(failing)).toContain(
    "ResponseCould not load this part. No connection.Try again",
  );
  expect(textOf(failing)).toContain(
    "WorkflowCould not load this part. No connection.Try again",
  );
  expect(headings(view({ run: loading() }))[0]).toBe("Workflow");
  expect(textOf(view({ run: loading() }))).toContain("WorkflowLoading…");
  expect(headings(view({}))[0]).toBe("Workflow");
});

it("VD-C-21 one anchor per shown section, in order, to its card's id", () => {
  const html = view({});
  const nav = html.match(/<nav[^>]*>(.*?)<\/nav>/)![1];
  expect(
    [...nav.matchAll(/<a href="([^"]+)">([^<]*)<\/a>/g)].map((m) => [
      m[1],
      m[2],
    ]),
  ).toEqual([
    ["#hypothesis", "Hypothesis and customer impact"],
    ["#alerts", "Alerts (126)"],
    ["#evidence", "Evidence (8)"],
    ["#ai-investigation", "AI investigation"],
    ["#response", "Response"],
    ["#recovery", "Recovery"],
  ]);
  for (const id of [
    "hypothesis",
    "alerts",
    "evidence",
    "ai-investigation",
    "response",
    "recovery",
  ]) {
    expect(html).toContain(`<section id="${id}" class="${styles.section}">`);
  }
  const fewer = mainSections(
    header(),
    stormParts({
      response: loading(),
      recovery: loading(),
      evidence: loading(),
    }),
  );
  expect(fewer.map((s) => s.id)).toEqual([
    "hypothesis",
    "alerts",
    "evidence",
    "ai-investigation",
  ]);
  expect(fewer[2].heading).toBe("Evidence");
});

it("VD-C-22 each section card has its heading, its empty text, Loading or PartError", () => {
  const html = view({
    evidence: answered({ entries: [] }),
    notified: answered({ teams: [] }),
    timeline: loading(),
    alerts: failed("Not found."),
  });
  const sections = [...html.matchAll(/<section[^>]*>(.*?)<\/section>/g)].map(
    (m) => textOf(m[1]),
  );
  expect(sections).toContain("Evidence (0)No evidence yet.");
  expect(sections).toContain("NotifiedNo team notified yet.");
  expect(sections).toContain("TimelineLoading…");
  expect(sections).toContain(
    "Alerts (126)Could not load this part. Not found.Try again",
  );
  expect(html).toContain(`<h2 class="${styles.heading}">Notified</h2>`);
  expect(html).toContain(`<p class="${styles.empty}">No evidence yet.</p>`);
});
