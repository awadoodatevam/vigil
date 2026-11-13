import { ReactFlowProvider } from "@xyflow/react";
import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import badges from "../badges/badges.module.css";
import { at, execution, stormRun } from "../test/fixtures";
import { textOf } from "../test/render";
import { nodesOf, type StepCardData } from "./graph";
import { StepCard } from "./StepCard";

const card = (data: StepCardData, now?: number) =>
  renderToStaticMarkup(
    <ReactFlowProvider>
      <StepCard data={data} now={now} />
    </ReactFlowProvider>,
  );
const data = (run: ReturnType<typeof stormRun>, id: string) =>
  nodesOf(run).find((n) => n.id === id)!.data;

it("DC-S06-05 the card shows its label, type, summary and labelled outputs", () => {
  const html = card(data(stormRun(), "s-approve"));
  expect(textOf(html)).toBe(
    "DB Ops approvalApprovalEligible: Database Operations; expires after 900 sapprovedrejectedexpiredapproved",
  );
  expect(html).toContain('data-step-id="s-approve"');
  expect(html).toContain('data-state="completed"');
  expect(html).toContain(`<span class="${badges.stage}">approved</span>`);
  expect(html.match(/data-handleid="([^"]+)"/g)).toEqual([
    'data-handleid="in"',
    'data-handleid="approved"',
    'data-handleid="rejected"',
    'data-handleid="expired"',
  ]);
  expect(html.match(/data-taken="true"/g)).toHaveLength(1);
  const trigger = card(data(stormRun(), "s-trigger"));
  expect(trigger.match(/data-handleid="([^"]+)"/g)).toEqual([
    'data-handleid="next"',
  ]);
  const condition = card(data(stormRun(), "s-severity"));
  expect(textOf(condition)).toBe(
    "SeverityCondition1 branch and otherwisep1otherwisep1",
  );
  const visited = stormRun({
    step_executions: [
      execution(1, "s-diagnose", { state: "completed", named_output: "done" }),
      execution(2, "s-diagnose", {
        state: "completed",
        named_output: "done",
        visit: 2,
      }),
    ],
  });
  expect(textOf(card(data(visited, "s-diagnose")))).toMatch(/done×2$/);
  expect(card(data(stormRun(), "s-escalate"))).toContain(
    'data-state="not-reached"',
  );
});

it("DC-S03-07 the waiting card shows Waiting and the countdown", () => {
  const due = at("10:15:00");
  const run = stormRun({
    state: "waiting",
    current_step_execution_id: execution(1, "s-approve").step_execution_id,
    step_executions: [
      execution(1, "s-approve", { state: "waiting", timer_due_at: due }),
      execution(2, "s-wait-ack", { state: "waiting", timer_due_at: due }),
      execution(3, "s-recover", { state: "waiting", timer_due_at: due }),
      execution(4, "s-similar", { state: "cancelled" }),
    ],
  });
  const approve = card(data(run, "s-approve"), Date.parse(at("10:00:01")));
  expect(textOf(approve)).toMatch(/Waiting14:59$/);
  expect(approve).toContain('data-current="true"');
  expect(approve).toContain('data-follow-target="true"');
  expect(
    textOf(card(data(run, "s-wait-ack"), Date.parse(at("10:15:30")))),
  ).toMatch(/Waiting0:00$/);
  const recover = textOf(
    card(data(run, "s-recover"), Date.parse(at("10:00:01"))),
  );
  expect(recover).toMatch(/not recoveredWaiting$/);
  expect(textOf(card(data(run, "s-similar")))).toMatch(/Cancelled$/);
  expect(card(data(run, "s-similar"))).not.toContain("data-current");
});

it("SL-WE-10 the card has no control that changes it and its handles are not connectable", () => {
  for (const n of nodesOf(stormRun())) {
    const html = card(n.data);
    expect(html).not.toMatch(/<(button|input|select|textarea)\b/);
    expect(html).not.toMatch(/contenteditable|tabindex/);
    const handles = [
      ...html.matchAll(/<div [^>]*class="[^"]*react-flow__handle[^"]*"/g),
    ].map((m) => m[0]);
    expect(handles.length).toBeGreaterThan(0);
    for (const handle of handles) {
      expect(handle.match(/class="([^"]*)"/)![1].split(" ")).not.toContain(
        "connectable",
      );
    }
  }
});
