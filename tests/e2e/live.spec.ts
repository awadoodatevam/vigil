// The suite live (P-32 spec Sections 6.8 and 6.10; AR-SP-01 as amended by BP-A-31, CTX-D-095 (6)): the live updates
// of vigil-app through the load balancer, measured in a browser on the owner's Mac during a storm run. Run only against
// the demo installation by tests/acceptance/demo-host/storm.sh {installation} --live, which starts the driver after
// the marker live-ready; never locally (deploy/local.sh e2e leaves out @live).
//
// The driver's reset gives Vigil a new tenant and so ends the session made before it (AR-B-02): as the suite storm
// does, the test then signs in again and measures the streams it opens with the new session.
import { writeFileSync } from "node:fs";
import { expect, test, type Page } from "@playwright/test";
import { signIn } from "./helpers";

const READY = "/e2e/run/live-ready";
const RESULT = "/e2e/run/AR-SP-01.json";
const BASE = process.env.VIGIL_BASE_URL ?? "http://demo.vigil.test:8080";
const MINUTE = 60_000;
const LIMIT = 20 * MINUTE;
const NOTICES = 200;
/** A stream between an error and its next open gets up to 5 s more at the end (as P-05's page). */
const GRACE = 5_000;
const BRANDING = "/api/v1/public/branding";

interface Stream {
  first_open: number | null;
  last_error: number | null;
  errors: number[];
  reconnects: { at: number; gap: number }[];
  closed: boolean;
  open: boolean;
}

interface Live {
  started: number;
  skews: number[];
  notices: Stream;
  idle: Stream;
}

test.describe.configure({ timeout: 25 * MINUTE });

/** The smallest round trip of 20 requests of the public branding, in ms. */
function smallestRoundTrip(page: Page): Promise<number> {
  return page.evaluate(async (path) => {
    let smallest = Number.POSITIVE_INFINITY;
    for (let index = 0; index < 20; index++) {
      const sent = performance.now();
      const answer = await fetch(path, { cache: "no-store" });
      await answer.text();
      smallest = Math.min(smallest, performance.now() - sent);
    }
    return smallest;
  }, BRANDING);
}

/** Opens the two streams in the page, closing earlier ones, and records their events in window.__live. */
async function openStreams(page: Page): Promise<void> {
  await page.goto(BRANDING);
  await page.evaluate(() => {
    const w = window as unknown as {
      __live?: Live;
      __sources?: EventSource[];
    };
    for (const source of w.__sources ?? []) {
      source.close();
    }
    const stream = (): Stream => ({
      first_open: null,
      last_error: null,
      errors: [],
      reconnects: [],
      closed: false,
      open: false,
    });
    const live: Live = {
      started: Date.now(),
      skews: [],
      notices: stream(),
      idle: stream(),
    };
    w.__live = live;
    const watch = (source: EventSource, state: Stream) => {
      source.onopen = () => {
        const now = Date.now();
        if (state.first_open === null) {
          state.first_open = now;
        } else if (state.last_error !== null) {
          state.reconnects.push({
            at: now - live.started,
            gap: now - state.last_error,
          });
        }
        state.last_error = null;
        state.open = true;
      };
      source.onerror = () => {
        const now = Date.now();
        state.errors.push(now - live.started);
        state.last_error ??= now;
        state.open = false;
        state.closed = source.readyState === EventSource.CLOSED;
      };
    };
    const notices = new EventSource("/api/v1/live?topics=alerts,incidents");
    const idle = new EventSource("/api/v1/live?topics=tenant");
    notices.addEventListener("change", (event) => {
      const at = (JSON.parse((event as MessageEvent).data) as { at: string })
        .at;
      live.skews.push(Date.now() - Date.parse(at));
    });
    watch(notices, live.notices);
    watch(idle, live.idle);
    w.__sources = [notices, idle];
  });
  await expect
    .poll(
      () =>
        page.evaluate(() => {
          const live = (window as unknown as { __live: Live }).__live;
          return live.notices.open && live.idle.open;
        }),
      { timeout: 30_000 },
    )
    .toBe(true);
}

