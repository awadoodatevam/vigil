/// <reference types="node" />
// The tokens and the CSS rules of docs/45-visual-design.md, read from the CSS sources under web/src (P-44 spec
// Section 7). Values are compared with their whitespace collapsed.
import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const SRC = fileURLToPath(new URL("..", import.meta.url));

/** Every CSS file under web/src, by its path relative to web/src. */
function cssFiles(dir = SRC): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      return cssFiles(path);
    }
    return entry.name.endsWith(".css") ? [relative(SRC, path)] : [];
  });
}

const collapse = (text: string) => text.replace(/\s+/g, " ").trim();
const read = (file: string) =>
  readFileSync(join(SRC, file), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");

interface Rule {
  selector: string;
  body: string;
}

/** The innermost rules of a stylesheet (the rules inside @media and @keyframes included). */
function rules(css: string): Rule[] {
  const found: Rule[] = [];
  const stack: number[] = [];
  let start = 0;
  for (let i = 0; i < css.length; i++) {
    if (css[i] === "{") {
      stack.push(start);
      stack.push(i);
      start = i + 1;
    } else if (css[i] === "}") {
      const open = stack.pop()!;
      const before = stack.pop()!;
      const body = css.slice(open + 1, i);
      if (!body.includes("{")) {
        found.push({
          selector: collapse(css.slice(before, open).split(/[;}]/).pop()!),
          body: collapse(body),
        });
      }
      start = i + 1;
    }
  }
  return found;
}

/** The declarations of a rule's body, in order. */
function declarations(body: string): [string, string][] {
  return body
    .split(";")
    .map((part) => part.trim())
    .filter((part) => part !== "")
    .map((part) => {
      const colon = part.indexOf(":");
      return [part.slice(0, colon).trim(), collapse(part.slice(colon + 1))];
    });
}

const TOKENS_CSS = "app/tokens.css";
const MODULES = cssFiles().filter((file) => file.endsWith(".module.css"));

function rootTokens(): Map<string, string> {
  const root = rules(read(TOKENS_CSS)).find(
    (rule) => rule.selector === ":root",
  );
  return new Map(declarations(root!.body));
}

