// The recorder of the storm workspace (P-29 spec Section 6.11 item 2): an init script of the workspace context that
// samples the page every 100 ms and keeps its values on window.__vigilRecord. The browser and vigil-app run on one
// Docker host, so Date.now() and the server's started_at share one clock.
import { existsSync, readFileSync } from "node:fs";

export interface Change {
  stepId: string;
  at: number;
}

export interface Distance {
  stepId: string;
  distance: number;
  zoom: number;
}

export interface Recording {
  start: number;
  changes: Change[];
  distances: Distance[];
  waiting: { stepId: string; countdown: boolean }[];
  holding: string[];
}

/** Runs in the page (no closure over Node values). */
export function recorder(): void {
  const record: Recording = {
    start: Date.now(),
    changes: [],
    distances: [],
    waiting: [],
    holding: [],
  };
  (window as unknown as { __vigilRecord: Recording }).__vigilRecord = record;
  let last: string | null = null;
  let pending: number | null = null;
  const centre = (rect: DOMRect) => [
    rect.left + rect.width / 2,
    rect.top + rect.height / 2,
  ];
  setInterval(() => {
    const now = Date.now();
    const current = document.querySelector('[data-current="true"]');
    const stepId = current?.getAttribute("data-step-id") ?? null;
    if (stepId !== null && stepId !== last) {
      record.changes.push({ stepId, at: now });
      last = stepId;
      pending = now;
    }
    if (pending !== null && now - pending >= 500) {
      pending = null;
      const target = document.querySelector('[data-follow-target="true"]');
      const canvas = document.querySelector(".react-flow");
      const viewport = document.querySelector(".react-flow__viewport");
      if (target && canvas && viewport) {
        const [tx, ty] = centre(target.getBoundingClientRect());
        const [cx, cy] = centre(canvas.getBoundingClientRect());
        record.distances.push({
          stepId: target.getAttribute("data-step-id") ?? "",
          distance: Math.hypot(tx - cx, ty - cy),
          zoom: new DOMMatrixReadOnly(getComputedStyle(viewport).transform).a,
        });
      }
    }
    for (const id of ["s-approve", "s-wait-ack"]) {
      const card = document.querySelector(`[data-step-id="${id}"]`);
      const spans = card ? [...card.querySelectorAll("span")] : [];
      if (spans.some((span) => span.textContent === "Waiting")) {
        record.waiting.push({
          stepId: id,
          countdown: spans.some((span) =>
            /^\d+:\d{2}$/.test(span.textContent ?? ""),
          ),
        });
      }
    }
    for (const line of document.querySelectorAll("#recovery p")) {
      const text = line.textContent ?? "";
      if (
        /^Holding for \d+ s of 60 s$/.test(text) &&
        !record.holding.includes(text)
      ) {
        record.holding.push(text);
      }
    }
  }, 100);
}

/** The lab profile of the installation (/run/state/lab-profile; test when the file does not exist). */
export function labProfile(): string {
  const file = "/run/state/lab-profile";
  return existsSync(file) ? readFileSync(file, "utf8").trim() : "test";
}

/** The storm driver's mode (/e2e/run/storm-mode, written by with-playwright.sh; immediate when it does not exist). */
export function stormMode(): string {
  const file = "/e2e/run/storm-mode";
  return existsSync(file) ? readFileSync(file, "utf8").trim() : "immediate";
}

/** The minimum counts of P-29 spec Section 6.11 item 3. */
export function minimums(mode: string) {
  return mode === "script"
    ? { steps: 3, distances: 3, waiting: 1 }
    : { steps: 1, distances: 1, waiting: 0 };
}

export interface Execution {
  step_id: string;
  started_at: string;
}

/**
 * The highlight's delay for each measured step execution (CTX-D-089 (1)): every execution that started after the
 * first recorded current card; the first recorded change at or after its start to its step or to a step started
 * later. The run's last execution, which no later step follows, is measured only when a change to it was recorded.
 */
export function highlightDelays(
  executions: Execution[],
  changes: Change[],
): { stepId: string; delay: number | null }[] {
  if (changes.length === 0) {
    return [];
  }
  const started = (e: Execution) => Date.parse(e.started_at);
  const latestStart = (stepId: string) =>
    Math.max(
      ...executions.filter((e) => e.step_id === stepId).map(started),
      -Infinity,
    );
  const last = executions[executions.length - 1];
  return executions
    .filter((e) => started(e) > changes[0].at)
    .flatMap((e) => {
      const change = changes.find(
        (c) =>
          c.at >= started(e) &&
          (c.stepId === e.step_id || latestStart(c.stepId) > started(e)),
      );
      if (change === undefined && e === last) {
        return [];
      }
      return [
        {
          stepId: e.step_id,
          delay: change === undefined ? null : change.at - started(e),
        },
      ];
    });
}
