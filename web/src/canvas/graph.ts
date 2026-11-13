// The run canvas's graph (DC-S03-06, DC-S03-07, DC-S06-05, DC-S06-06; P-29 spec Section 6.6 items 2, 3, 5): nodes,
// edges and step states from a LiveRun, pure. The stored content's shape is `WorkflowPartCheck.stored`'s.
import type { Edge, Node } from "@xyflow/react";
import type { LiveRun, StepExecution } from "../api/types";
import { outputText, stepTypeName } from "../format/names";
import { followTarget } from "./follow";
import { cardSummary, namedOutputs, type ContentStep } from "./summaries";

export interface ContentConnection {
  from_step_id: string;
  from_output: string;
  to_step_id: string;
}

export interface RunContent {
  steps: ContentStep[];
  connections: ContentConnection[];
}

/** The run's content in its stored shape (the generated type leaves it open). */
export function contentOf(run: LiveRun): RunContent {
  const content = run.content as unknown as Partial<RunContent>;
  return { steps: content.steps ?? [], connections: content.connections ?? [] };
}

export type StepState =
  "not-reached" | "completed" | "running" | "waiting" | "cancelled";

export interface StepCardData extends Record<string, unknown> {
  stepId: string;
  label: string;
  typeName: string;
  summary: string;
  /** No target handle on a Trigger. */
  target: boolean;
  outputs: { key: string; text: string; taken: boolean }[];
  state: StepState;
  /** The named output's display text of a completed step. */
  output: string | null;
  visit: number;
  /** The pending timer of a waiting Escalation (wait for acknowledgement) or Approval step, for its countdown. */
  countdownDue: string | null;
  current: boolean;
  followTarget: boolean;
}

export type StepNode = Node<StepCardData, ContentStep["type"]>;

/** The step's latest execution: the highest visit, the latest started_at among equals. */
export function latestExecution(
  run: LiveRun,
  stepId: string,
): StepExecution | null {
  let latest: StepExecution | null = null;
  for (const execution of run.step_executions) {
    if (
      execution.step_id === stepId &&
      (latest === null ||
        execution.visit > latest.visit ||
        (execution.visit === latest.visit &&
          Date.parse(execution.started_at) >= Date.parse(latest.started_at)))
    ) {
      latest = execution;
    }
  }
  return latest;
}

const isTaken = (run: LiveRun, stepId: string, output: string) =>
  run.step_executions.some(
    (execution) =>
      execution.step_id === stepId &&
      execution.state === "completed" &&
      execution.named_output === output,
  );

function hasCountdown(step: ContentStep): boolean {
  return (
    step.type === "approval" ||
    (step.type === "escalation" &&
      step.parameters.mode === "wait_for_acknowledgement")
  );
}

export function nodesOf(run: LiveRun): StepNode[] {
  const target = followTarget(run);
  const currentStep =
    run.step_executions.find(
      (execution) =>
        execution.step_execution_id === run.current_step_execution_id,
    )?.step_id ?? null;
  return contentOf(run).steps.map((step) => {
    const latest = latestExecution(run, step.step_id);
    const state: StepState = latest === null ? "not-reached" : latest.state;
    return {
      id: step.step_id,
      type: step.type,
      position: { x: step.position.x, y: step.position.y },
      draggable: false,
      selectable: false,
      connectable: false,
      focusable: false,
      data: {
        stepId: step.step_id,
        label: step.label,
        typeName: stepTypeName(step.type),
        summary: cardSummary(step, run.team_names),
        target: step.type !== "trigger",
        outputs: namedOutputs(step).map((key) => ({
          key,
          text: outputText(key),
          taken: isTaken(run, step.step_id, key),
        })),
        state,
        output:
          state === "completed" && latest?.named_output
            ? outputText(latest.named_output)
            : null,
        visit: latest?.visit ?? 0,
        countdownDue:
          state === "waiting" && hasCountdown(step)
            ? (latest?.timer_due_at ?? null)
            : null,
        current: step.step_id === currentStep,
        followTarget: step.step_id === target,
      },
    };
  });
}

export interface RunEdge extends Edge {
  data: { taken: boolean };
}

/** No accessible name: the library leaves out `aria-label` only for null, which its type does not list (CTX-D-089 (3)). */
const NO_LABEL = null as unknown as string;

export function edgesOf(run: LiveRun): RunEdge[] {
  return contentOf(run).connections.map((connection) => ({
    id: `${connection.from_step_id}--${connection.from_output}`,
    source: connection.from_step_id,
    sourceHandle: connection.from_output,
    target: connection.to_step_id,
    targetHandle: "in",
    ariaLabel: NO_LABEL,
    focusable: false,
    selectable: false,
    data: {
      taken: isTaken(run, connection.from_step_id, connection.from_output),
    },
  }));
}
