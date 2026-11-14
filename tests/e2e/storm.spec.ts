// The incident list, the incident workspace and the mobile pages during a storm driver run (P-28, P-29 and P-30 spec
// Section 7, CTX-D-085 (9), CTX-D-089 (12), CTX-D-091 (7)): run only by the suite storm, which
// tests/acceptance/storm/with-playwright.sh starts before the driver. One serial file sharing one page for the list
// and further browser contexts for the storm workspace and the mobile pages.
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
  blocks,
  scrollWidth,
  sharing,
  signedInThrough,
  viewerTime,
  watch,
  watched,
} from "./mobile";
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

// The mobile pages during the storm (P-30 spec Section 6.10 item 3): in mode immediate the pages acknowledge and
// approve and the driver waits for them; in mode script the driver acts and the pages show its action.
let stormId = "";
let requestId = "";
const ACTION = "Purge archived transaction logs";
const LIMITS = [
  "Deletes only *.arc files directly under /data/txlog_archive",
  "Deletes only files last modified more than 24 hours ago",
  "Deletes at most 12 GiB, oldest files first",
  "Runs only on the Subscriber DB server (lab-db-01)",
];
const KIND_WORDS: Record<string, string> = {
  diagnostic_result: "Observation",
  ai_investigation: "AI analysis",
  similarity_match: "History match",
};
const SNAPSHOT_CHANGED =
  "This request changed after you opened it. Reload the page to see the current request.";

/** Closes a test's own context at its end (P-30 spec Section 6.10 item 3). */
async function done(context: BrowserContext): Promise<void> {
  await context.close();
  contexts.splice(contexts.indexOf(context), 1);
}

/** A read of the page's operation with the page's own session. */
async function readAs<T>(target: Page, path: string): Promise<T> {
  const answer = await target.request.get(path);
  expect(answer.status(), path).toBe(200);
  return (await answer.json()) as T;
}

interface AcknowledgeAnswer {
  number: number;
  first_alert_fired_at: string;
  alert_count: number;
  summary: string | null;
  acknowledged: { user: { display_name: string }; at: string } | null;
}

interface ApprovalAnswer {
  sequence_in_run: number;
  state: string;
  incident: { number: number | null; title: string | null };
  expires_at: string;
  evidence: { kind: string; summary: string; collected_at: string }[];
  recovery_checks: { conditions: string[] };
  decided: { user: { display_name: string }; at: string } | null;
}

const number = (n: number | null) =>
  n === null ? "" : `INC-${String(n).padStart(4, "0")}`;

/** The content of DC-S04-03 (P-30 spec Section 7 introduction): exact texts, the rest compared with AR-I-32. */
async function acknowledgeContent(target: Page, path: string): Promise<void> {
  await expect(async () => {
    const shown = (await blocks(target)).slice(0, 8);
    const read = await readAs<AcknowledgeAnswer>(target, path);
    expect(shown).toEqual([
      "Acknowledge incident",
      `${number(read.number)} ${STORM_TITLE}`,
      "Severity: P1",
      "Owner: Database Operations",
      `Detected: ${await viewerTime(target, read.first_alert_fired_at)}`,
      `Alerts: ${read.alert_count}`,
      `Summary: ${read.summary ?? ""}`,
      "State: Open",
    ]);
  }).toPass({ timeout: MINUTE });
}

