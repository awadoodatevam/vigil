/// <reference types="node" />
import { readFileSync } from "node:fs";
import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import type { TenantSettings } from "../api/types";
import { titleOf } from "../app/title";
import { AcknowledgeView } from "../mobile/AcknowledgePage";
import { ApprovalView } from "../mobile/ApprovalPage";
import { INITIAL } from "../mobile/actionArea";
import { LiveInterrupted } from "../states/states";
import { time } from "../test/fixtures";
import { acknowledgePage, approvalPage } from "../test/mobile";
import styles from "./layout.module.css";
import mobile from "./mobile.module.css";
import { MobileBar, MobileView } from "./MobileLayout";

const SETTINGS: TenantSettings = {
  product_display_name: "Techfinity",
  customer_name: "Jazz",
  has_logo: false,
  environment_label: "Demo environment — fictional data",
};

const bar = (settings = SETTINGS, failure: string | null = null) =>
  renderToStaticMarkup(
    <MobileBar
      settings={settings}
      displayName="Hira Baig (NOC L1)"
      onSignOut={() => {}}
      signingOut={false}
      signOutFailure={failure}
    />,
  );

const page = (children: React.ReactNode, line: React.ReactNode = null) =>
  renderToStaticMarkup(
    <MobileView
      settings={SETTINGS}
      displayName="Hira Baig (NOC L1)"
      onSignOut={() => {}}
      signingOut={false}
      signOutFailure={null}
      line={line}
    >
      {children}
    </MobileView>,
  );

const text = (html: string) =>
  html.replace(/<[^>]+>/g, "|").replace(/\|+/g, "|");

const acknowledge = (
  <AcknowledgeView
    page={acknowledgePage()}
    local={INITIAL}
    format={time}
    onAcknowledge={() => {}}
  />
);

/** The markup before <main>: the banner, the bar and the interrupted line. */
const top = (html: string) => html.slice(0, html.indexOf("<main"));

it("DC-S04-02 the bar holds the product, the customer name, the display name and Sign out, and no navigation", () => {
  const html = bar();
  expect(text(html)).toBe("|Techfinity|Jazz|Hira Baig (NOC L1)|Sign out|");
  expect(html).toMatch(/^<header class="[^"]+">/);
  expect(html).not.toContain("<nav");
  expect(html).not.toContain("Times in");
  expect(html).not.toContain("<svg");
  expect(html).toContain(`class="${styles.product}"`);
  expect(html).toContain(`class="${styles.ghost} ${mobile.signOutButton}"`);
  expect(text(bar(SETTINGS, "No connection."))).toBe(
    "|Techfinity|Jazz|Hira Baig (NOC L1)|Sign out|Could not sign out. No connection.|",
  );
  const html2 = page(acknowledge);
  expect(text(top(html2))).toBe(
    "|Demo environment — fictional data|Techfinity|Jazz|Hira Baig (NOC L1)|Sign out|",
  );
  expect(html2).toContain(`<main class="${mobile.content}">`);
});

it("DC-S05-02 the Approval page has the same layout", () => {
  const approval = page(
    <ApprovalView
      page={approvalPage()}
      local={INITIAL}
      now={0}
      format={time}
      onPress={() => {}}
    />,
  );
  expect(top(approval)).toBe(top(page(acknowledge)));
  expect(approval).toContain(`<main class="${mobile.content}">`);
});

it("VD-C-24 the customer is the name chip, never the logo", () => {
  const html = bar({ ...SETTINGS, has_logo: true });
  expect(html).not.toContain("<img");
  expect(html).toContain(`<span class="${styles.customerHeader}">Jazz</span>`);
});

it("DC-U-15 Acknowledge — Techfinity and Approve — Techfinity", () => {
  expect(titleOf("Acknowledge", "Techfinity")).toBe("Acknowledge — Techfinity");
  expect(titleOf("Approve", "Techfinity")).toBe("Approve — Techfinity");
  const source = (file: string) =>
    readFileSync(new URL(`../mobile/${file}`, import.meta.url), "utf8");
  expect(source("AcknowledgePage.tsx")).toContain('screen="Acknowledge"');
  expect(source("ApprovalPage.tsx")).toContain('screen="Approve"');
});

it("DC-U-08 the live-interrupted line under the bar", () => {
  const html = page(acknowledge, <LiveInterrupted time="10:20:00" />);
  expect(text(top(html))).toBe(
    "|Demo environment — fictional data|Techfinity|Jazz|Hira Baig (NOC L1)|Sign out|Live updates interrupted. Reconnecting… Last update 10:20:00.|",
  );
  expect(top(html).indexOf("</header>")).toBeLessThan(
    top(html).indexOf("Live updates interrupted."),
  );
  expect(top(page(acknowledge))).not.toContain("Live updates interrupted.");
});
