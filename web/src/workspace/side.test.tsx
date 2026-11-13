import { expect, it } from "vitest";
import { execution, notified, stormRun } from "../test/fixtures";
import { render, texts } from "../test/render";
import { NotifiedContent, RunStepsContent } from "./Side";
import styles from "./workspace.module.css";

it("DC-S03-12 one line per step execution with its output, Running, Waiting or Cancelled; No workflow run.", () => {
  const lines = texts(render(<RunStepsContent run={stormRun()} />), "li");
  expect(lines).toHaveLength(13);
  expect(lines[0]).toBe("Incident created or reopenednextTrigger10:00:31");
  expect(lines[1]).toBe("Severityp1Condition10:00:32");
  expect(lines[12]).toBe("Resolve Jira ticketdoneTicket10:00:43");
  const run = stormRun({
    state: "waiting",
    step_executions: [
      execution(1, "s-trigger", { state: "completed", named_output: "next" }),
      execution(2, "s-recover", { state: "running" }),
      execution(3, "s-wait-ack", { state: "waiting" }),
      execution(4, "s-approve", { state: "cancelled" }),
    ],
  });
  expect(
    texts(
      render(<RunStepsContent run={run} />),
      "span",
      ` class="${styles.stepState}"`,
    ),
  ).toEqual(["Running", "Waiting", "Cancelled"]);
  expect(texts(render(<RunStepsContent run={null} />), "p")).toEqual([
    "No workflow run.",
  ]);
});

it("DC-S03-14 the notified lines in order; No team notified yet.", () => {
  expect(
    texts(render(<NotifiedContent notified={notified()} />), "li"),
  ).toEqual([
    "NOC L1 — incident notification — 10:00:59",
    "Database Operations — incident notification — 10:01:00",
    "VAS Service Owners — incident notification — 10:01:00",
  ]);
  expect(
    texts(render(<NotifiedContent notified={{ teams: [] }} />), "p"),
  ).toEqual(["No team notified yet."]);
});
