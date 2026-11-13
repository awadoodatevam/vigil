import { expect, it } from "vitest";
import { CONTENT } from "../test/content";
import { at, execution, stormRun } from "../test/fixtures";
import { edgesOf, latestExecution, nodesOf } from "./graph";
import { nodeTypes, STEP_TYPES } from "./nodes";
import { canvasProps } from "./RunCanvas";

const node = (run: ReturnType<typeof stormRun>, id: string) =>
  nodesOf(run).find((n) => n.id === id)!.data;

it("DC-S03-07 each step state from the latest visit", () => {
  const run = stormRun({
    state: "waiting",
    current_step_execution_id: execution(6, "s-approve").step_execution_id,
    step_executions: [
      execution(1, "s-trigger", { state: "completed", named_output: "next" }),
      execution(2, "s-diagnose", {
        state: "completed",
        named_output: "failed",
      }),
      execution(3, "s-diagnose", {
        state: "completed",
        named_output: "done",
        visit: 2,
      }),
      execution(4, "s-investigate", { state: "running" }),
      execution(5, "s-wait-ack", { state: "cancelled" }),
      execution(6, "s-approve", {
        state: "waiting",
        timer_due_at: at("10:15:00"),
      }),
      execution(7, "s-recover", {
        state: "waiting",
        timer_due_at: at("10:15:00"),
      }),
    ],
  });
  expect(node(run, "s-severity")).toMatchObject({
    state: "not-reached",
    output: null,
    current: false,
  });
  expect(node(run, "s-trigger")).toMatchObject({
    state: "completed",
    output: "next",
    visit: 1,
  });
  expect(node(run, "s-diagnose")).toMatchObject({
    state: "completed",
    output: "done",
    visit: 2,
  });
  expect(node(run, "s-investigate")).toMatchObject({
    state: "running",
    output: null,
  });
  expect(node(run, "s-wait-ack")).toMatchObject({
    state: "cancelled",
    countdownDue: null,
  });
  expect(node(run, "s-approve")).toMatchObject({
    state: "waiting",
    countdownDue: at("10:15:00"),
    current: true,
    followTarget: true,
  });
  expect(node(run, "s-recover")).toMatchObject({
    state: "waiting",
    countdownDue: null,
  });
  expect(nodesOf(run).filter((n) => n.data.current)).toHaveLength(1);
  expect(nodesOf(run).filter((n) => n.data.followTarget)).toHaveLength(1);
  const finished = stormRun();
  expect(node(finished, "s-resolve")).toMatchObject({
    current: false,
    followTarget: true,
  });
  const same = stormRun({
    step_executions: [
      execution(1, "s-diagnose", {
        state: "completed",
        named_output: "failed",
        started_at: at("10:01:00"),
      }),
      execution(2, "s-diagnose", {
        state: "running",
        started_at: at("10:02:00"),
      }),
    ],
  });
  expect(latestExecution(same, "s-diagnose")?.state).toBe("running");
});

it("DC-S06-06 each connection runs from its output's handle to the target's handle, without label or marker; the storm run's taken connections are exactly the 12 of its path", () => {
  const edges = edgesOf(stormRun());
  expect(edges).toHaveLength(CONTENT.connections.length);
  expect(edges[0]).toEqual({
    id: "s-trigger--next",
    source: "s-trigger",
    sourceHandle: "next",
    target: "s-severity",
    targetHandle: "in",
    ariaLabel: null,
    focusable: false,
    selectable: false,
    data: { taken: true },
  });
  for (const edge of edges) {
    expect(edge.label).toBeUndefined();
    expect(edge.markerEnd).toBeUndefined();
    expect(edge.type).toBeUndefined();
  }
  expect(
    edges.filter((edge) => edge.data.taken).map((edge) => edge.id),
  ).toEqual([
    "s-trigger--next",
    "s-severity--p1",
    "s-diagnose--done",
    "s-investigate--done",
    "s-summarise--done",
    "s-ticket--done",
    "s-notify--sent",
    "s-wait-ack--next",
    "s-similar--done",
    "s-approve--approved",
    "s-action--succeeded",
    "s-recover--recovered",
  ]);
});

it("AR-W-03 one node per step, handles in DM Section 9 order, a Condition's branches then otherwise, no target handle on a Trigger", () => {
  const nodes = nodesOf(stormRun());
  expect(nodes.map((n) => n.id)).toEqual(
    CONTENT.steps.map((step) => step.step_id),
  );
  const severity = nodes.find((n) => n.id === "s-severity")!;
  expect(severity.position).toEqual({ x: 220, y: 300 });
  expect(severity.type).toBe("condition");
  expect(severity.data.outputs.map((o) => [o.key, o.text, o.taken])).toEqual([
    ["p1", "p1", true],
    ["otherwise", "otherwise", false],
  ]);
  const outputs = (id: string) =>
    nodes.find((n) => n.id === id)!.data.outputs.map((o) => o.text);
  expect(outputs("s-trigger")).toEqual(["next"]);
  expect(outputs("s-approve")).toEqual(["approved", "rejected", "expired"]);
  expect(outputs("s-recover")).toEqual(["recovered", "not recovered"]);
  expect(outputs("s-notify")).toEqual(["sent", "failed"]);
  expect(outputs("s-investigate")).toEqual(["done", "unavailable"]);
  expect(outputs("s-action")).toEqual(["succeeded", "failed"]);
  expect(nodes.find((n) => n.id === "s-trigger")!.data.target).toBe(false);
  expect(nodes.filter((n) => n.data.target)).toHaveLength(nodes.length - 1);
  for (const n of nodes) {
    expect([n.draggable, n.selectable, n.connectable, n.focusable]).toEqual([
      false,
      false,
      false,
      false,
    ]);
  }
});

it("AR-T-09 the run canvas is an @xyflow/react graph with one node type per step type", () => {
  expect(Object.keys(nodeTypes)).toEqual([...STEP_TYPES]);
  expect(STEP_TYPES).toHaveLength(11);
  expect(new Set(Object.values(nodeTypes)).size).toBe(11);
});

it("PM-M24-04 the run canvas is read-only", () => {
  const props = canvasProps(
    nodesOf(stormRun()),
    edgesOf(stormRun()),
    false,
    undefined,
  );
  expect(props).toMatchObject({
    nodesDraggable: false,
    nodesConnectable: false,
    elementsSelectable: false,
    nodesFocusable: false,
    edgesFocusable: false,
    deleteKeyCode: null,
    panOnDrag: true,
    zoomOnScroll: false,
    zoomOnPinch: false,
    zoomOnDoubleClick: false,
    panOnScroll: false,
    preventScrolling: false,
    minZoom: 0.25,
    maxZoom: 2,
    disableKeyboardA11y: true,
    proOptions: { hideAttribution: true },
    ariaLabelConfig: {
      "node.a11yDescription.default": "",
      "node.a11yDescription.keyboardDisabled": "",
      "edge.a11yDescription.default": "",
    },
  });
  expect(canvasProps([], [], true, undefined).preventScrolling).toBe(true);
  expect(Object.keys(props)).not.toEqual(
    expect.arrayContaining(["onNodesChange", "onConnect", "fitView"]),
  );
});