/** The content of DC-S05-03 (P-30 spec Section 7 introduction): exact texts, the rest compared with AR-I-34. */
async function approvalContent(target: Page, path: string): Promise<void> {
  await expect(async () => {
    const read = await readAs<ApprovalAnswer>(target, path);
    const expected: (string | RegExp)[] = [
      "Approve action",
      ...(read.sequence_in_run > 1
        ? [
            `Approval ${read.sequence_in_run}: your approval must differ from earlier approvals in this run.`,
          ]
        : []),
      `${number(read.incident.number)} ${read.incident.title ?? ""}`,
      "State: Waiting for approval",
      "Action",
      ACTION,
      "Parameters",
      "path = /data/txlog_archive",
      "older_than_minutes = 1440",
      "max_bytes = 12884901888",
      "Target server",
      "Subscriber DB server (lab-db-01)",
      "Limits",
      ...LIMITS,
      "Attempt 1 of 1",
      "Expires",
      await viewerTime(target, read.expires_at),
      /^in \d+ min \d+ s$/,
      "Based on",
      "PI-1001 Subscriber DB /data full after archive catch-up runFictional record",
      "Similarity 1.00",
      "Fix succeeded 4 times",
    ];
    for (const entry of read.evidence) {
      expected.push(`${KIND_WORDS[entry.kind]}${entry.summary}`);
      expected.push(await viewerTime(target, entry.collected_at));
    }
    expected.push(
      "Recovery checks that will be used",
      "Check: Subscriber DB services recovered",
      ...read.recovery_checks.conditions,
      "Held for 60 s while every monitoring source is healthy.",
    );
    const shown = (await blocks(target)).slice(0, expected.length);
    expect(shown).toHaveLength(expected.length);
    expected.forEach((text, index) => {
      if (text instanceof RegExp) {
        expect(shown[index]).toMatch(text);
      } else {
        expect(shown[index]).toBe(text);
      }
    });
    expect(read.incident.title).toBe(STORM_TITLE);
    const words = read.evidence.map((entry) => KIND_WORDS[entry.kind]).sort();
    expect(words).toEqual([
      "AI analysis",
      "History match",
      "Observation",
      "Observation",
      "Observation",
    ]);
  }).toPass({ timeout: MINUTE });
}

/** The page's own buttons in its action area (not the dialog's). */
const pageButton = (target: Page, name: string) =>
  target.locator("main > div > button", { hasText: new RegExp(`^${name}$`) });

test(
  "SL-A1-05 noc-l1 acknowledges on the page",
  { tag: "@storm" },
  async ({ browser }) => {
    stormId = await incidentId(STORM_TITLE);
    await expect(async () => {
      const teams = await readAs<{ teams: { team_key: string }[] }>(
        page,
        `/api/v1/incidents/${stormId}/notified-teams`,
      );
      expect(teams.teams.map((team) => team.team_key)).toContain("noc-l1");
    }).toPass({ timeout: 10 * MINUTE });
    const path = `/m/acknowledge/${stormId}`;
    const api = `/api/v1/incidents/${stormId}/acknowledgement`;
    const viewer = await sharing(page, path);
    contexts.push(viewer.context);
    await acknowledgeContent(viewer.page, api);
    await expect(
      viewer.page.getByText("View only.", { exact: true }),
    ).toBeVisible();
    await watch(viewer.page, "Already acknowledged by ");

    const noc = await signedInThrough(browser, "noc-l1", path);
    contexts.push(noc.context);
    await acknowledgeContent(noc.page, api);
    await screenshot(noc.page, "acknowledge-360");
    if (MODE === "immediate") {
      await pageButton(noc.page, "Acknowledge").click();
      await expect(
        noc.page.getByText(/^Acknowledged by Hira Baig \(NOC L1\) at /),
      ).toBeVisible();
    } else if (
      (await readAs<AcknowledgeAnswer>(noc.page, api)).acknowledged === null
    ) {
      await expect(pageButton(noc.page, "Acknowledge")).toBeVisible();
    }
    await expect(async () => {
      expect(
        (await readAs<AcknowledgeAnswer>(noc.page, api)).acknowledged,
      ).not.toBeNull();
    }).toPass({ timeout: 10 * MINUTE });
    const read = await readAs<AcknowledgeAnswer>(noc.page, api);
    const at = read.acknowledged!.at;
    const time = await viewerTime(noc.page, at);
    if (MODE === "immediate") {
      await expect(
        noc.page.getByText(`Acknowledged by Hira Baig (NOC L1) at ${time}.`, {
          exact: true,
        }),
      ).toBeVisible();
      await expect(pageButton(noc.page, "Acknowledge")).toHaveCount(0);
      await screenshot(noc.page, "acknowledge-done-360");
    } else {
      await expect(
        noc.page.getByText(
          `Already acknowledged by Hira Baig (NOC L1) at ${time}.`,
          {
            exact: true,
          },
        ),
      ).toBeVisible({ timeout: 10 * MINUTE });
    }
    await expect(
      viewer.page.getByText(
        `Already acknowledged by Hira Baig (NOC L1) at ${time}.`,
        {
          exact: true,
        },
      ),
    ).toBeVisible();
    const seen = await watched(viewer.page);
    expect(seen.sameDocument).toBe(true);
    expect(seen.shown).not.toBeNull();
    expect(seen.shown! - Date.parse(at)).toBeLessThanOrEqual(2000);
    await done(noc.context);
    await done(viewer.context);
  },
);

