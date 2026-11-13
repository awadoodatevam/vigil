// The incident list during a storm driver run (P-28 spec Section 7, CTX-D-085 (9)): run only by the suite storm, which
// tests/acceptance/storm/with-playwright.sh starts before the driver. One serial file sharing one page.
import { writeFileSync } from "node:fs";
import { expect, test, type Page } from "@playwright/test";
import { fillSignIn, signIn } from "./helpers";

const READY = "/e2e/run/storm-ready";
const MINUTE = 60 * 1000;
const EMPTY = "No incidents yet. Techfinity is watching 2 monitoring sources.";
const HEALTHY = "Monitoring: 2 of 2 sources healthy";
const STORM_TITLE = "Subscriber DB server: Disk full — 3 services affected";
const REPORT_TITLE =
  "Report export job: Report export job failed — 1 service affected";
const VIEWER_TIME =
  /^(\d{2}:\d{2}:\d{2}|\d{1,2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4}, \d{2}:\d{2})$/;

interface Sample {
  rows: string[][];
  strip: string[];
  latest: number;
}

function sample(page: Page): Promise<Sample> {
  return page.evaluate(() => ({
    rows: [...document.querySelectorAll("tbody tr")].map((row) =>
      [...row.querySelectorAll("td")].map((cell) => cell.textContent ?? ""),
    ),
    strip: [
      ...document.querySelectorAll('[data-testid="alerts-strip"] > p'),
    ].map((p) => p.textContent ?? ""),
    latest: document.querySelectorAll('[data-testid="alerts-strip"] li').length,
  }));
}

test.describe.configure({ mode: "serial", timeout: 20 * MINUTE });

let page: Page;

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage();
});

test.afterAll(async () => {
  await page.close();
});

test(
  "DC-S01-04 the reset ends the session and sign-in returns to the list",
  { tag: "@storm" },
  async () => {
    await signIn(page, "presenter");
    await expect(page).toHaveURL(/\/incidents$/);
    writeFileSync(READY, `${new Date().toISOString()}\n`);
    await expect(
      page.getByText("Your session ended. Sign in again."),
    ).toBeVisible({ timeout: 420 * 1000 });
    await expect(page).toHaveURL(/\/login\?next=%2Fincidents$/);
    await fillSignIn(page, "presenter");
    await expect(page).toHaveURL(/\/incidents$/);
    await expect(
      page.getByRole("heading", { name: "Incidents" }),
    ).toBeVisible();
    await page.evaluate(() => {
      (window as unknown as { __vigilMark?: string }).__vigilMark = "P-28";
    });
  },
);

test(
  "DC-S02-05 the new tenant's list is empty",
  { tag: "@storm" },
  async () => {
    await expect(async () => {
      const now = await sample(page);
      expect(now.rows).toEqual([]);
      expect(now.strip).toContain(HEALTHY);
      await expect(page.getByText(EMPTY, { exact: true })).toBeVisible({
        timeout: 0,
      });
    }).toPass({ timeout: MINUTE });
  },
);

test(
  "DC-S02-03 the storm builds and no forming incident is a row",
  { tag: "@storm" },
  async () => {
    const seen = { grouping: false, received: false, latest: false };
    const end = Date.now() + 10 * MINUTE;
    let rows = 0;
    while (Date.now() < end) {
      const now = await sample(page);
      rows = now.rows.length;
      expect(rows).toBeLessThanOrEqual(2);
      for (const row of now.rows) {
        expect(row[0]).toMatch(/^INC-\d{4,}$/);
      }
      seen.grouping ||= now.strip.some((text) =>
        /^Grouping in progress: \d+ alerts? (is|are) being grouped$/.test(text),
      );
      seen.received ||= now.strip.includes(
        "Alerts received in the last 10 minutes: 127",
      );
      seen.latest ||= now.latest === 10;
      if (rows === 2 && seen.grouping && seen.received && seen.latest) {
        break;
      }
      await page.waitForTimeout(1000);
    }
    expect(rows).toBe(2);
    expect(seen).toEqual({ grouping: true, received: true, latest: true });
  },
);

test("SL-A1-02 the storm and report rows", { tag: "@storm" }, async () => {
  const table = page.locator("tbody tr");
  const storm = table.filter({ hasText: STORM_TITLE });
  const report = table.filter({ hasText: REPORT_TITLE });
  const cell = (row: typeof storm, column: number) =>
    row.locator("td").nth(column);
  await expect(async () => {
    await expect(table).toHaveCount(2, { timeout: 0 });
    await expect(cell(storm, 2)).toHaveText("P1", { timeout: 0 });
    await expect(cell(storm, 5)).toHaveText("Database Operations", {
      timeout: 0,
    });
    await expect(cell(storm, 7)).toHaveText("126", { timeout: 0 });
    const link = cell(storm, 9).getByRole("link");
    await expect(link).toHaveAttribute(
      "href",
      /^http:\/\/jira\.fakes:8000\/browse\/NOC-\d+$/,
      { timeout: 0 },
    );
    await expect(link).toHaveAttribute("target", "_blank", { timeout: 0 });
    await expect(cell(report, 2)).toHaveText("P4", { timeout: 0 });
    await expect(cell(report, 5)).toHaveText("IT Applications", { timeout: 0 });
    await expect(cell(report, 7)).toHaveText("1", { timeout: 0 });
    await expect(cell(report, 6)).toHaveText("No", { timeout: 0 });
  }).toPass({ timeout: 20 * MINUTE });
  const numbers = (await table.locator("td:first-child").allTextContents()).map(
    (text) => Number(text.slice(4)),
  );
  expect(numbers[0]).toBeGreaterThan(numbers[1]);
  await expect(async () => {
    const acknowledged =
      (await cell(storm, 6).textContent({ timeout: 0 })) ?? "";
    const prefix = "Hira Baig (NOC L1), ";
    expect(acknowledged.startsWith(prefix)).toBe(true);
    expect(acknowledged.slice(prefix.length)).toMatch(VIEWER_TIME);
    await expect(cell(storm, 3)).toHaveText("Resolved", { timeout: 0 });
  }).toPass({ timeout: 20 * MINUTE });
});

test(
  "DC-U-08 the list changed without a reload",
  { tag: "@storm" },
  async () => {
    expect(
      await page.evaluate(
        () => (window as unknown as { __vigilMark?: string }).__vigilMark,
      ),
    ).toBe("P-28");
  },
);

test(
  "DC-S02-04 a click on a row opens the incident's address",
  { tag: "@storm" },
  async () => {
    const answer = await page.request.get("/api/v1/incidents");
    expect(answer.status()).toBe(200);
    const first = (await answer.json()).items[0].incident_id as string;
    await page.locator("tbody tr").first().locator("td").nth(1).click();
    await expect(page).toHaveURL(new RegExp(`/incidents/${first}$`));
  },
);
