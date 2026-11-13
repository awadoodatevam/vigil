// Follow mode and the zoom steps of the run canvas (DC-S03-06, AR-W-03, VD-R-11; P-29 spec Section 6.6 items 6, 7).
import type { LiveRun } from "../api/types";

export const FOLLOW_ZOOM = 0.8;
export const MIN_ZOOM = 0.25;
export const MAX_ZOOM = 2;

/** The step of current_step_execution_id, or when it is null the step of the last step execution; null when none. */
export function followTarget(run: LiveRun): string | null {
  const executions = run.step_executions;
  const current = executions.find(
    (execution) =>
      execution.step_execution_id === run.current_step_execution_id,
  );
  return (current ?? executions[executions.length - 1])?.step_id ?? null;
}

/** The next multiple of 0.1 above the zoom, at most 2. */
export function zoomIn(zoom: number): number {
  return Math.min(MAX_ZOOM, (Math.floor(zoom * 10 + 1e-6) + 1) / 10);
}

/** The next multiple of 0.1 below the zoom, at least 0.25. */
export function zoomOut(zoom: number): number {
  return Math.max(MIN_ZOOM, (Math.ceil(zoom * 10 - 1e-6) - 1) / 10);
}

/** The kinds of canvas move (45-visual-design Section 4.6 "Canvas move"). */
export type Move = "follow" | "fit" | "zoom" | "resize" | "first";

const DURATIONS: Record<Move, number> = {
  follow: 300,
  fit: 300,
  zoom: 200,
  resize: 0,
  first: 0,
};

/** The duration of a move in ms; 0 under prefers-reduced-motion: reduce. */
export function moveDuration(move: Move, reducedMotion: boolean): number {
  return reducedMotion ? 0 : DURATIONS[move];
}