function snapshot(page: Page): Promise<Live> {
  return page.evaluate(() =>
    JSON.parse(JSON.stringify((window as unknown as { __live: Live }).__live)),
  );
}

/** The status of AR-I-13 with the page's session. */
function sessionStatus(page: Page): Promise<number> {
  return page.evaluate(
    async () => (await fetch("/api/v1/session", { cache: "no-store" })).status,
  );
}

test(
  "AR-SP-01 live updates of vigil-app through the load balancer",
  { tag: "@live" },
  async ({ page }) => {
    const startedAt = new Date();
    await signIn(page, "presenter");
    const rtt = await smallestRoundTrip(page);
    await openStreams(page);
    writeFileSync(READY, `${new Date().toISOString()}\n`);
    let sessions = 1;
    let live = await snapshot(page);
    const end = startedAt.getTime() + LIMIT;
    while (Date.now() < end) {
      live = await snapshot(page);
      if (live.skews.length >= NOTICES && live.idle.reconnects.length >= 1) {
        break;
      }
      if ((await sessionStatus(page)) === 401) {
        // The driver's reset ended the session: sign in again and measure the new streams.
        sessions += 1;
        await signIn(page, "presenter");
        await openStreams(page);
        continue;
      }
      if (live.notices.closed || live.idle.closed) {
        break;
      }
      await page.waitForTimeout(1_000);
    }
    const graceEnd = Date.now() + GRACE;
    while (
      Date.now() < graceEnd &&
      [live.notices, live.idle].some((s) => s.last_error !== null && !s.closed)
    ) {
      await page.waitForTimeout(250);
      live = await snapshot(page);
    }
    live = await snapshot(page);

    const url = new URL(page.url());
    const smallest = Math.min(...live.skews);
    const delays = live.skews.map((skew) => skew - smallest + rtt / 2);
    const maxDelay = delays.length ? Math.max(...delays) : null;
    const firstOpen = live.idle.first_open;
    const firstError =
      firstOpen === null || live.idle.errors.length === 0
        ? null
        : (live.started + live.idle.errors[0] - firstOpen) / 1000;
    const reconnects: {
      stream: string;
      at_s: number;
      gap_ms: number | null;
    }[] = [];
    for (const [name, stream] of [
      ["notices", live.notices],
      ["idle", live.idle],
    ] as const) {
      for (const reconnect of stream.reconnects) {
        reconnects.push({
          stream: name,
          at_s: reconnect.at / 1000,
          gap_ms: reconnect.gap,
        });
      }
      if (stream.last_error !== null) {
        reconnects.push({
          stream: name,
          at_s: (stream.last_error - live.started) / 1000,
          gap_ms: null,
        });
      }
    }
    const criteria = {
      https_origin:
        url.protocol === "https:" && url.origin === new URL(BASE).origin,
      notices_200: live.skews.length >= NOTICES,
      max_delay_1000: maxDelay !== null && maxDelay <= 1000,
      idle_open_295: firstOpen !== null && (firstError ?? 295) >= 295,
      reconnect_5000: reconnects.every(
        (r) => r.gap_ms !== null && r.gap_ms <= 5000,
      ),
    };
    const passed = Object.values(criteria).every(Boolean);
    const result = {
      started_at: startedAt.toISOString(),
      finished_at: new Date().toISOString(),
      page_url: page.url(),
      user_agent: await page.evaluate(() => navigator.userAgent),
      sessions,
      rtt_min_ms: rtt,
      notices: {
        count: live.skews.length,
        max_delay_ms: maxDelay,
        delays_ms: delays,
        skews_ms: live.skews,
      },
      idle: { first_error_after_s: firstError },
      reconnects,
      criteria,
      passed,
    };
    writeFileSync(RESULT, `${JSON.stringify(result, null, 2)}\n`);
    console.log(`AR-SP-01 ${JSON.stringify({ criteria, passed })}`);
    expect(criteria).toEqual({
      https_origin: true,
      notices_200: true,
      max_delay_1000: true,
      idle_open_295: true,
      reconnect_5000: true,
    });
  },
);
