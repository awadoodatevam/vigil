// The suite control (P-31 spec Section 6.8, CTX-D-093 (11)): the demo control panel at control.vigil.test:8090 on the
// local installation — its login, buttons and guards, SB-T-05's "Clear all faults" after a storm and five resets in a
// row (the first after SL-F-1 with SL-F-2), and the new tenant of a reset. Run by deploy/local.sh e2e control.
import { mkdirSync, readFileSync } from "node:fs";
import {
  expect,
  request as playwrightRequest,
  test,
  type APIRequestContext,
  type Page,
} from "@playwright/test";
import { SCREENS, password as seedPassword, screenshot } from "./helpers";

const CONTROL = "http://control.vigil.test:8090";
const VIGIL = "http://demo.vigil.test:8080";
const MINUTE = 60_000;
/** DD-F-10 storm_arrival: every storm alert within 120 s of the storm button. */
const STORM_ARRIVAL = 120_000;
const TAG = { tag: "@control" };
const LABELS = [
  "Storm: SL-F-1 + SL-F-3",
  "Storm re-run: SL-F-1",
  "Storm with read-only DB: SL-F-1 + SL-F-2",
  "Cut cloud AI: SL-F-4",
  "Clear all faults",
  "Reset",
];
const DESCRIPTIONS = [
  "Fills /data on the Subscriber DB server and fails the report export job.",
  "Fills /data on the Subscriber DB server again.",
  "Fills /data and sets the database read-only.",
  "Blocks Techfinity's calls to the Claude and OpenAI APIs.",
  "Ends every active fault and waits until every lab alert is resolved.",
  "Clears the lab and brings Techfinity to the starting state.",
];
const TIME = "\\d{2}:\\d{2}:\\d{2}";

test.describe.configure({ mode: "serial", timeout: 15 * MINUTE });

let page: Page;
/** A presenter session of Vigil made before the last reset (AR-B-02). */
let vigil: APIRequestContext;
let presenterCookie = "";

function controlPassword(): string {
  return readFileSync("/run/secrets/vigil/control-password", "utf8");
}

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage({
    baseURL: CONTROL,
    viewport: { width: 1280, height: 800 },
  });
});

test.afterAll(async () => {
  await page.close();
  await vigil?.dispose();
});

function item(label: string) {
  return page.locator("#buttons li").filter({
    has: page.getByRole("button", { name: label, exact: true }),
  });
}

function button(label: string) {
  return page
    .locator("#buttons")
    .getByRole("button", { name: label, exact: true });
}

function reason(label: string) {
  return item(label).locator(".reason");
}

async function noHorizontalScroll(): Promise<void> {
  await page.setViewportSize({ width: 640, height: 800 });
  const widths = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(widths.scroll).toBeLessThanOrEqual(widths.client);
  await page.setViewportSize({ width: 1280, height: 800 });
}

/** The last result of /api/state, printed for the report (the measured values of SB-T-05). */
async function measured(what: string): Promise<Record<string, unknown>> {
  const answer = await page.request.get("/api/state");
  expect(answer.status()).toBe(200);
  const last = (await answer.json()).last_result as Record<string, unknown>;
  console.log(`${what}: ${JSON.stringify(last)}`);
  return last;
}

async function signIn(): Promise<void> {
  await page.getByLabel("Username").fill("operator");
  await page.getByLabel("Password").fill(controlPassword());
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator("#panel")).toBeVisible();
}

/** Waits until the status line matches, and returns its text. */
async function statusMatching(
  pattern: RegExp,
  timeout: number,
): Promise<string> {
  await expect(page.locator("#status")).toHaveText(pattern, { timeout });
  return (await page.locator("#status").textContent()) ?? "";
}