// docs/45-visual-design.md Sections 4.1 to 4.6 (complete list), copied from the document.
const EXPECTED: [string, string][] = [
  ["--vd-gray-0", "#ffffff"],
  ["--vd-gray-25", "#f8fafc"],
  ["--vd-gray-50", "#f1f4f8"],
  ["--vd-gray-100", "#e6eaf0"],
  ["--vd-gray-200", "#d5dbe4"],
  ["--vd-gray-300", "#b8c1cd"],
  ["--vd-gray-400", "#8b97a8"],
  ["--vd-gray-500", "#647083"],
  ["--vd-gray-600", "#4a5567"],
  ["--vd-gray-700", "#353f4f"],
  ["--vd-gray-900", "#141a23"],
  ["--vd-text", "var(--vd-gray-900)"],
  ["--vd-text-2", "var(--vd-gray-600)"],
  ["--vd-text-3", "var(--vd-gray-500)"],
  ["--vd-canvas", "var(--vd-gray-50)"],
  ["--vd-surface", "var(--vd-gray-0)"],
  ["--vd-surface-2", "var(--vd-gray-25)"],
  ["--vd-border", "var(--vd-gray-200)"],
  ["--vd-border-2", "var(--vd-gray-100)"],
  ["--vd-navy-900", "#0b1526"],
  ["--vd-navy-800", "#12213a"],
  ["--vd-navy-700", "#1c2f50"],
  ["--vd-navy-600", "#2a4068"],
  ["--vd-on-navy", "#ffffff"],
  ["--vd-on-navy-2", "#cfdbf0"],
  ["--vd-on-navy-3", "#aebbd1"],
  ["--vd-on-navy-4", "#8fa0bb"],
  ["--vd-accent-50", "#eaf2ff"],
  ["--vd-accent-100", "#d3e3ff"],
  ["--vd-accent-200", "#b3cdfb"],
  ["--vd-accent-500", "#2f6fed"],
  ["--vd-accent-600", "#1f5ad6"],
  ["--vd-accent-700", "#1846ab"],
  ["--vd-red-50", "#fdecec"],
  ["--vd-red-200", "#f6c7c3"],
  ["--vd-red-600", "#d92d20"],
  ["--vd-red-700", "#b42318"],
  ["--vd-orange-50", "#fff1e6"],
  ["--vd-orange-100", "#ffe2c7"],
  ["--vd-orange-200", "#f6b98a"],
  ["--vd-orange-600", "#e8590c"],
  ["--vd-orange-700", "#b54708"],
  ["--vd-orange-800", "#9a3d06"],
  ["--vd-warning", "var(--vd-orange-700)"],
  ["--vd-green-50", "#e8f7ee"],
  ["--vd-green-600", "#13955a"],
  ["--vd-green-700", "#0e7448"],
  ["--vd-banner", "#ffd23f"],
  ["--vd-banner-text", "#1a1400"],
  ["--vd-tag-hypothesis-bg", "#f1ecfd"],
  ["--vd-tag-hypothesis", "#6e44d4"],
  ["--vd-tag-observation-bg", "#eaf0f7"],
  ["--vd-tag-observation", "#2d4e78"],
  ["--vd-tag-ai-bg", "#ecebff"],
  ["--vd-tag-ai", "#4338ca"],
  ["--vd-tag-history-bg", "#e5f6f6"],
  ["--vd-tag-history", "#0b6e70"],
  ["--vd-tag-proposed-bg", "#fff3e0"],
  ["--vd-tag-proposed", "#9a4a00"],
  ["--vd-tag-approval-bg", "var(--vd-accent-50)"],
  ["--vd-tag-approval", "var(--vd-accent-700)"],
  ["--vd-tag-execution-bg", "#e6f6fb"],
  ["--vd-tag-execution", "#0b7896"],
  ["--vd-tag-verified-bg", "var(--vd-green-50)"],
  ["--vd-tag-verified", "var(--vd-green-700)"],
  ["--vd-tag-not-recovered-bg", "var(--vd-red-50)"],
  ["--vd-tag-not-recovered", "var(--vd-red-700)"],
  ["--vd-tag-test-bg", "#ffffff"],
  ["--vd-tag-test", "#c026a3"],
  ["--vd-tag-baseline-bg", "#ffffff"],
  ["--vd-tag-baseline", "var(--vd-gray-600)"],
  ["--vd-tag-record-bg", "var(--vd-gray-100)"],
  ["--vd-tag-record", "var(--vd-gray-600)"],
  [
    "--vd-font",
    '"Inter Variable", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
  ],
  [
    "--vd-mono",
    '"JetBrains Mono Variable", ui-monospace, "SF Mono", Menlo, Consolas, monospace',
  ],
  ["--vd-size-12", "12px"],
  ["--vd-size-13", "13px"],
  ["--vd-size-14", "14px"],
  ["--vd-size-15", "15px"],
  ["--vd-size-16", "16px"],
  ["--vd-size-20", "20px"],
  ["--vd-size-24", "24px"],
  ["--vd-size-30", "30px"],
  ["--vd-space-1", "4px"],
  ["--vd-space-2", "8px"],
  ["--vd-space-3", "12px"],
  ["--vd-space-4", "16px"],
  ["--vd-space-5", "20px"],
  ["--vd-space-6", "24px"],
  ["--vd-space-8", "32px"],
  ["--vd-space-10", "40px"],
  ["--vd-radius-sm", "6px"],
  ["--vd-radius", "10px"],
  ["--vd-radius-lg", "14px"],
  ["--vd-radius-pill", "999px"],
  [
    "--vd-shadow-1",
    "0 1px 2px rgba(16, 24, 40, 0.06), 0 1px 3px rgba(16, 24, 40, 0.08)",
  ],
  [
    "--vd-shadow-2",
    "0 8px 24px rgba(16, 24, 40, 0.1), 0 2px 6px rgba(16, 24, 40, 0.06)",
  ],
  [
    "--vd-focus-ring",
    "0 0 0 2px var(--vd-gray-0), 0 0 0 4px var(--vd-accent-500)",
  ],
  ["--vd-fast", "120ms ease-out"],
];

// The literal colours VD-R-04 allows in a CSS Module (P-44 spec Section 6.2 item 4).
const ALLOWED_COLOURS = [
  "#ffffff",
  "#5aa2ff",
  "#f5f9ff",
  "#24406f",
  "#1b3d73",
  "rgba(0,0,0,0.06)",
  "rgba(11,21,38,0.5)",
];

const COLOUR = /#[0-9a-fA-F]{3,8}\b|rgba?\([^)]*\)/g;
const normal = (colour: string) => colour.replace(/\s+/g, "").toLowerCase();

/** The red, green and blue of a hex or rgb()/rgba() colour, each 0 to 255. */
function channels(colour: string): [number, number, number] {
  const value = normal(colour);
  if (value.startsWith("#")) {
    const hex =
      value.length <= 5
        ? [...value.slice(1, 4)].map((digit) => digit + digit).join("")
        : value.slice(1, 7);
    return [0, 2, 4].map((at) => parseInt(hex.slice(at, at + 2), 16)) as [
      number,
      number,
      number,
    ];
  }
  const [r, g, b] = value
    .replace(/^rgba?\(|\)$/g, "")
    .split(",")
    .map(Number);
  return [r, g, b];
}

