import { expect, it } from "vitest";
import { execution, stormRun } from "../test/fixtures";
import { followTarget, moveDuration, zoomIn, zoomOut } from "./follow";

it("DC-S03-06 the follow target is the current step, else the last step execution", () => {
  const running = stormRun({
    state: "waiting",
    current_step_execution_id: execution(2, "s-wait-ack").step_execution_id,
    step_executions: [
      execution(1, "s-trigger", { state: "completed", named_output: "next" }),
      execution(2, "s-wait-ack", { state: "waiting" }),
      execution(3, "s-similar", { state: "completed", named_output: "done" }),
    ],
  });
  expect(followTarget(running)).toBe("s-wait-ack");
  expect(followTarget(stormRun())).toBe("s-resolve");
  expect(followTarget(stormRun({ step_executions: [] }))).toBeNull();
});

it("DC-S03-06 zoom steps of 10 % between 25 % and 200 %", () => {
  expect(zoomIn(0.8)).toBeCloseTo(0.9, 10);
  expect(zoomOut(0.8)).toBeCloseTo(0.7, 10);
  expect(zoomIn(0.25)).toBeCloseTo(0.3, 10);
  expect(zoomOut(0.25)).toBeCloseTo(0.25, 10);
  expect(zoomIn(0.43)).toBeCloseTo(0.5, 10);
  expect(zoomOut(0.43)).toBeCloseTo(0.4, 10);
  expect(zoomIn(2)).toBeCloseTo(2, 10);
  expect(zoomOut(2)).toBeCloseTo(1.9, 10);
  expect(zoomIn(0.7000000001)).toBeCloseTo(0.8, 10);
});

it("VD-R-11 every move has duration 0 under reduced motion", () => {
  expect(moveDuration("follow", false)).toBe(300);
  expect(moveDuration("fit", false)).toBe(300);
  expect(moveDuration("zoom", false)).toBe(200);
  expect(moveDuration("first", false)).toBe(0);
  expect(moveDuration("resize", false)).toBe(0);
  for (const move of ["follow", "fit", "zoom", "first", "resize"] as const) {
    expect(moveDuration(move, true)).toBe(0);
  }
});
