// One node type per step type (AR-W-03, AR-T-09), each rendering the step card.
import type { NodeProps, NodeTypes } from "@xyflow/react";
import type { StepType } from "../api/types";
import type { StepNode } from "./graph";
import { StepCard } from "./StepCard";

export const STEP_TYPES: readonly StepType[] = [
  "trigger",
  "condition",
  "diagnostic",
  "ai_task",
  "approval",
  "action",
  "recovery_check",
  "escalation",
  "notification",
  "ticket",
  "wait",
];

function stepNode(type: StepType) {
  function Node({ data }: NodeProps<StepNode>) {
    return <StepCard data={data} />;
  }
  Node.displayName = `StepNode(${type})`;
  return Node;
}

export const nodeTypes: NodeTypes = Object.fromEntries(
  STEP_TYPES.map((type) => [type, stepNode(type)]),
);