test(
  "SL-A1-06 dbops-engineer approves with confirmation",
  { tag: "@storm" },
  async ({ browser }) => {
    await expect(async () => {
      const response = await readAs<{
        approvals: { approval_request_id: string; sequence_in_run: number }[];
      }>(page, `/api/v1/incidents/${stormId}/response`);
      const first = [...response.approvals].sort(
        (a, b) => a.sequence_in_run - b.sequence_in_run,
      )[0];
      expect(first).toBeDefined();
      requestId = first.approval_request_id;
    }).toPass({ timeout: 10 * MINUTE });
    const path = `/m/approve/${requestId}`;
    const api = `/api/v1/approval-requests/${requestId}`;
    const viewer = await sharing(page, path);
    contexts.push(viewer.context);
    const countdown = viewer.page.getByText(/^in \d+ min \d+ s$/);
    await expect(countdown).toBeVisible({ timeout: MINUTE });
    const first = (await countdown.textContent()) ?? "";
    await expect(countdown).not.toHaveText(first, { timeout: 3000 });
    await expect(
      viewer.page.getByText("View only.", { exact: true }),
    ).toBeVisible();
    await watch(viewer.page, "Already approved by ");

    const engineer = await signedInThrough(browser, "dbops-engineer", path);
    contexts.push(engineer.context);
    await approvalContent(engineer.page, api);
    await screenshot(engineer.page, "approve-360");
    if (MODE === "immediate") {
      const sent: string[] = [];
      engineer.page.on("request", (request) => {
        if (
          request.method() === "POST" &&
          request.url().endsWith("/decisions")
        ) {
          sent.push(request.postData() ?? "");
        }
      });
      const reject = pageButton(engineer.page, "Reject");
      expect(
        await reject.evaluate(
          (button) => getComputedStyle(button).backgroundColor,
        ),
      ).toBe("rgb(217, 45, 32)");
      const dialog = engineer.page.locator("dialog");
      await engineer.page.route(
        "**/decisions",
        async (route) => {
          const body = JSON.parse(route.request().postData() ?? "{}");
          body.snapshot_hash_shown = `${body.snapshot_hash_shown}-changed`;
          await route.continue({ postData: JSON.stringify(body) });
        },
        { times: 1 },
      );
      await pageButton(engineer.page, "Approve").click();
      await expect(dialog).toBeVisible();
      await dialog.getByRole("button", { name: "Approve" }).click();
      await expect(
        engineer.page.getByText(SNAPSHOT_CHANGED, { exact: true }),
      ).toBeVisible();
      await expect(pageButton(engineer.page, "Approve")).toHaveCount(0);
      expect(sent).toHaveLength(1);

      await engineer.page.reload();
      await expect(pageButton(engineer.page, "Reject")).toBeVisible();
      await pageButton(engineer.page, "Reject").click();
      await expect(dialog).toBeVisible();
      await expect(dialog.locator("p")).toHaveText(
        `Reject ${ACTION} on Subscriber DB server?`,
      );
      await expect(
        dialog.getByRole("button", { name: "Reject" }),
      ).toBeVisible();
      await dialog.getByRole("button", { name: "Cancel" }).click();
      await expect(dialog).toBeHidden();

      await pageButton(engineer.page, "Approve").click();
      await expect(dialog).toBeVisible();
      await expect(dialog.locator("p")).toHaveText(
        `Approve ${ACTION} on Subscriber DB server?`,
      );
      expect(
        await dialog.evaluate(
          (element) => getComputedStyle(element, "::backdrop").backgroundColor,
        ),
      ).toBe("rgba(11, 21, 38, 0.5)");
      expect(
        await engineer.page.evaluate(() => document.activeElement?.textContent),
      ).toBe("Cancel");
      await engineer.page.screenshot({
        path: `${SCREENS}/approve-dialog-360.png`,
        fullPage: false,
      });
      await dialog.getByRole("button", { name: "Cancel" }).click();
      await expect(dialog).toBeHidden();
      expect(sent).toHaveLength(1);

      await pageButton(engineer.page, "Approve").click();
      await dialog.getByRole("button", { name: "Approve" }).click();
      await expect(
        engineer.page.getByText(/^Approved by Usman Tariq \(DB Ops\) at /),
      ).toBeVisible();
      expect(sent).toHaveLength(2);
    } else if (
      (await readAs<ApprovalAnswer>(engineer.page, api)).state === "pending"
    ) {
      await expect(pageButton(engineer.page, "Approve")).toBeVisible();
      await expect(pageButton(engineer.page, "Reject")).toBeVisible();
    }
    await expect(async () => {
      expect(
        (await readAs<ApprovalAnswer>(engineer.page, api)).decided,
      ).not.toBeNull();
    }).toPass({ timeout: 10 * MINUTE });
    const read = await readAs<ApprovalAnswer>(engineer.page, api);
    expect(read.state).toBe("approved");
    const at = read.decided!.at;
    const time = await viewerTime(engineer.page, at);
    if (MODE === "immediate") {
      await expect(
        engineer.page.getByText(
          `Approved by Usman Tariq (DB Ops) at ${time}. Techfinity will check the conditions again before it runs the action.`,
          { exact: true },
        ),
      ).toBeVisible();
      await screenshot(engineer.page, "approve-done-360");
    } else {
      await expect(
        engineer.page.getByText(
          `Already approved by Usman Tariq (DB Ops) at ${time}.`,
          {
            exact: true,
          },
        ),
      ).toBeVisible({ timeout: 10 * MINUTE });
    }
    await expect(
      viewer.page.getByText(
        `Already approved by Usman Tariq (DB Ops) at ${time}.`,
        {
          exact: true,
        },
      ),
    ).toBeVisible();
    const seen = await watched(viewer.page);
    expect(seen.sameDocument).toBe(true);
    expect(seen.shown).not.toBeNull();
    expect(seen.shown! - Date.parse(at)).toBeLessThanOrEqual(2000);
    await done(engineer.context);
    await done(viewer.context);
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
  "DC-S04-05 the refusals of the Acknowledge page",
  { tag: "@storm" },
  async ({ browser }) => {
    const report = await incidentId(REPORT_TITLE);
    const noc = await signedInThrough(
      browser,
      "noc-l1",
      `/m/acknowledge/${stormId}`,
    );
    contexts.push(noc.context);
    await expect(
      noc.page.getByText(
        /^This incident is (resolved|closed)\. It does not need acknowledgement\.$/,
      ),
    ).toBeVisible({ timeout: MINUTE });
    await expect(pageButton(noc.page, "Acknowledge")).toHaveCount(0);
    await noc.page.goto(`/m/acknowledge/${report}`);
    await expect(
      noc.page.getByText(
        "You are not in a team that was notified about this incident.",
        { exact: true },
      ),
    ).toBeVisible();
    await expect(pageButton(noc.page, "Acknowledge")).toHaveCount(0);
    await screenshot(noc.page, "acknowledge-refused-360");
    const viewer = await sharing(page, `/m/acknowledge/${report}`);
    contexts.push(viewer.context);
    await expect(
      viewer.page.getByText("View only.", { exact: true }),
    ).toBeVisible();
    await expect(viewer.page.locator("main > div > button")).toHaveCount(0);
    expect(
      await viewer.page
        .locator("main > div")
        .nth(1)
        .locator("p")
        .allTextContents(),
    ).toEqual(["View only."]);
    await done(noc.context);
    await done(viewer.context);
  },
);

test("DC-S05-06 a decided request", { tag: "@storm" }, async ({ browser }) => {
  const path = `/m/approve/${requestId}`;
  const read = await readAs<ApprovalAnswer>(
    page,
    `/api/v1/approval-requests/${requestId}`,
  );
  const time = await viewerTime(page, read.decided!.at);
  const text = `Already approved by Usman Tariq (DB Ops) at ${time}.`;
  const lead = await signedInThrough(browser, "dbops-lead", path);
  contexts.push(lead.context);
  await expect(lead.page.getByText(text, { exact: true })).toBeVisible();
  await expect(pageButton(lead.page, "Approve")).toHaveCount(0);
  await expect(lead.page.getByText("View only.", { exact: true })).toHaveCount(
    0,
  );
  const viewer = await sharing(page, path);
  contexts.push(viewer.context);
  await expect(viewer.page.getByText(text, { exact: true })).toBeVisible();
  await expect(
    viewer.page.getByText("View only.", { exact: true }),
  ).toBeVisible();
  await done(lead.context);
  await done(viewer.context);
});

test(
  "PM-M24-03 the Acknowledge page is usable at 360 px",
  { tag: "@storm" },
  async () => {
    const viewer = await sharing(page, `/m/acknowledge/${stormId}`);
    contexts.push(viewer.context);
    await expect(
      viewer.page.getByRole("heading", { name: "Acknowledge incident" }),
    ).toBeVisible();
    expect(await scrollWidth(viewer.page)).toBeLessThanOrEqual(360);
    expect(
      await viewer.page
        .locator("main")
        .evaluate((main) => getComputedStyle(main).fontSize),
    ).toBe("16px");
    await done(viewer.context);
  },
);

test("VD-S-07 the Approval page at 360 px", { tag: "@storm" }, async () => {
  const viewer = await sharing(page, `/m/approve/${requestId}`);
  contexts.push(viewer.context);
  await expect(
    viewer.page.getByRole("heading", { name: "Approve action" }),
  ).toBeVisible();
  expect(await scrollWidth(viewer.page)).toBeLessThanOrEqual(360);
  const parameters = viewer.page.locator("main p", {
    hasText: /^(path|older_than_minutes|max_bytes) = /,
  });
  await expect(parameters).toHaveCount(3);
  for (const font of await parameters.evaluateAll((elements) =>
    elements.map((element) => getComputedStyle(element).fontFamily),
  )) {
    expect(font).toContain("JetBrains Mono");
  }
  const limits = viewer.page.locator("main ul li:not(:has(p))");
  await expect(limits).toHaveText(LIMITS);
  expect(
    await limits.evaluateAll((elements) =>
      elements.map((element) => getComputedStyle(element).display),
    ),
  ).toEqual(["list-item", "list-item", "list-item", "list-item"]);
  expect(
    await limits
      .first()
      .evaluate(
        (element) => getComputedStyle(element.parentElement!).listStyleType,
      ),
  ).toBe("disc");
  await done(viewer.context);
});

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
