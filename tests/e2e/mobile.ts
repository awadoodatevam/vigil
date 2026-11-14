// Helpers of the mobile pages' Playwright tests (P-30 spec Sections 6.10 and 7): contexts at 360 × 740 px, the sign-in
// through a page's address (DC-U-13), the page's text blocks, viewer times as the page formats them, and a watch of
// the moment a text first shows on a page.
import {
  expect,
  type Browser,
  type BrowserContext,
  type Page,
} from "@playwright/test";
import { fillSignIn } from "./helpers";

export const MOBILE = {
  viewport: { width: 360, height: 740 },
  isMobile: false,
  hasTouch: false,
} as const;

/** An address as a regular expression that matches the whole path. */
export function addressOf(path: string): RegExp {
  return new RegExp(`${path.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`);
}

/** A new 360 × 740 px context that opens the address, is sent to the login and signs in as the user. */
export async function signedInThrough(
  browser: Browser,
  username: string,
  path: string,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext(MOBILE);
  const page = await context.newPage();
  await page.goto(path);
  await expect(page).toHaveURL(
    new RegExp(`/login\\?next=${encodeURIComponent(path)}$`),
  );
  await fillSignIn(page, username);
  await expect(page).toHaveURL(addressOf(path));
  return { context, page };
}

/** A 360 × 740 px context with the session of `page` (the list page's presenter) that opens the address. */
export async function sharing(
  page: Page,
  path: string,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await page
    .context()
    .browser()!
    .newContext({
      ...MOBILE,
      storageState: await page.context().storageState(),
    });
  const shown = await context.newPage();
  await shown.goto(path);
  await expect(shown).toHaveURL(addressOf(path));
  return { context, page: shown };
}

/** The text blocks of the page's main element in document order: headings, paragraphs and plain list items. */
export function blocks(page: Page): Promise<string[]> {
  return page.evaluate(() =>
    [...document.querySelectorAll("main :is(h1, h2, p, li)")]
      .filter(
        (element) => !(element.tagName === "LI" && element.querySelector("p")),
      )
      .filter((element) => !element.closest("dialog"))
      .map((element) => element.textContent ?? ""),
  );
}

const MONTHS = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
];

/** A viewer time (DC-U-07 rule (1)) in the page's time zone, at the page's clock. */
export async function viewerTime(page: Page, iso: string): Promise<string> {
  const [zone, now] = await page.evaluate(() => [
    Intl.DateTimeFormat().resolvedOptions().timeZone,
    Date.now(),
  ]);
  const parts = (instant: number) => {
    const values: Record<string, number> = {};
    for (const part of new Intl.DateTimeFormat("en-GB", {
      timeZone: zone as string,
      year: "numeric",
      month: "numeric",
      day: "numeric",
      hour: "numeric",
      minute: "numeric",
      second: "numeric",
      hourCycle: "h23",
    }).formatToParts(instant)) {
      if (part.type !== "literal") {
        values[part.type] = Number(part.value);
      }
    }
    return values;
  };
  const two = (n: number) => String(n).padStart(2, "0");
  const at = parts(Date.parse(iso));
  const today = parts(now as number);
  if (
    at.year === today.year &&
    at.month === today.month &&
    at.day === today.day
  ) {
    return `${two(at.hour)}:${two(at.minute)}:${two(at.second)}`;
  }
  return `${at.day} ${MONTHS[at.month - 1]} ${at.year}, ${two(at.hour)}:${two(at.minute)}`;
}

/** Starts recording, in the page, the first moment a paragraph starting with `prefix` shows; marks the document. */
export async function watch(page: Page, prefix: string): Promise<void> {
  await page.evaluate((text) => {
    const target = window as unknown as {
      __vigilShown?: number;
      __vigilMark?: string;
    };
    target.__vigilMark = "P-30";
    const timer = setInterval(() => {
      const found = [...document.querySelectorAll("main p")].some((p) =>
        (p.textContent ?? "").startsWith(text),
      );
      if (found) {
        target.__vigilShown = Date.now();
        clearInterval(timer);
      }
    }, 50);
  }, prefix);
}

/** The recorded moment, and whether the document is the one `watch` marked (no reload). */
export function watched(
  page: Page,
): Promise<{ shown: number | null; sameDocument: boolean }> {
  return page.evaluate(() => {
    const target = window as unknown as {
      __vigilShown?: number;
      __vigilMark?: string;
    };
    return {
      shown: target.__vigilShown ?? null,
      sameDocument: target.__vigilMark === "P-30",
    };
  });
}

/** Whether the page scrolls sideways (VD-R-10). */
export function scrollWidth(page: Page): Promise<number> {
  return page.evaluate(() => document.documentElement.scrollWidth);
}