/** HSL hue in degrees and saturation from 0 to 1. */
function hueSaturation(colour: string): [number, number] {
  const [r, g, b] = channels(colour).map((channel) => channel / 255);
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const delta = max - min;
  if (delta === 0) {
    return [0, 0];
  }
  const lightness = (max + min) / 2;
  const saturation = delta / (1 - Math.abs(2 * lightness - 1));
  let hue =
    max === r
      ? ((g - b) / delta) % 6
      : max === g
        ? (b - r) / delta + 2
        : (r - g) / delta + 4;
  hue *= 60;
  return [hue < 0 ? hue + 360 : hue, saturation];
}

describe("tokens.css and the CSS Modules", () => {
  it("VD-R-04 the tokens are those of 45-visual-design Section 4", () => {
    expect([...rootTokens()]).toEqual(
      EXPECTED.map(([name, value]) => [name, collapse(value)]),
    );
    expect(
      rules(read(TOKENS_CSS)).filter((rule) => rule.selector === ":root"),
    ).toHaveLength(1);
  });

  it("VD-R-04 every var() in a CSS Module is a declared token", () => {
    const declared = rootTokens();
    expect(MODULES.length).toBeGreaterThanOrEqual(6);
    for (const file of MODULES) {
      for (const [, name] of read(file).matchAll(/var\(\s*(--[\w-]+)/g)) {
        expect(declared.has(name), `${file}: ${name}`).toBe(true);
      }
      expect(read(file), file).not.toMatch(/(^|[\s;{])--[\w-]+\s*:/);
    }
  });

  it("VD-R-04 literal colours only where 45-visual-design allows them", () => {
    for (const file of MODULES) {
      for (const [colour] of read(file).matchAll(COLOUR)) {
        expect(ALLOWED_COLOURS, `${file}: ${colour}`).toContain(normal(colour));
      }
    }
  });

  it("VD-R-03 no text transform and no cut text", () => {
    for (const file of cssFiles()) {
      const css = read(file);
      for (const property of [
        "text-transform",
        "text-overflow",
        "line-clamp",
      ]) {
        expect(css, `${file}: ${property}`).not.toContain(property);
      }
    }
  });

  it("VD-R-07 the banner colour is used only by the banner", () => {
    for (const file of MODULES) {
      const using = rules(read(file)).filter((rule) =>
        /--vd-banner\b/.test(rule.body),
      );
      if (file === "layout/layout.module.css") {
        expect(using.map((rule) => rule.selector)).toEqual([".banner"]);
        expect(using[0].body).toContain("var(--vd-banner)");
        expect(using[0].body).toContain("var(--vd-banner-text)");
      } else {
        expect(using, file).toEqual([]);
      }
    }
    for (const file of cssFiles()) {
      let css = read(file);
      if (file === TOKENS_CSS) {
        css = css.replace(/--vd-banner(-text)?\s*:[^;]*;/g, "");
      }
      for (const [colour] of css.matchAll(COLOUR)) {
        const [hue, saturation] = hueSaturation(colour);
        expect(
          hue >= 40 && hue <= 65 && saturation > 0.6,
          `${file}: ${colour} is a yellow`,
        ).toBe(false);
      }
    }
    expect(hueSaturation(rootTokens().get("--vd-banner")!)[0]).toBeGreaterThan(
      40,
    );
  });

  it("VD-R-08 the warning item is in the warning colour", () => {
    const warning = rules(read("incidents/incidents.module.css")).filter(
      (rule) =>
        rule.selector.split(",").some((part) => part.trim() === ".warning"),
    );
    expect(warning.length).toBeGreaterThanOrEqual(1);
    for (const rule of warning) {
      expect(declarations(rule.body)).toContainEqual([
        "color",
        "var(--vd-warning)",
      ]);
    }
  });

  it("VD-R-11 animations and transitions stop under reduced motion", () => {
    expect(collapse(read(TOKENS_CSS))).toContain(
      "@media (prefers-reduced-motion: reduce) { *, *::before, *::after { animation: none !important; transition: none !important; } }",
    );
    let animations = 0;
    for (const file of MODULES) {
      const css = read(file);
      const keyframes = new Set(
        [...css.matchAll(/@keyframes\s+([\w-]+)/g)].map((match) => match[1]),
      );
      for (const rule of rules(css)) {
        for (const [property, value] of declarations(rule.body)) {
          if (property === "animation-name") {
            animations++;
            expect(keyframes.has(value), `${file}: ${value}`).toBe(true);
          } else if (property === "animation") {
            animations++;
            expect(
              value.split(" ").some((word) => keyframes.has(word)),
              `${file}: ${value}`,
            ).toBe(true);
          }
        }
      }
    }
    expect(animations).toBe(2);
  });

  it("VD-R-12 every link, button and input shows the focus ring", () => {
    expect(collapse(read(TOKENS_CSS))).toContain(
      ":is(a, button, input):focus-visible { outline: none; box-shadow: var(--vd-focus-ring) !important; }",
    );
    for (const file of MODULES) {
      expect(collapse(read(file)), file).not.toMatch(
        /outline\s*:\s*(none|0)\b/,
      );
    }
  });
});
