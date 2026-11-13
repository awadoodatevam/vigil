// The incident list and the incident workspace during a storm driver run (P-28 and P-29 spec Section 7, CTX-D-085 (9),
// CTX-D-089 (12)): run only by the suite storm, which tests/acceptance/storm/with-playwright.sh starts before the
// driver. One serial file sharing one page for the list and a second browser context for the storm workspace.
import { mkdirSync, writeFileSync } from "node:fs";
import {
  expect,
  test,
  type BrowserContext,
  type Locator,
  type Page,
} from "@playwright/test";
import { fillSignIn, SCREENS, screenshot, signIn } from "./helpers";
import {
  highlightDelays,
  labProfile,
  minimums,
  recorder,
  stormMode,
  type Recording,
} from "./recorder";

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
/** The storm workspace's own context and page (its own window, so its timers are not throttled). */
let workspaceContext: BrowserContext;
let workspace: Page;
let navigation: Promise<void> | null = null;
let navigationError: unknown = null;
let stormNumber = "";
let reportId = "";
const contexts: BrowserContext[] = [];
const PROFILE = labProfile();
const MODE = stormMode();
const MIN = minimums(MODE);
const ACCENT = "rgb(47, 111, 237)";
const GREY = "rgb(139, 151, 168)";
const GREEN = "rgb(14, 116, 72)";
const PATH = [
  ["s-trigger", "next"],
  ["s-severity", "p1"],
  ["s-diagnose", "done"],
  ["s-investigate", "done"],
  ["s-summarise", "done"],
  ["s-ticket", "done"],
  ["s-notify", "sent"],
  ["s-wait-ack", "next"],
  ["s-similar", "done"],
  ["s-approve", "approved"],
  ["s-action", "succeeded"],
  ["s-recover", "recovered"],
  ["s-resolve", "done"],
];
const OFF_PATH = [
  "s-escalate",
  "s-summarise-2",
  "s-ticket-2",
  "s-notify-2",
  "s-recover-2",
  "s-resolve-2",
];

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage();
});

test.afterAll(async () => {
  await page.close();
  for (const context of contexts) {
    await context.close();
  }
});

/** The incident id of the list's row with the title, read from AR-I-21. */
async function incidentId(title: string): Promise<string> {
  const answer = await page.request.get("/api/v1/incidents");
  expect(answer.status()).toBe(200);
  const items = (await answer.json()).items as {
    incident_id: string;
    number: number;
    title: string;
  }[];
  const item = items.find((row) => row.title === title)!;
  stormNumber ||=
    title === STORM_TITLE ? `INC-${String(item.number).padStart(4, "0")}` : "";
  return item.incident_id;
}

const recording = (): Promise<Recording> =>
  workspace.evaluate(
    () => (window as unknown as { __vigilRecord: Recording }).__vigilRecord,
  );

/** The texts of the <p> lines under a locator. */
const lines = (locator: Locator) => locator.locator("p").allTextContents();

/** The labelled values of the workspace header card. */
const headerValues = (target: Page) =>
  target.locator("main h1 + div > span").allTextContents();

const section = (target: Page, heading: string) =>
  target.locator("section", {
    has: target.getByRole("heading", { name: heading, exact: true }),
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
    workspaceContext = await page
      .context()
      .browser()!
      .newContext({ storageState: await page.context().storageState() });
    contexts.push(workspaceContext);
    await workspaceContext.addInitScript(recorder);
    workspace = await workspaceContext.newPage();
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
      const grouping = now.strip.some((text) =>
        /^Grouping in progress: \d+ alerts? (is|are) being grouped$/.test(text),
      );
      if (grouping && !seen.grouping) {
        await screenshot(page, "incidents-storm-grouping");
      }
      seen.grouping ||= grouping;
      seen.received ||= now.strip.includes(
        "Alerts received in the last 10 minutes: 127",
      );
      seen.latest ||= now.latest === 10;
      if (
        navigation === null &&
        now.rows.some((row) => row[1] === STORM_TITLE)
      ) {
        const id = await incidentId(STORM_TITLE);
        navigation = workspace
          .goto(`/incidents/${id}`)
          .then(() => undefined)
          .catch((error: unknown) => {
            navigationError = error;
          });
      }
      if (rows === 2 && seen.grouping && seen.received && seen.latest) {
        break;
      }
      await page.waitForTimeout(1000);
    }
    expect(rows).toBe(2);
    expect(seen).toEqual({ grouping: true, received: true, latest: true });
  },
);

