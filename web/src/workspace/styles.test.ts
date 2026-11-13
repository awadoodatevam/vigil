/// <reference types="node" />
// The workspace's and canvas's CSS, read from the sources (P-29 spec Section 7).
import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, it } from "vitest";

const SRC = fileURLToPath(new URL("..", import.meta.url));
const read = (file: string) =>
  readFileSync(join(SRC, file), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const collapse = (text: string) => text.replace(/\s+/g, " ").trim();

/** The body of the rule whose selector is exactly `selector`. */
function body(css: string, selector: string): string {
  const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
  const rule = rules.find((match) => collapse(match[1]) === selector);
  expect(rule, selector).toBeDefined();
  return collapse(rule![2]);
}

function sources(dir = SRC): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      return entry.name === "generated" ? [] : sources(path);
    }
    return /\.(ts|tsx)$/.test(entry.name) && !entry.name.includes(".test.")
      ? [relative(SRC, path)]
      : [];
  });
}

it("VD-R-14 base.css is imported before the canvas module and style.css nowhere", () => {
  const canvas = read("canvas/RunCanvas.tsx");
  const base = canvas.indexOf('import "@xyflow/react/dist/base.css";');
  const module = canvas.indexOf('from "./canvas.module.css"');
  expect(base).toBeGreaterThanOrEqual(0);
  expect(module).toBeGreaterThan(base);
  expect(canvas).not.toMatch(/\b(Background|Controls|MiniMap)\b/);
  expect(canvas).toContain("hideAttribution: true");
  for (const file of sources()) {
    const text = read(file);
    expect(text, file).not.toContain("@xyflow/react/dist/style.css");
    if (file !== "canvas/RunCanvas.tsx") {
      expect(text, file).not.toContain("dist/base.css");
    }
  }
});

it("VD-C-19 the canvas module overrides the connection, handle and pane colours", () => {
  const css = read("canvas/canvas.module.css");
  expect(body(css, ".canvas :global(.react-flow__edge-path)")).toBe(
    "stroke: var(--vd-gray-400); stroke-width: 1.5px;",
  );
  expect(body(css, ".canvas .taken :global(.react-flow__edge-path)")).toBe(
    "stroke: var(--vd-accent-500); stroke-width: 2px;",
  );
  const handle = body(css, ".canvas :global(.react-flow__handle)");
  expect(handle).toContain("background: var(--vd-surface);");
  expect(handle).toContain("border: 1.5px solid var(--vd-gray-400);");
  expect(handle).toContain("width: 8px;");
  expect(
    body(css, '.canvas :global(.react-flow__handle)[data-taken="true"]'),
  ).toBe(
    "background: var(--vd-accent-500); border-color: var(--vd-accent-500);",
  );
  expect(body(css, ".canvas :global(.react-flow__pane)")).toBe(
    "background: var(--vd-gray-25);",
  );
  expect(
    body(css, '.card[data-state="running"], .card[data-state="waiting"]'),
  ).toBe(
    "outline: 2px solid var(--vd-accent-500); box-shadow: var(--vd-focus-ring);",
  );
  expect(body(css, ".card")).toContain("width: 180px;");
  expect(css).not.toMatch(/@keyframes/);
});

it("VD-L-05 the two columns are 66fr and 34fr", () => {
  const css = read("workspace/workspace.module.css");
  expect(body(css, ".columns")).toContain(
    "grid-template-columns: minmax(0, 66fr) minmax(0, 34fr);",
  );
  expect(body(css, ".columns")).toContain("gap: var(--vd-space-4);");
  expect(body(css, ".columns")).toContain("align-items: start;");
  expect(css).not.toMatch(/@keyframes/);
});

it("VD-C-23 the outcome words have their colours", () => {
  const css = read("workspace/workspace.module.css");
  expect(body(css, '.outcome[data-outcome="good"]')).toBe(
    "color: var(--vd-green-700);",
  );
  expect(body(css, '.outcome[data-outcome="warning"]')).toBe(
    "color: var(--vd-warning);",
  );
  expect(body(css, '.outcome[data-outcome="bad"]')).toBe(
    "color: var(--vd-red-700);",
  );
  expect(body(css, ".outcome")).toBe("font-weight: 600;");
  const alerts = read("workspace/Alerts.tsx");
  expect(alerts).toContain('<OutcomeWord outcome="warning" text="Firing" />');
  expect(alerts).toContain('<OutcomeWord outcome="good" text="Resolved" />');
  const recovery = read("workspace/Recovery.tsx");
  expect(collapse(recovery)).toContain(
    'outcome={condition.held ? "good" : "warning"} text={condition.held ? "Holding" : "Not holding"}',
  );
  expect(collapse(read("workspace/Response.tsx"))).toContain(
    'outcome={recheck.passed ? "good" : "bad"} text={recheck.passed ? "passed" : "failed"}',
  );
});
