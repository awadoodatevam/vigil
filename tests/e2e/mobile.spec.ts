// The mobile pages on any tenant (P-30 spec Section 7): the links of AR-I-101 and AR-I-102 with an unknown id, the
// sign-in through the address, the permission text, the code the pages load, the bar, the width and the focus ring.
import { expect, test, type Response } from "@playwright/test";
import { banner, fillSignIn, LABEL, screenshot } from "./helpers";
import { MOBILE, scrollWidth, signedInThrough } from "./mobile";

const UNKNOWN = "00000000-0000-4000-8000-000000000000";
const ACKNOWLEDGE = `/m/acknowledge/${UNKNOWN}`;
const APPROVE = `/m/approve/${UNKNOWN}`;

test("DC-U-13 an Acknowledge link opens the login and then the page", async ({
  browser,
}) => {
  const { context, page } = await signedInThrough(
    browser,
    "noc-l1",
    ACKNOWLEDGE,
  );
  await expect(
    page.getByText("Could not load this page. Not found.", { exact: true }),
  ).toBeVisible();
  await expect(banner(page)).toHaveText(LABEL);
  const bar = page.locator("header");
  await expect(bar).toContainText("Hira Baig (NOC L1)");
  await expect(bar.getByRole("button", { name: "Sign out" })).toBeVisible();
  const order = await page.evaluate(() => {
    const top = (selector: string) =>
      document.querySelector(selector)!.getBoundingClientRect().top;
    return [
      top('[data-testid="environment-banner"]'),
      top("header"),
      top("main"),
    ];
  });
  expect(order[0]).toBeLessThan(order[1]);
  expect(order[1]).toBeLessThan(order[2]);
  await context.close();
});

test("SL-B-13 an Approval link without a session shows only the login", async ({
  browser,
}) => {
  const context = await browser.newContext(MOBILE);
  const page = await context.newPage();
  await page.goto(APPROVE);
  await expect(page).toHaveURL(
    new RegExp(`/login\\?next=${encodeURIComponent(APPROVE)}$`),
  );
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page.getByText("Approve action")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
  await fillSignIn(page, "noc-l1");
  await expect(page).toHaveURL(new RegExp(`${APPROVE}$`));
  await expect(
    page.getByText("You do not have permission to approve actions.", {
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByText("Approve action")).toHaveCount(0);
  await screenshot(page, "mobile-no-permission-360");
  await context.close();
});

test("AR-W-07 the mobile pages load no canvas code", async ({ browser }) => {
  const context = await browser.newContext(MOBILE);
  const page = await context.newPage();
  const scripts: Response[] = [];
  page.on("response", (response) => {
    if (response.request().resourceType() === "script") {
      scripts.push(response);
    }
  });
  await page.goto(APPROVE);
  await fillSignIn(page, "noc-l1");
  await expect(
    page.getByText("You do not have permission to approve actions."),
  ).toBeVisible();
  await page.goto(ACKNOWLEDGE);
  await expect(
    page.getByText("Could not load this page. Not found.", { exact: true }),
  ).toBeVisible();
  expect(scripts.length).toBeGreaterThan(0);
  const names = scripts.map((response) => new URL(response.url()).pathname);
  expect(names.some((name) => /AcknowledgePage/.test(name))).toBe(true);
  expect(names.some((name) => /ApprovalPage/.test(name))).toBe(true);
  for (const response of scripts) {
    expect(await response.text(), response.url()).not.toContain("react-flow__");
  }
  await context.close();
});

test("DC-S04-02 the bar at 360 px", async ({ browser }) => {
  const { context, page } = await signedInThrough(
    browser,
    "noc-l1",
    ACKNOWLEDGE,
  );
  await expect(banner(page)).toHaveText(LABEL);
  const bar = page.locator("header");
  await expect(bar).toContainText("Techfinity");
  await expect(bar.getByText("Jazz", { exact: true })).toBeVisible();
  await expect(
    bar.getByText("Hira Baig (NOC L1)", { exact: true }),
  ).toBeVisible();
  await expect(bar.locator("img")).toHaveCount(0);
  await expect(bar.locator("nav")).toHaveCount(0);
  const signOut = bar.getByRole("button", { name: "Sign out" });
  const tryAgain = page.getByRole("button", { name: "Try again" });
  for (const button of [signOut, tryAgain]) {
    const box = await button.boundingBox();
    expect(box!.height).toBeGreaterThanOrEqual(44);
  }
  await signOut.click();
  await expect(
    page.getByText("You are signed out.", { exact: true }),
  ).toBeVisible();
  await context.close();
});

test("VD-R-10 a mobile page at 360 px has no horizontal scrolling", async ({
  browser,
}) => {
  const { context, page } = await signedInThrough(
    browser,
    "noc-l1",
    ACKNOWLEDGE,
  );
  await expect(
    page.getByText("Could not load this page. Not found."),
  ).toBeVisible();
  expect(await scrollWidth(page)).toBeLessThanOrEqual(360);
  await context.close();
});

test("VD-R-12 Tab reaches Sign out with the focus ring", async ({
  browser,
}) => {
  const { context, page } = await signedInThrough(
    browser,
    "noc-l1",
    ACKNOWLEDGE,
  );
  await expect(
    page.getByText("Could not load this page. Not found."),
  ).toBeVisible();
  let focused = "";
  for (let presses = 0; presses < 10 && focused !== "Sign out"; presses++) {
    await page.keyboard.press("Tab");
    focused = await page.evaluate(
      () => document.activeElement?.textContent ?? "",
    );
  }
  expect(focused).toBe("Sign out");
  const shadow = await page.evaluate(
    () => getComputedStyle(document.activeElement!).boxShadow,
  );
  expect(shadow).toContain("rgb(47, 111, 237)");
  expect(shadow).toContain("rgb(255, 255, 255)");
  await context.close();
});
