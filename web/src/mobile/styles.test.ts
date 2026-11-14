/// <reference types="node" />
// The mobile pages' CSS, read from the sources, and the Acknowledge page's markup (P-30 spec Section 7).
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import { MobileView } from "../layout/MobileLayout";
import layout from "../layout/mobile.module.css";
import { time } from "../test/fixtures";
import { acknowledgePage } from "../test/mobile";
import { AcknowledgeView } from "./AcknowledgePage";
import { INITIAL } from "./actionArea";
import styles from "./mobile.module.css";

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

const LAYOUT = read("layout/mobile.module.css");
const MOBILE = read("mobile/mobile.module.css");
const DIALOG = read("dialog/dialog.module.css");

it("VD-L-06 one column at most 560 px wide, body 16 px", () => {
  const content = body(LAYOUT, ".content");
  expect(content).toContain("width: 100%;");
  expect(content).toContain("max-width: 560px;");
  expect(content).toContain("box-sizing: border-box;");
  expect(content).toContain("margin: 0 auto;");
  expect(content).toContain(
    "padding: var(--vd-space-4) var(--vd-space-4) var(--vd-space-8);",
  );
  expect(content).toContain("font-size: var(--vd-size-16);");
  expect(content).toContain("line-height: 24px;");
  expect(LAYOUT).not.toMatch(/@media/);
  expect(MOBILE).not.toMatch(/@media/);
  expect(body(MOBILE, ".card")).toContain("padding: var(--vd-space-4);");
});

it("VD-L-04 every button in the mobile layout at least 44 px high", () => {
  expect(body(LAYOUT, ".content :where(button)")).toBe("min-height: 44px;");
  expect(body(LAYOUT, ".signOutButton")).toBe("min-height: 44px;");
  expect(body(MOBILE, ".area .button")).toContain("min-height: 48px;");
  expect(
    body(DIALOG, ".dialog .cancel, .dialog .primary, .dialog .destructive"),
  ).toContain("min-height: 48px;");
});

it("VD-C-25 the parameters in mono and the limits as a list", () => {
  const param = body(MOBILE, ".param");
  expect(param).toContain("font-family: var(--vd-mono);");
  expect(param).toContain("font-size: var(--vd-size-14);");
  expect(param).toContain("overflow-wrap: anywhere;");
  const limits = body(MOBILE, ".limits");
  expect(limits).toContain("list-style: disc;");
  expect(limits).toContain("padding-left: var(--vd-space-5);");
  const countdown = body(MOBILE, ".countdown");
  expect(countdown).toContain("font-family: var(--vd-mono);");
  expect(countdown).toContain("color: var(--vd-accent-700);");
  expect(countdown).toContain("font-variant-numeric: tabular-nums;");
  expect(body(MOBILE, ".heading")).toContain("font-size: var(--vd-size-20);");
  expect(body(MOBILE, ".part")).toContain("margin: var(--vd-space-4) 0 6px;");
});

it("VD-C-26 the full-width buttons and the success, warning and info notices", () => {
  const button = body(MOBILE, ".area .button");
  expect(button).toContain("width: 100%;");
  expect(button).toContain("font-size: var(--vd-size-16);");
  expect(button).toContain("background: var(--vd-accent-500);");
  expect(body(MOBILE, ".area .destructive")).toBe(
    "background: var(--vd-red-600);",
  );
  const success = body(MOBILE, ".success");
  expect(success).toContain("color: var(--vd-green-700);");
  expect(success).toContain("background: var(--vd-green-50);");
  expect(success).toContain("border: none;");
  const warning = body(MOBILE, ".warning");
  expect(warning).toContain("font-size: var(--vd-size-16);");
  expect(warning).toContain("color: var(--vd-orange-700);");
  expect(warning).toContain("background: var(--vd-orange-50);");
  const info = body(MOBILE, ".outcomeInfo");
  expect(info).toContain("color: var(--vd-accent-700);");
  expect(info).toContain("background: var(--vd-accent-50);");
  expect(body(MOBILE, ".viewOnly")).toContain("color: var(--vd-text-2);");
  const failed = body(MOBILE, ".area .failed");
  expect(failed).toContain("font-size: var(--vd-size-15);");
  expect(failed).toContain("color: var(--vd-red-700);");
  expect(body(DIALOG, ".dialog::backdrop")).toBe(
    "background: rgba(11, 21, 38, 0.5);",
  );
});

it("VD-S-06 the Acknowledge page is the bar, one card and the action area", () => {
  const html = renderToStaticMarkup(
    createElement(MobileView, {
      settings: {
        product_display_name: "Techfinity",
        customer_name: "Jazz",
        has_logo: false,
        environment_label: "Demo environment — fictional data",
      },
      displayName: "Hira Baig (NOC L1)",
      onSignOut: () => {},
      signingOut: false,
      signOutFailure: null,
      line: null,
      children: createElement(AcknowledgeView, {
        page: acknowledgePage(),
        local: INITIAL,
        format: time,
        onAcknowledge: () => {},
      }),
    }),
  );
  expect(html).toMatch(/^<div [^>]*data-testid="environment-banner">/);
  const main = html.slice(html.indexOf("<main"));
  expect(html.indexOf("<header")).toBeLessThan(html.indexOf("<main"));
  expect(
    main.startsWith(
      `<main class="${layout.content}"><div class="${styles.card}">`,
    ),
  ).toBe(true);
  // The main element's children: the card, then the action area.
  const children = [...main.matchAll(/<div class="([^"]+)">/g)]
    .map((match) => match[1])
    .filter((name) => name === styles.card || name === styles.area);
  expect(children).toEqual([styles.card, styles.area]);
  expect(main.indexOf(`<div class="${styles.area}">`)).toBeGreaterThan(
    main.lastIndexOf("</p></div>"),
  );
});