/** Presses "Reset", confirms it and checks it completes within 60 s (AR-TM-10). */
async function reset(dialogScreenshot = false): Promise<void> {
  await button("Reset").click();
  const dialog = page.locator("dialog#confirm");
  await expect(dialog).toBeVisible();
  if (dialogScreenshot) {
    mkdirSync(SCREENS, { recursive: true });
    await page.screenshot({ path: `${SCREENS}/control-reset-dialog.png` });
  }
  await dialog.getByRole("button", { name: "Reset" }).click();
  await expect(dialog).toBeHidden();
  for (const label of LABELS) {
    await expect(reason(label)).toHaveText("Wait: Reset is running.");
    await expect(button(label)).toBeDisabled();
  }
  await statusMatching(
    /^Resetting… \d+ s — lab: (Clearing|Clear), Techfinity: (Resetting|Ready)$/,
    10_000,
  );
  const done = await statusMatching(
    /^(Reset completed in \d+ s|Reset failed after .*)$/,
    90_000,
  );
  const last = await measured("reset");
  expect(done).toMatch(/^Reset completed in \d+ s$/);
  expect(Number(done.match(/(\d+) s$/)![1])).toBeLessThanOrEqual(60);
  expect(Number(last.seconds)).toBeLessThanOrEqual(60);
  await expect(page.locator("#faults")).toContainText("No active faults.");
  await expect(page.locator("#clock")).toBeHidden();
}

test("DC-S12-01 the login and its refusal", TAG, async () => {
  await page.goto("/");
  await expect(page.locator("#top")).toHaveText(
    "Demo control panel — demo-only, not part of Techfinity.",
  );
  await expect(page.getByLabel("Username")).toBeVisible();
  await expect(page.getByLabel("Password")).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  await page.getByLabel("Username").fill("operator");
  await page.getByLabel("Password").fill(controlPassword() + "-wrong");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(
    page.getByText("Username or password is incorrect."),
  ).toBeVisible();
  await noHorizontalScroll();
  await screenshot(page, "control-login");
  await signIn();
  await expect(page.locator("#top")).toHaveText(
    "Demo control panel — demo-only, not part of Techfinity.",
  );
});

test("DC-S12-02 the buttons, descriptions and link", TAG, async () => {
  await expect(page.locator("#status")).toBeVisible({ timeout: 30_000 });
  const items = page.locator("#buttons li");
  await expect(items).toHaveCount(6);
  for (let index = 0; index < LABELS.length; index++) {
    await expect(items.nth(index).getByRole("button")).toHaveText(
      LABELS[index],
    );
    await expect(items.nth(index).locator(".description")).toHaveText(
      DESCRIPTIONS[index],
    );
  }
  const open = page.getByRole("link", { name: "Open Techfinity" });
  await expect(open).toHaveAttribute("href", `${VIGIL}/login`);
  await expect(open).toHaveAttribute("target", "_blank");
  await expect(page.locator("#faults p")).toHaveText([
    "Active faults:",
    "No active faults.",
  ]);
  await expect(button("Clear all faults")).toBeDisabled();
  await expect(reason("Clear all faults")).toHaveText("No active faults.");
  await expect(page.locator("#status")).toHaveText(
    /^(Idle|Reset completed in \d+ s|Clear all faults completed in \d+ s)$/,
  );
  await noHorizontalScroll();
  await screenshot(page, "control-idle");
});

test("SB-T-05 Clear all faults after a storm", TAG, async () => {
  test.setTimeout(10 * MINUTE);
  await button("Storm: SL-F-1 + SL-F-3").click();
  const lines = page.locator("#faults p");
  await expect(lines.nth(1)).toHaveText(
    new RegExp(`^SL-F-1 Subscriber DB /data full since ${TIME}$`),
  );
  await expect(lines.nth(2)).toHaveText(
    new RegExp(`^SL-F-3 Unrelated report export failure since ${TIME}$`),
  );
  for (const label of LABELS.slice(0, 3)) {
    await expect(reason(label)).toHaveText(
      "Clear all faults first (active: SL-F-1, SL-F-3).",
    );
    await expect(button(label)).toBeDisabled();
  }
  const clock = page.locator("#clock");
  await expect(clock).toHaveText(
    /^T\+\d+:\d{2} since 'Storm: SL-F-1 \+ SL-F-3'$/,
  );
  const first = await clock.textContent();
  await expect(clock).not.toHaveText(first!, { timeout: 2_000 });
  await page.waitForTimeout(STORM_ARRIVAL);
  await screenshot(page, "control-storm");

  await button("Clear all faults").click();
  for (const label of LABELS) {
    await expect(reason(label)).toHaveText(
      "Wait: Clear all faults is running.",
    );
    await expect(button(label)).toBeDisabled();
  }
  const clearing =
    /^Clearing faults… \d+ s — firing lab alerts: Zabbix (\d+), Alertmanager (\d+)$/;
  let firing = false;
  let done = "";
  const end = Date.now() + 90_000;
  while (Date.now() < end) {
    const text = (await page.locator("#status").textContent()) ?? "";
    const match = text.match(clearing);
    if (match) {
      firing ||= Number(match[1]) + Number(match[2]) > 0;
    } else if (/^Clear all faults (completed in|failed after) /.test(text)) {
      done = text;
      break;
    }
    await page.waitForTimeout(200);
  }
  const last = await measured("clear");
  expect(firing).toBe(true);
  expect(done).toMatch(/^Clear all faults completed in \d+ s$/);
  expect(Number(done.match(/(\d+) s$/)![1])).toBeLessThanOrEqual(52);
  expect(Number(last.quiet_seconds)).toBeLessThanOrEqual(40);
  await expect(page.locator("#faults")).toContainText("No active faults.");
});