test(
  "SL-A1-08 the storm workspace opens and records the run",
  { tag: "@storm" },
  async () => {
    expect(navigation).not.toBeNull();
    await navigation;
    if (navigationError !== null) {
      throw navigationError;
    }
    await expect(
      workspace.getByRole("heading", {
        name: `${stormNumber} ${STORM_TITLE}`,
        exact: true,
      }),
    ).toBeVisible({ timeout: MINUTE });
    expect((await recording()).start).toBeGreaterThan(0);
    await workspace.evaluate(() => {
      (window as unknown as { __vigilMark?: string }).__vigilMark = "P-29";
    });
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
  await screenshot(page, "incidents-storm-open");
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

test("VD-L-03 the incident table fits 1280 px", { tag: "@storm" }, async () => {
  await expect(page.locator("tbody tr")).toHaveCount(2);
  expect(page.viewportSize()?.width).toBe(1280);
  const sizes = await page.evaluate(() => {
    const table = document.querySelector("table")!;
    return {
      scrollWidth: document.documentElement.scrollWidth,
      table: table.getBoundingClientRect().width,
      card: table.parentElement!.clientWidth,
    };
  });
  expect(sizes.scrollWidth).toBeLessThanOrEqual(1280);
  expect(sizes.table).toBeLessThanOrEqual(sizes.card);
});

test("VD-C-07 the badge and chip colours", { tag: "@storm" }, async () => {
  const table = page.locator("tbody tr");
  const background = (row: string, column: number) =>
    table
      .filter({ hasText: row })
      .locator("td")
      .nth(column)
      .locator("span")
      .first()
      .evaluate((element) => getComputedStyle(element).backgroundColor);
  expect(await background(STORM_TITLE, 2)).toBe("rgb(217, 45, 32)");
  await expect(
    table.filter({ hasText: STORM_TITLE }).locator("td").nth(3),
  ).toHaveText("Resolved");
  expect(await background(STORM_TITLE, 3)).toBe("rgb(232, 247, 238)");
  expect(await background(REPORT_TITLE, 2)).toBe("rgb(230, 234, 240)");
  await expect(
    table.filter({ hasText: REPORT_TITLE }).locator("td").nth(3),
  ).toHaveText("Open");
  expect(await background(REPORT_TITLE, 3)).toBe("rgb(234, 242, 255)");
  await screenshot(page, "incidents-storm-rows");
});

test(
  "SL-A1-08 the highlight followed every step change within 2 s",
  { tag: "@storm" },
  async () => {
    const band = workspace.getByTestId("run-band");
    await expect(band.getByText("Finished", { exact: true })).toBeVisible({
      timeout: 5 * MINUTE,
    });
    const id = await incidentId(STORM_TITLE);
    const answer = await workspace.request.get(`/api/v1/incidents/${id}/run`);
    expect(answer.status()).toBe(200);
    const run = (await answer.json()).run as {
      step_executions: { step_id: string; started_at: string }[];
    };
    const record = await recording();
    const delays = highlightDelays(run.step_executions, record.changes);
    console.log(`highlight delays (ms): ${JSON.stringify(delays)}`);
    expect(delays.length).toBeGreaterThanOrEqual(MIN.steps);
    for (const { stepId, delay } of delays) {
      expect(delay, stepId).not.toBeNull();
      expect(delay!, stepId).toBeLessThanOrEqual(2000);
    }
  },
);

test(
  "DC-S03-06 follow mode keeps the current step in view",
  { tag: "@storm" },
  async () => {
    const record = await recording();
    console.log(`follow distances: ${JSON.stringify(record.distances)}`);
    expect(record.distances.length).toBeGreaterThanOrEqual(MIN.distances);
    for (const { stepId, distance, zoom } of record.distances) {
      expect(distance, stepId).toBeLessThanOrEqual(2);
      expect(zoom, stepId).toBeCloseTo(0.8, 5);
    }
  },
);

test(
  "DC-S03-07 countdowns and the observation window were counted",
  { tag: "@storm" },
  async () => {
    const record = await recording();
    for (const sample of record.waiting) {
      expect(sample.countdown, sample.stepId).toBe(true);
    }
    for (const stepId of ["s-wait-ack", "s-approve"]) {
      expect(
        record.waiting.filter((sample) => sample.stepId === stepId).length,
        stepId,
      ).toBeGreaterThanOrEqual(MIN.waiting);
    }
    const seconds = record.holding.map((text) => Number(text.split(" ")[2]));
    expect(seconds.length).toBeGreaterThanOrEqual(2);
    expect(seconds).toEqual([...seconds].sort((a, b) => a - b));
  },
);

test(
  "SL-A1-02 the storm workspace header, hypothesis, impact and alerts",
  { tag: "@storm" },
  async () => {
    await expect(async () => {
      const values = await headerValues(workspace);
      expect(values[0]).toBe("State: Resolved");
    }).toPass({ timeout: 5 * MINUTE });
    const values = await headerValues(workspace);
    const time = String(VIEWER_TIME).slice(2, -2);
    expect(values.slice(0, 5)).toEqual([
      "State: Resolved",
      "Severity: P1",
      "Owner: Database Operations",
      "Informed: VAS Service Owners",
      "Rule: Critical service affected",
    ]);
    expect(values[5]).toMatch(
      new RegExp(`^Acknowledged: Hira Baig \\(NOC L1\\), (${time})$`),
    );
    expect(values[6]).toMatch(new RegExp(`^Detected: (${time})$`));
    expect(values[7]).toMatch(new RegExp(`^Resolved: (${time})$`));
    expect(values[8]).toMatch(/^Ticket: Jira NOC-\d+$/);
    expect(values).toHaveLength(9);
    const link = workspace.locator("main h1 + div a");
    await expect(link).toHaveAttribute(
      "href",
      /^http:\/\/jira\.fakes:8000\/browse\/NOC-\d+$/,
    );
    expect(await link.getAttribute("href")).toMatch(
      new RegExp(`/${values[8].slice("Ticket: Jira ".length)}$`),
    );
    await expect(link).toHaveAttribute("target", "_blank");
    const hypothesis = workspace.locator("#hypothesis");
    const shown = await lines(hypothesis);
    expect(shown.slice(0, 4)).toEqual([
      "HypothesisSubscriber DB server (srv-db-01)",
      "Server, criticality Critical",
      "The other 6 affected components all depend on it.",
      "Chosen by the service map rule: the affected component that most other affected components depend on.",
    ]);
    const impact = shown.slice(shown.indexOf("Customer impact:"));
    expect(impact).toHaveLength(4);
    expect(impact[1]).toMatch(
      /^Balance Check \(USSD\) — criticality Critical — via /,
    );
    expect(impact[2]).toMatch(/^Bundle Purchase — criticality Critical — via /);
    expect(impact[3]).toMatch(/^SMS Notifications — criticality High — via /);
    const alerts = workspace.locator("#alerts");
    await expect(alerts.locator("h2")).toHaveText("Alerts (126)");
    await expect(alerts.locator("p").first()).toHaveText(
      "Firing 0 · Resolved 126",
    );
    const groups = alerts.locator(":scope > ul > li > button[aria-expanded]");
    await expect(groups).toHaveCount(7);
    const counts = (await groups.allTextContents()).map((text) =>
      Number(/\((\d+)\)$/.exec(text)![1]),
    );
    expect(counts.reduce((a, b) => a + b, 0)).toBe(126);
    const reason =
      /^(Same component as|Depends on the component of|The component of|Unknown component, with the same|The only alert of this incident\.)/;
    for (const index of [0, 1, 2]) {
      const group = groups.nth(index);
      await group.click();
      await expect(group).toHaveAttribute("aria-expanded", "true");
      const item = alerts.locator(":scope > ul > li").nth(index);
      const why = item.getByRole("button", { name: "Why grouped" }).first();
      await why.click();
      await expect(why).toHaveAttribute("aria-expanded", "true");
      const text = await item.locator("li p").first().textContent();
      expect(text).toMatch(reason);
    }
  },
);

test("SL-A1-03 the evidence lines", { tag: "@storm" }, async () => {
  const evidence = workspace.locator("#evidence");
  await expect(evidence.locator("h2")).toHaveText("Evidence (8)");
  const entries = evidence.locator("ul > li");
  const tags = await entries.evaluateAll((items) =>
    items.map((item) => item.querySelector("p > span")?.textContent ?? ""),
  );
  expect(tags).toEqual([
    "Observation",
    "Observation",
    "Observation",
    "AI analysis",
    "History match",
    "Approval",
    "Execution result",
    "Verified outcome",
  ]);
  const time = String(VIEWER_TIME).slice(2, -2);
  const demo = {
    fs_usage: "/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)",
    largest_dirs: "Largest folder under /data: /data/txlog_archive (8.5 GiB)",
    recent_growth:
      "8.5 GiB written to 34 files under /data in the last 15 minutes",
  };
  const form = {
    fs_usage:
      /^\/data is \d+% full \(\d+(\.\d)? of \d+(\.\d)? GiB used, \d+(\.\d)? GiB free\)$/,
    largest_dirs: /^Largest folder under \/data: \S+ \(\d+(\.\d)? GiB\)$/,
    recent_growth:
      /^\d+(\.\d)? GiB written to \d+ files under \/data in the last 15 minutes$/,
  };
  for (const [index, operation] of (
    ["fs_usage", "largest_dirs", "recent_growth"] as const
  ).entries()) {
    const shown = await lines(entries.nth(index));
    const summary = shown[0].slice("Observation".length);
    if (PROFILE === "demo") {
      expect(summary).toBe(demo[operation]);
    } else {
      expect(summary).toMatch(form[operation]);
    }
    expect(shown[1]).toBe(
      `Source: lab-db-01 via runner, operation ${operation}`,
    );
    expect(shown[2]).toMatch(new RegExp(`^Collected: (${time})$`));
  }
  const first = entries.nth(0);
  await first.getByRole("button").click();
  const values = await first.locator("div p").allTextContents();
  expect(values.some((value) => value.startsWith("used_pct: "))).toBe(true);
});

test("SL-A1-04 the AI investigation section", { tag: "@storm" }, async () => {
  const shown = await lines(workspace.locator("#ai-investigation"));
  const time = String(VIEWER_TIME).slice(2, -2);
  expect(shown[0]).toBe("AI analysis");
  expect(shown[1]).toMatch(/^Likely cause: \S/);
  expect(shown[2]).toBe("Hypothesis: Confirms");
  expect(shown[3]).toBe("Evidence used:");
  const uncertain = shown.findIndex((line) =>
    line.startsWith("Still uncertain: "),
  );
  expect(uncertain).toBeGreaterThan(4);
  expect(shown[uncertain]).toMatch(/^Still uncertain: \S/);
  expect(shown[uncertain + 1]).toBe(
    "Runbooks given: RB-API-01, RB-DB-01, RB-USSD-01",
  );
  expect(shown[uncertain + 2]).toMatch(
    /^Runbooks cited: .*RB-(API|DB|USSD)-01/,
  );
  expect(shown[uncertain + 3]).toMatch(/^Provider: Claude — \S+/);
  expect(shown[uncertain + 4]).toMatch(
    new RegExp(`^(${time}) · From the workflow$`),
  );
  expect(shown[uncertain + 5]).toMatch(/^Summary \(AI\): \S/);
});

test("SL-A1-06 the Response section", { tag: "@storm" }, async () => {
  const response = workspace.locator("#response");
  const shown = await lines(response);
  const time = String(VIEWER_TIME).slice(2, -2);
  const number = PROFILE === "demo" ? "100" : "\\d+(\\.\\d+)?";
  expect(shown.slice(0, 3)).toEqual([
    "PI-1001 Subscriber DB /data full after archive catch-up runFictional record",
    "Similarity 1.00",
    "Fix succeeded 4 times",
  ]);
  await expect(
    response.getByText("Fictional record", { exact: true }),
  ).toBeVisible();
  const from = shown.indexOf("Proposed actionPurge archived transaction logs");
  expect(from).toBeGreaterThan(2);
  expect(shown.slice(from + 1, from + 11)).toEqual([
    "path = /data/txlog_archive",
    "older_than_minutes = 1440",
    "max_bytes = 12884901888",
    "Target: Subscriber DB server (lab-db-01)",
    "Policy: Approval required",
    "Approval 1",
    "Eligible: Database Operations",
    "Approved",
    shown[from + 9],
    "Execution resultAttempt 1 of 1",
  ]);
  expect(shown[from + 9]).toMatch(
    new RegExp(`^by Usman Tariq \\(DB Ops\\) at (${time})$`),
  );
  expect(shown[from + 11]).toBe("Succeeded");
  for (const recheck of [
    "Approval still valid: passed",
    "Action, parameters and target unchanged: passed",
    "Incident still open: passed",
    "Precondition: passed",
  ]) {
    expect(shown).toContain(recheck);
  }
  expect(
    shown.some((line) =>
      new RegExp(
        `^Used percentage of one filesystem \\(mount=/data\\): ${number} ≥ 90$`,
      ).test(line),
    ),
  ).toBe(true);
  const result =
    PROFILE === "demo"
      ? /^Purge archived transaction logs on Subscriber DB server: Succeeded; 34 files removed \(8\.5 GiB\)\.$/
      : /^Purge archived transaction logs on Subscriber DB server: Succeeded; \d+ files removed \(\d+(\.\d)? GiB\)\.$/;
  expect(shown.some((line) => result.test(line))).toBe(true);
});

test("SL-A1-07 the Recovery section", { tag: "@storm" }, async () => {
  const shown = await lines(workspace.locator("#recovery"));
  const time = String(VIEWER_TIME).slice(2, -2);
  expect(shown.slice(0, 5)).toEqual([
    "Check: Subscriber DB services recovered",
    "Every alert of the incident is resolved at its source: Holding",
    "No alert of Synthetic USSD sessions is firing: Holding",
    "Monitoring sources: all healthy",
    shown[4],
  ]);
  expect(shown[4]).toMatch(
    new RegExp(`^Verified outcomeRecovered at (${time})$`),
  );
  await expect(
    workspace
      .locator("#recovery")
      .getByText("Verified outcome", { exact: true }),
  ).toBeVisible();
});

test(
  "SB-S08 the finished run on the canvas and in the run steps",
  { tag: "@storm" },
  async () => {
    const band = workspace.getByTestId("run-band");
    await expect(band.getByText("Finished", { exact: true })).toBeVisible();
    for (const [stepId, output] of PATH) {
      const card = band.locator(`[data-step-id="${stepId}"]`);
      await expect(card).toHaveAttribute("data-state", "completed");
      await expect(card.locator("div").last()).toHaveText(output);
    }
    for (const stepId of OFF_PATH) {
      await expect(band.locator(`[data-step-id="${stepId}"]`)).toHaveAttribute(
        "data-state",
        "not-reached",
      );
    }
    const strokes = await band
      .locator(".react-flow__edge")
      .evaluateAll((edges) =>
        edges.map((edge) => {
          const path = edge.querySelector(".react-flow__edge-path")!;
          const style = getComputedStyle(path);
          return [
            edge.getAttribute("data-id"),
            style.stroke,
            style.strokeWidth,
          ];
        }),
      );
    const taken = PATH.slice(0, 12).map(
      ([stepId, output]) => `${stepId}--${output}`,
    );
    expect(strokes).toHaveLength(31);
    for (const [id, stroke, width] of strokes) {
      expect([stroke, width], id!).toEqual(
        taken.includes(id!) ? [ACCENT, "2px"] : [GREY, "1.5px"],
      );
    }
    const steps = section(workspace, "Run steps").locator("ul > li");
    await expect(steps).toHaveCount(13);
    const labels = await steps.evaluateAll((items) =>
      items.map((item) => item.querySelector("div > span")?.textContent ?? ""),
    );
    expect(labels[0]).toBe("Incident created or reopened");
    expect(labels[12]).toBe("Resolve Jira ticket");
    expect(labels).toEqual([
      "Incident created or reopened",
      "Severity",
      "Check the DB server",
      "AI investigation",
      "AI summary",
      "Create Jira ticket",
      "Notify owner, informed and NOC L1",
      "Wait for acknowledgement",
      "Find similar past incident",
      "DB Ops approval",
      "Run approved fix",
      "Verify recovery",
      "Resolve Jira ticket",
    ]);
    const library = await workspace.evaluate(() => ({
      descriptions: [
        ...document.querySelectorAll(
          '[id^="react-flow__node-desc-"], [id^="react-flow__edge-desc-"]',
        ),
      ].map((element) => element.textContent),
      labelledEdges: document.querySelectorAll(".react-flow__edge[aria-label]")
        .length,
      attribution: document.querySelectorAll(".react-flow__attribution").length,
      connectable: document.querySelectorAll(".react-flow__handle.connectable")
        .length,
    }));
    expect(library.descriptions.length).toBeGreaterThanOrEqual(2);
    expect(library.descriptions.every((text) => text === "")).toBe(true);
    expect(library).toMatchObject({
      labelledEdges: 0,
      attribution: 0,
      connectable: 0,
    });
    await screenshot(workspace, "workspace-storm");
    await band.getByRole("button", { name: "Fit", exact: true }).click();
    await expect(band.getByRole("button", { name: "Follow" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
    await workspace.waitForTimeout(500);
    await screenshot(workspace, "workspace-canvas-fit");
  },
);

test(
  "VD-C-18 the band and the expanded canvas",
  { tag: "@storm" },
  async () => {
    const band = workspace.getByTestId("run-band");
    const area = band.locator(".react-flow").locator("..");
    expect((await area.boundingBox())!.height).toBeCloseTo(240, 0);
    await workspace.evaluate(() => window.scrollTo(0, 400));
    await band.getByRole("button", { name: "Expand" }).click();
    await expect(band.getByRole("button", { name: "Close" })).toBeVisible();
    const sizes = await workspace.evaluate(() => {
      const card = document
        .querySelector('[data-testid="run-band"]')!
        .getBoundingClientRect();
      return {
        top: card.top,
        bottom: card.bottom,
        header: document.querySelector("header")!.getBoundingClientRect()
          .bottom,
        height: window.innerHeight,
        scrollY: window.scrollY,
      };
    });
    expect(sizes.top).toBeCloseTo(sizes.header, 0);
    expect(sizes.bottom).toBeCloseTo(sizes.height, 0);
    expect(sizes.scrollY).toBe(0);
    const box = (await band.boundingBox())!;
    await workspace.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await workspace.mouse.wheel(0, 600);
    await workspace.waitForTimeout(300);
    expect(await workspace.evaluate(() => window.scrollY)).toBe(0);
    mkdirSync(SCREENS, { recursive: true });
    await workspace.screenshot({
      path: `${SCREENS}/workspace-canvas-expanded.png`,
      fullPage: false,
    });
    await band.getByRole("button", { name: "Close" }).click();
    await expect(band.getByRole("button", { name: "Expand" })).toBeVisible();
    const after = await workspace.evaluate(() => ({
      position: getComputedStyle(
        document.querySelector('[data-testid="run-band"]')!,
      ).position,
      overflow: document.documentElement.style.overflow,
    }));
    expect(after).toEqual({ position: "static", overflow: "" });
    expect((await area.boundingBox())!.height).toBeCloseTo(240, 0);
  },
);

test(
  "DC-S03-13 the timeline and notified teams",
  { tag: "@storm" },
  async () => {
    const time = String(VIEWER_TIME).slice(2, -2);
    const entries = section(workspace, "Timeline")
      .locator("ul")
      .first()
      .locator(":scope > li");
    const shown = await entries.evaluateAll((items) =>
      items.map((item) => ({
        actor:
          item.querySelector(":scope > div > span:last-child")?.textContent ??
          "",
        text: item.querySelector(":scope > p")?.textContent ?? "",
      })),
    );
    const oldest = shown[shown.length - 1];
    expect(oldest.text).toMatch(
      /^Grouping: 126 alerts joined; hypothesis Subscriber DB server; \d+ groups? merged$/,
    );
    expect(oldest.actor).toBe("Techfinity (automatic)");
    expect(shown).toContainEqual({
      actor: "Hira Baig (NOC L1)",
      text: "Acknowledged by Hira Baig (NOC L1).",
    });
    expect(shown.map((entry) => entry.text)).toContain(
      "Resolved: recovery verified.",
    );
    const notified = await section(workspace, "Notified")
      .locator("li")
      .allTextContents();
    for (const team of [
      "Database Operations",
      "NOC L1",
      "VAS Service Owners",
    ]) {
      expect(
        notified.some((line) =>
          new RegExp(`^${team} — incident notification — (${time})$`).test(
            line,
          ),
        ),
        team,
      ).toBe(true);
    }
  },
);

test(
  "DC-U-08 the workspace changed without a reload",
  { tag: "@storm" },
  async () => {
    expect(
      await workspace.evaluate(
        () => (window as unknown as { __vigilMark?: string }).__vigilMark,
      ),
    ).toBe("P-29");
  },
);

test("DC-U-15 the workspace tab title", { tag: "@storm" }, async () => {
  await expect(workspace).toHaveTitle(
    `${stormNumber} ${STORM_TITLE} — Techfinity`,
  );
});

test(
  "VD-S-05 the workspace fits 1280 px with its two columns",
  { tag: "@storm" },
  async () => {
    expect(workspace.viewportSize()?.width).toBe(1280);
    const sizes = await workspace.evaluate(() => {
      const main = document.getElementById("hypothesis")!.parentElement!;
      const side = main.nextElementSibling!;
      return {
        scrollWidth: document.documentElement.scrollWidth,
        main: main.getBoundingClientRect().width,
        side: side.getBoundingClientRect().width,
      };
    });
    expect(sizes.scrollWidth).toBeLessThanOrEqual(1280);
    const share = sizes.main / (sizes.main + sizes.side);
    expect(share).toBeGreaterThanOrEqual(0.65);
    expect(share).toBeLessThanOrEqual(0.67);
  },
);

test(
  "VD-C-22 the section cards and their headings",
  { tag: "@storm" },
  async () => {
    const cards = await workspace
      .locator("main section")
      .evaluateAll((sections) =>
        sections.map((element) => {
          const heading = getComputedStyle(element.querySelector("h2")!);
          return [
            heading.fontSize,
            heading.fontWeight,
            getComputedStyle(element).borderTopLeftRadius,
          ];
        }),
      );
    expect(cards.length).toBe(9);
    for (const card of cards) {
      expect(card).toEqual(["16px", "650", "10px"]);
    }
  },
);

test(
  "VD-C-23 the outcome words in their colours",
  { tag: "@storm" },
  async () => {
    const colour = (locator: Locator) =>
      locator.first().evaluate((element) => getComputedStyle(element).color);
    expect(
      await colour(
        workspace
          .locator("#recovery span")
          .getByText("Holding", { exact: true }),
      ),
    ).toBe(GREEN);
    expect(
      await colour(
        workspace
          .locator("#alerts li span")
          .getByText("Resolved", { exact: true }),
      ),
    ).toBe(GREEN);
  },
);

test(
  "DC-S03-01 the report incident's workspace",
  { tag: "@storm" },
  async ({ browser }) => {
    reportId = await incidentId(REPORT_TITLE);
    const context = await browser.newContext();
    contexts.push(context);
    const report = await context.newPage();
    await report.goto(`/incidents/${reportId}`);
    await expect(report).toHaveURL(
      new RegExp(`/login\\?next=%2Fincidents%2F${reportId}$`),
    );
    await fillSignIn(report, "presenter");
    await expect(report).toHaveURL(new RegExp(`/incidents/${reportId}$`));
    await expect(report.locator("main h1")).toHaveText(
      /^INC-\d{4,} Report export job/,
    );
    const values = await headerValues(report);
    for (const value of [
      "Severity: P4",
      "Owner: IT Applications",
      "Informed: none",
      "Acknowledged: no",
      "Stage: Recovery",
    ]) {
      expect(values).toContain(value);
    }
    await expect(report.locator("#response")).toHaveCount(0);
    await expect(report.locator("nav a", { hasText: "Response" })).toHaveCount(
      0,
    );
    await expect(async () => {
      const shown = await lines(report.locator("#recovery"));
      expect(
        shown.some((line) =>
          /^Waiting for the conditions \(gives up at .+\)$/.test(line),
        ),
      ).toBe(true);
      expect(shown.some((line) => line.endsWith(": Not holding"))).toBe(true);
    }).toPass({ timeout: MINUTE });
    const card = report.locator('[data-step-id="s-recover-2"]');
    await expect(card).toHaveAttribute("data-state", "waiting");
    await expect(card.getByText("Waiting", { exact: true })).toBeVisible();
    expect(
      await card
        .locator("span")
        .evaluateAll((spans) =>
          spans.some((span) => /^\d+:\d{2}$/.test(span.textContent ?? "")),
        ),
    ).toBe(false);
    await screenshot(report, "workspace-report");
    await context.close();
    contexts.splice(contexts.indexOf(context), 1);
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
