// DC-S10 Dashboard on a tenant that has received no alert (P-33 spec Section 7): right after deploy/local.sh up or
// reset, as P-28's incidents.spec.ts. The values after a storm are checked by storm.spec.ts.
import { expect, test, type Page, type Route } from "@playwright/test";
import { password, screenshot, signIn } from "./helpers";
import { dataLineOf, METRICS, shown, tiles, type Answer } from "./dashboard";

test.use({
  viewport: { width: 1280, height: 720 },
  timezoneId: "Asia/Karachi",
});

const FOCUS_RING =
  "rgb(255, 255, 255) 0px 0px 0px 2px, rgb(47, 111, 237) 0px 0px 0px 4px";
const START_LINE =
  "Includes 0 live incidents and 50 imported past incidents labelled 'Fictional record'.";

async function openDashboard(page: Page): Promise<void> {
  await signIn(page, "presenter");
  await page.goto("/dashboard");
  await expect(page.locator("main section")).toHaveCount(4);
}

/** The requests to AR-I-80 that the page sends, answered by `answer` (by default as the server answers). */
async function count(
  page: Page,
  answer: (route: Route, n: number) => Promise<void> = (route) =>
    route.continue(),
): Promise<{ n: number }> {
  const requests = { n: 0 };
  await page.route("**/api/v1/dashboard", async (route) => {
    requests.n++;
    await answer(route, requests.n);
  });
  return requests;
}

test("SB-V-38 the starting state of the dashboard", async ({ page }) => {
  await signIn(page, "presenter");
  await page
    .getByRole("banner")
    .getByRole("link", { name: "Dashboard" })
    .click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.locator("main section")).toHaveCount(4);
  expect(await tiles(page)).toEqual([
    {
      name: "Time to acknowledge",
      value: "5.7 min",
      definition: "Mean time from detection to first acknowledgement",
      baseline: "11.0 min Illustrative baseline",
    },
    {
      name: "Time to recover",
      value: "41.1 min",
      definition: "Mean time from detection to first verified recovery",
      baseline: "72.0 min Illustrative baseline",
    },
    {
      name: "Alerts suppressed",
      value: "525",
      definition: "Alerts received minus incidents",
      baseline: "0 Illustrative baseline",
    },
    {
      name: "Incidents merged",
      value: "46",
      definition: "Incidents made from 2 or more alerts",
      baseline: "0 Illustrative baseline",
    },
  ]);
  await expect(dataLineOf(page)).toHaveText(START_LINE);
  const answer = await page.request.get("/api/v1/dashboard");
  expect(answer.status()).toBe(200);
  const expected = shown((await answer.json()) as Answer);
  const screen = await tiles(page);
  expect(screen.map((tile) => tile.name)).toEqual(METRICS);
  expect(screen.map((tile) => tile.value)).toEqual(expected.values);
  expect(screen.map((tile) => tile.baseline)).toEqual(expected.baselines);
  await expect(dataLineOf(page)).toHaveText(expected.dataLine);
  await screenshot(page, "dashboard-start-1280");
});

