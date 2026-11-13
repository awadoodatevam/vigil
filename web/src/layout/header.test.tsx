import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { expect, it } from "vitest";
import type { TenantSettings } from "../api/types";
import { Banner } from "./Banner";
import { Header, type HeaderProps } from "./Header";
import styles from "./layout.module.css";

const SETTINGS: TenantSettings = {
  product_display_name: "Techfinity",
  customer_name: "Jazz",
  has_logo: false,
  environment_label: "Demo environment — fictional data",
};

function render(overrides: Partial<HeaderProps> = {}, path = "/incidents") {
  const props: HeaderProps = {
    settings: SETTINGS,
    displayName: "Demo Presenter",
    full: true,
    withNavigation: true,
    onSignOut: () => {},
    signingOut: false,
    signOutFailure: null,
    now: new Date("2026-10-10T09:00:00Z"),
    zone: "Asia/Karachi",
    ...overrides,
  };
  return renderToStaticMarkup(
    <MemoryRouter initialEntries={[path]}>
      <Header {...props} />
    </MemoryRouter>,
  );
}

const text = (html: string) =>
  html.replace(/<[^>]+>/g, "|").replace(/\|+/g, "|");

it("DC-U-03 the header items from left to right", () => {
  expect(text(render())).toBe(
    "|Techfinity|Jazz|Incidents|Times in UTC+05:00|Demo Presenter|Sign out|",
  );
});

it("DC-U-03 the customer logo when the tenant has one, with the customer name as its text", () => {
  const html = render({ settings: { ...SETTINGS, has_logo: true } });
  expect(html).toContain('src="/api/v1/public/logo" alt="Jazz"');
  expect(text(html)).toBe(
    "|Techfinity|Incidents|Times in UTC+05:00|Demo Presenter|Sign out|",
  );
});

it("DC-U-03 the reduced header of the no-access page and the sign-out failure under Sign out", () => {
  expect(
    text(render({ full: false, withNavigation: false }, "/no-access")),
  ).toBe("|Techfinity|Jazz|Demo Presenter|Sign out|");
  expect(text(render({ signOutFailure: "No connection." }))).toContain(
    "|Sign out|Could not sign out. No connection.|",
  );
});

it("DC-U-04 the navigation only with view, the current item marked", () => {
  expect(render({ withNavigation: false })).not.toContain("<nav");
  const current = render();
  expect(current).toContain('aria-current="page"');
  expect(current).toContain(styles.navCurrent);
  const other = render({}, "/incidents/00000000-0000-4000-8000-000000000000");
  expect(other).toContain('href="/incidents"');
  expect(other).not.toContain('aria-current="page"');
  expect(other).not.toContain(styles.navCurrent);
});

it("DC-U-01 the banner shows the label exactly, and no banner for a null or empty label", () => {
  expect(
    renderToStaticMarkup(<Banner label="Demo environment — fictional data" />),
  ).toBe(
    `<div class="${styles.banner}" data-testid="environment-banner">Demo environment — fictional data</div>`,
  );
  expect(renderToStaticMarkup(<Banner label={null} />)).toBe("");
  expect(renderToStaticMarkup(<Banner label="" />)).toBe("");
});
