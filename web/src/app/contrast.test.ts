/// <reference types="node" />
// The WCAG 2.2 contrast of the listed colour pairs (VD-R-05, P-44 spec Section 7), with the values read from tokens.css.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { expect, it } from "vitest";

const css = readFileSync(
  fileURLToPath(new URL("./tokens.css", import.meta.url)),
  "utf8",
);
const root = /:root\s*\{([^}]*)\}/.exec(
  css.replace(/\/\*[\s\S]*?\*\//g, ""),
)![1];
const TOKENS = new Map(
  [...root.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)].map((match) => [
    match[1],
    match[2].replace(/\s+/g, " ").trim(),
  ]),
);

/** A token's colour with its var() references resolved, or a literal colour. */
function colour(name: string): string {
  if (name.startsWith("#")) {
    return name;
  }
  const value = TOKENS.get(name);
  expect(value, name).toBeDefined();
  const reference = /^var\((--[\w-]+)\)$/.exec(value!);
  return reference ? colour(reference[1]) : value!;
}

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((at) => {
    const c = parseInt(hex.slice(at, at + 2), 16) / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(foreground: string, background: string): number {
  const [a, b] = [colour(foreground), colour(background)].map(luminance);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

const pairs = (foregrounds: string[], backgrounds: string[]) =>
  foregrounds.flatMap((f) => backgrounds.map((b) => [f, b] as const));

// Text pairs: at least 4.5:1 (complete list of P-44 spec Section 7).
const TEXT = [
  ...pairs(
    ["--vd-text", "--vd-text-2", "--vd-text-3"],
    ["--vd-surface", "--vd-canvas", "--vd-surface-2"],
  ),
  ...pairs(
    ["--vd-on-navy", "--vd-on-navy-2", "--vd-on-navy-3", "--vd-on-navy-4"],
    ["--vd-navy-900"],
  ),
  ["--vd-on-navy-2", "--vd-navy-700"],
  ...pairs(["#ffffff"], ["--vd-accent-500", "--vd-red-600"]),
  ...pairs(["--vd-accent-700"], ["--vd-accent-50", "--vd-accent-100"]),
  ["--vd-accent-600", "--vd-surface"],
  ...pairs(["--vd-orange-700"], ["--vd-surface", "--vd-orange-50"]),
  ["--vd-orange-800", "--vd-orange-100"],
  ["--vd-green-700", "--vd-green-50"],
  ["--vd-red-700", "--vd-red-50"],
  ...pairs(["--vd-gray-700", "--vd-gray-600"], ["--vd-gray-100"]),
  ["--vd-banner-text", "--vd-banner"],
  ...[
    "hypothesis",
    "observation",
    "ai",
    "history",
    "proposed",
    "approval",
    "execution",
    "verified",
    "not-recovered",
    "test",
    "baseline",
    "record",
  ].map((tag) => [`--vd-tag-${tag}`, `--vd-tag-${tag}-bg`] as const),
  ...pairs(
    [
      "--vd-text",
      "--vd-text-2",
      "--vd-accent-600",
      "--vd-accent-700",
      "--vd-warning",
    ],
    ["--vd-accent-50"],
  ),
  ...pairs(["--vd-red-700"], ["--vd-surface", "--vd-canvas"]),
  ["#ffffff", "--vd-accent-600"],
  ["--vd-on-navy-2", "--vd-navy-800"],
  ["--vd-gray-700", "--vd-surface"],
  ["--vd-gray-600", "--vd-gray-50"],
  ["--vd-accent-700", "#f5f9ff"],
] as const;

// Status dots, the input border and the focus ring: at least 3:1.
const GRAPHIC = [
  ...pairs(
    ["--vd-green-600", "--vd-orange-600", "--vd-gray-500", "--vd-accent-500"],
    ["--vd-surface"],
  ),
  ["--vd-accent-500", "--vd-navy-900"],
] as const;

it("VD-R-05 every listed pair meets its contrast", () => {
  expect(TEXT).toHaveLength(51);
  for (const [foreground, background] of TEXT) {
    expect(
      contrast(foreground, background),
      `${foreground} on ${background}`,
    ).toBeGreaterThanOrEqual(4.5);
  }
  for (const [foreground, background] of GRAPHIC) {
    expect(
      contrast(foreground, background),
      `${foreground} on ${background}`,
    ).toBeGreaterThanOrEqual(3);
  }
  // The values 45-visual-design Section 13 states.
  expect(contrast("--vd-text-3", "--vd-canvas")).toBeCloseTo(4.55, 1);
  expect(contrast("#ffffff", "--vd-red-600")).toBeCloseTo(4.83, 1);
});