test("DC-U-04 the Dashboard item opens the dashboard and is marked", async ({
  page,
}) => {
  await signIn(page, "presenter");
  await expect(page).toHaveURL(/\/incidents$/);
  const header = page.getByRole("banner");
  await expect(header).toHaveText(
    /^TechfinityJazzIncidentsDashboardTimes in UTC\+05:00Demo PresenterSign out$/,
  );
  await header.getByRole("link", { name: "Dashboard" }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(header.getByRole("link", { name: "Dashboard" })).toHaveAttribute(
    "aria-current",
    "page",
  );
  await expect(
    header.getByRole("link", { name: "Incidents" }),
  ).not.toHaveAttribute("aria-current", "page");
  await expect(
    page.getByRole("heading", { level: 1, name: "Dashboard" }),
  ).toBeVisible();
});

test("DC-U-15 the dashboard's tab title", async ({ page }) => {
  await openDashboard(page);
  await expect(page).toHaveTitle("Dashboard — Techfinity");
});

test("DC-S10-04 the values are read on opening and on Refresh only", async ({
  page,
}) => {
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  const requests = await count(page, async (route, n) => {
    if (n === 1) {
      await route.continue();
      return;
    }
    const response = await route.fetch();
    const json = (await response.json()) as Answer;
    for (const metric of json.metrics) {
      if (metric.key === "alerts_suppressed" && metric.value !== null) {
        metric.value += 1;
      }
    }
    json.live_incidents = 1;
    await held;
    await route.fulfill({ response, json });
  });
  await openDashboard(page);
  expect(requests.n).toBe(1);
  await page.waitForTimeout(5000);
  expect(requests.n).toBe(1);
  const refresh = page.getByRole("button", { name: "Refresh" });
  await refresh.click();
  await expect.poll(() => requests.n).toBe(2);
  await expect(page.getByText("Loading…")).toBeVisible();
  await expect(page.locator("main section")).toHaveCount(0);
  await expect(refresh).toBeDisabled();
  await screenshot(page, "dashboard-loading-1280");
  release();
  await expect(page.locator("main section")).toHaveCount(4);
  expect((await tiles(page))[2].value).toBe("526");
  await expect(dataLineOf(page)).toHaveText(
    "Includes 1 live incident and 50 imported past incidents labelled 'Fictional record'.",
  );
  await expect(refresh).toBeEnabled();
  expect(requests.n).toBe(2);
});

test("DC-U-09 a failed read shows the page error and Try again reads again", async ({
  page,
}) => {
  const requests = await count(page, async (route, n) => {
    if (n === 2) {
      await route.fulfill({
        status: 500,
        json: {
          error: {
            code: "internal_error",
            message: "Something went wrong. Try again.",
          },
        },
      });
      return;
    }
    await route.continue();
  });
  await openDashboard(page);
  await page.getByRole("button", { name: "Refresh" }).click();
  const error = page.getByRole("alert");
  await expect(error.locator("p")).toHaveText(
    "Could not load this page. Something went wrong. Try again.",
  );
  await expect(error.getByRole("button", { name: "Try again" })).toBeVisible();
  await expect(page.locator("main h1")).toHaveCount(0);
  await expect(page.locator("main section")).toHaveCount(0);
  await screenshot(page, "dashboard-error-1280");
  await error.getByRole("button", { name: "Try again" }).click();
  await expect(page.locator("main section")).toHaveCount(4);
  await expect(dataLineOf(page)).toHaveText(START_LINE);
  expect(requests.n).toBe(3);
});

test("DC-S10-01 a user without view is sent to the no-access page", async ({
  page,
}) => {
  expect(password("noc-l1")).not.toBe("");
  await signIn(page, "noc-l1");
  await page.goto("/dashboard");
  await expect(page).toHaveURL(/\/no-access$/);
  await expect(page.locator("main section")).toHaveCount(0);
});

test("VD-R-10 the dashboard at 1280 × 720 has no horizontal scrolling", async ({
  page,
}) => {
  await openDashboard(page);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(1280);
});

test("VD-S-08 the heading row and the four tiles on one row", async ({
  page,
}) => {
  await openDashboard(page);
  const tops = await page
    .locator("main section")
    .evaluateAll((sections) =>
      sections.map((section) => section.getBoundingClientRect().top),
    );
  expect(tops).toHaveLength(4);
  expect(new Set(tops).size).toBe(1);
  const heading = (await page.locator("main h1").boundingBox())!;
  const refresh = (await page
    .getByRole("button", { name: "Refresh" })
    .boundingBox())!;
  expect(refresh.x).toBeGreaterThan(heading.x + heading.width);
  expect(refresh.y).toBeLessThan(heading.y + heading.height);
  expect(heading.y).toBeLessThan(refresh.y + refresh.height);
  const tag = page.locator("main section [data-kind]").first();
  await expect(tag).toHaveText("Illustrative baseline");
  await expect(tag).toHaveCSS("border", "1.5px dashed rgb(139, 151, 168)");
});

test("VD-R-12 Tab reaches Refresh with the focus ring", async ({ page }) => {
  await openDashboard(page);
  const button = page.getByRole("button", { name: "Refresh" });
  for (let i = 0; i < 20; i++) {
    await page.keyboard.press("Tab");
    if (
      await button.evaluate((element) => element === document.activeElement)
    ) {
      break;
    }
  }
  await expect(button).toBeFocused();
  await expect(button).toHaveCSS("box-shadow", FOCUS_RING);
});
