/// <reference types="node" />
// The dashboard's CSS Module, read as text (P-33 spec Section 7): VD-C-28 and VD-S-08.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { expect, it } from "vitest";

const CSS = readFileSync(
  fileURLToPath(new URL("./dashboard.module.css", import.meta.url)),
  "utf8",
).replace(/\/\*[\s\S]*?\*\//g, "");
const collapse = (text: string) => text.replace(/\s+/g, " ").trim();

/** The body of the rule whose selector is exactly `selector`. */
function body(selector: string): string {
  const rules = [...CSS.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
  const rule = rules.find((match) => collapse(match[1]) === selector);
  expect(rule, selector).toBeDefined();
  return collapse(rule![2]);
}

const CARD = [
  "background: var(--vd-surface);",
  "border: 1px solid var(--vd-border);",
  "border-radius: var(--vd-radius);",
  "box-shadow: var(--vd-shadow-1);",
];

it("VD-C-28 the tiles are cards of flex 1 1 240px in a wrapping row 16px apart", () => {
  const row = body(".tiles");
  expect(row).toContain("display: flex;");
  expect(row).toContain("flex-wrap: wrap;");
  expect(row).toContain("gap: var(--vd-space-4);");
  const tile = body(".tile");
  for (const declaration of [
    "display: flex;",
    "flex: 1 1 240px;",
    "flex-direction: column;",
    "padding: var(--vd-space-4) var(--vd-space-5);",
    ...CARD,
  ]) {
    expect(tile).toContain(declaration);
  }
  const name = body(".name");
  expect(name).toContain("margin: 0;");
  expect(name).toContain("font-size: var(--vd-size-13);");
  expect(name).toContain("font-weight: 500;");
  expect(name).toContain("color: var(--vd-text-2);");
  const value = body(".value, .noData");
  for (const declaration of [
    "margin: var(--vd-space-1) 0 0;",
    "font-size: var(--vd-size-30);",
    "line-height: 36px;",
    "letter-spacing: -0.02em;",
    "font-weight: 650;",
    "font-variant-numeric: tabular-nums;",
    "color: var(--vd-text);",
  ]) {
    expect(value).toContain(declaration);
  }
  expect(body(".noData")).toBe("color: var(--vd-text-3);");
  const definition = body(".definition");
  expect(definition).toContain("margin: 6px 0 var(--vd-space-3);");
  expect(definition).toContain("font-size: var(--vd-size-13);");
  expect(definition).toContain("line-height: 18px;");
  expect(definition).toContain("color: var(--vd-text-2);");
  const baseline = body(".baseline");
  for (const declaration of [
    "display: flex;",
    "flex-wrap: wrap;",
    "align-items: center;",
    "gap: var(--vd-space-1) var(--vd-space-2);",
    "margin: auto 0 0;",
    "padding-top: var(--vd-space-3);",
    "border-top: 1px solid var(--vd-border-2);",
  ]) {
    expect(baseline).toContain(declaration);
  }
  const baselineValue = body(".baselineValue");
  expect(baselineValue).toContain("font-size: var(--vd-size-14);");
  expect(baselineValue).toContain("font-weight: 600;");
  expect(baselineValue).toContain("font-variant-numeric: tabular-nums;");
  expect(baselineValue).toContain("color: var(--vd-text);");
});

it("VD-S-08 the heading row holds the heading without its own margin and Refresh on the right", () => {
  const row = body(".headingRow");
  for (const declaration of [
    "display: flex;",
    "flex-wrap: wrap;",
    "align-items: center;",
    "justify-content: space-between;",
    "gap: var(--vd-space-2) var(--vd-space-4);",
    "margin: 0 0 var(--vd-space-4);",
  ]) {
    expect(row).toContain(declaration);
  }
  expect(body(".headingRow > h1")).toBe("margin: 0;");
  const refresh = body(".refresh");
  for (const declaration of [
    "padding: 9px var(--vd-space-4);",
    "font-size: var(--vd-size-14);",
    "font-weight: 600;",
    "color: var(--vd-text);",
    ...CARD.filter((line) => !line.startsWith("border-radius")),
    "border-radius: var(--vd-radius-sm);",
  ]) {
    expect(refresh).toContain(declaration);
  }
  expect(body(".refresh:hover")).toBe("background: var(--vd-gray-25);");
  expect(body(".refresh:disabled")).toBe("opacity: 0.55; cursor: not-allowed;");
  for (const declaration of CARD) {
    expect(body(".loadingCard")).toContain(declaration);
  }
  const line = body(".dataLine");
  expect(line).toContain("margin: var(--vd-space-3) 0 0;");
  expect(line).toContain("font-size: var(--vd-size-14);");
  expect(line).toContain("color: var(--vd-text-2);");
  expect(CSS).not.toMatch(/@media/);
});