test(
  "SB-T-05 five resets in a row, the first after SL-F-1 with SL-F-2",
  TAG,
  async () => {
    test.setTimeout(15 * MINUTE);
    await button("Storm with read-only DB: SL-F-1 + SL-F-2").click();
    await expect(page.locator("#faults p").nth(2)).toHaveText(
      new RegExp(`^SL-F-2 Subscriber DB stays read-only since ${TIME}$`),
    );
    await button("Cut cloud AI: SL-F-4").click();
    await expect(page.locator("#faults p").nth(3)).toHaveText(
      new RegExp(`^SL-F-4 Cloud AI unreachable since ${TIME}$`),
    );
    await expect(reason("Cut cloud AI: SL-F-4")).toHaveText("Already active.");
    await expect(button("Cut cloud AI: SL-F-4")).toBeDisabled();
    for (const label of LABELS.slice(0, 3)) {
      await expect(reason(label)).toHaveText(
        "Clear all faults first (active: SL-F-1, SL-F-2, SL-F-4).",
      );
    }
    await page.waitForTimeout(STORM_ARRIVAL);

    const sent: string[] = [];
    page.on("request", (request) => {
      if (request.url().includes("/api/buttons/")) {
        sent.push(request.url());
      }
    });
    await button("Reset").click();
    const dialog = page.locator("dialog#confirm");
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText(
      "Reset now? This ends the current demo run.",
    );
    const cancel = dialog.getByRole("button", { name: "Cancel" });
    await expect(dialog.getByRole("button").first()).toHaveText("Cancel");
    await expect(cancel).toBeFocused();
    await cancel.click();
    await expect(dialog).toBeHidden();
    await page.waitForTimeout(1_500);
    expect(sent).toEqual([]);

    await reset(true);
    for (let run = 2; run <= 5; run++) {
      if (run === 5) {
        vigil = await playwrightRequest.newContext({ baseURL: VIGIL });
        const login = await vigil.post("/api/v1/session", {
          data: { username: "presenter", password: seedPassword("presenter") },
        });
        expect(login.status()).toBe(200);
        presenterCookie = login
          .headersArray()
          .filter((header) => header.name.toLowerCase() === "set-cookie")
          .map((header) => header.value.split(";")[0])
          .join("; ");
        const before = await vigil.get("/api/v1/session", {
          headers: { Cookie: presenterCookie },
        });
        expect(before.status()).toBe(200);
      }
      await reset();
    }
    await screenshot(page, "control-reset-done");
  },
);

test("AR-B-02 a reset gives Vigil a new tenant", TAG, async () => {
  const refused = await vigil.get("/api/v1/session", {
    headers: { Cookie: presenterCookie },
  });
  expect(refused.status()).toBe(401);
  const fresh = await playwrightRequest.newContext({ baseURL: VIGIL });
  try {
    const login = await fresh.post("/api/v1/session", {
      data: { username: "presenter", password: seedPassword("presenter") },
    });
    expect(login.status()).toBe(200);
    const incidents = await fresh.get("/api/v1/incidents");
    expect(incidents.status()).toBe(200);
    expect((await incidents.json()).items).toEqual([]);
  } finally {
    await fresh.dispose();
  }
});
