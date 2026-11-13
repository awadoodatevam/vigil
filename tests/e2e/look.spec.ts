// The look of the P-28 screens (P-44 spec Sections 6.9 and 7, docs/45-visual-design.md): the banner's own colour,
// the bundled fonts, the widths, the focus ring, reduced motion, and the screenshots of the screens for the review.
import { expect, test, type Page } from "@playwright/test";
import { banner, fillSignIn, password, screenshot, signIn } from "./helpers";

const BANNER = "rgb(255, 210, 63)";
const NAVY = "rgb(11, 21, 38)";
const FOCUS_RING =
  "rgb(255, 255, 255) 0px 0px 0px 2px, rgb(47, 111, 237) 0px 0px 0px 4px";

test.use({ viewport: { width: 1280, height: 720 } });

/** The login card: the parent element of the heading "Sign in". */
function card(page: Page) {
  return page.getByRole("heading", { name: "Sign in" }).locator("..");
}

async function openLogin(page: Page): Promise<void> {
  await page.goto("/login");
  await expect(page.getByLabel("Username")).toBeVisible();
}

async function openIncidents(page: Page): Promise<void> {
  await signIn(page, "presenter");
  await expect(page.getByRole("heading", { name: "Incidents" })).toBeVisible();
  await expect(page.getByTestId("alerts-strip")).toBeVisible();
}

/** The elements other than the banner that use the banner's colour as background, text or border colour. */
function bannerColourElsewhere(page: Page): Promise<string[]> {
  return page.evaluate((colour) => {
    const found: string[] = [];
    const own = document.querySelector('[data-testid="environment-banner"]');
    for (const element of document.querySelectorAll("*")) {
      if (element === own) {
        continue;
      }
      const style = getComputedStyle(element);
      const values = [
        style.backgroundColor,
        style.color,
        style.borderTopColor,
        style.borderRightColor,
        style.borderBottomColor,
        style.borderLeftColor,
      ];
      if (values.includes(colour)) {
        found.push(`${element.tagName} ${element.className}`);
      }
    }
    return found;
  }, BANNER);
}

async function scrollWidth(page: Page): Promise<number> {
  return page.evaluate(() => document.documentElement.scrollWidth);
}

test("DC-U-01 no element other than the banner uses its colour", async ({
  page,
}) => {
  await openLogin(page);
  await expect(banner(page)).toHaveCSS("background-color", BANNER);
  expect(await bannerColourElsewhere(page)).toEqual([]);
  await openIncidents(page);
  await expect(banner(page)).toHaveCSS("background-color", BANNER);
  expect(await bannerColourElsewhere(page)).toEqual([]);
});

test("VD-C-01 the banner is sticky, at least 30 px high, yellow with dark text", async ({
  page,
}) => {
  await openIncidents(page);
  const style = await banner(page).evaluate((element) => {
    const computed = getComputedStyle(element);
    return {
      position: computed.position,
      top: computed.top,
      height: element.getBoundingClientRect().height,
      background: computed.backgroundColor,
      color: computed.color,
    };
  });
  expect(style.position).toBe("sticky");
  expect(style.top).toBe("0px");
  expect(style.height).toBeGreaterThanOrEqual(30);
  expect(style.background).toBe(BANNER);
  expect(style.color).toBe("rgb(26, 20, 0)");
});

test("VD-R-09 the fonts are bundled and nothing loads from another host", async ({
  page,
}) => {
  const requests: string[] = [];
  page.on("request", (request) => requests.push(request.url()));
  await openIncidents(page);
  const loaded = await page.evaluate(async () => ({
    inter: (await document.fonts.load('15px "Inter Variable"')).length,
    mono: (await document.fonts.load('13px "JetBrains Mono Variable"')).length,
    body: getComputedStyle(document.body).fontFamily,
    origin: window.location.origin,
  }));
  expect(loaded.inter).toBeGreaterThanOrEqual(1);
  expect(loaded.mono).toBeGreaterThanOrEqual(1);
  expect(loaded.body.startsWith('"Inter Variable"')).toBe(true);
  for (const url of requests) {
    expect(new URL(url).origin, url).toBe(loaded.origin);
  }
  const fonts = requests.filter((url) => /\.woff2?(\?|$)/.test(url));
  for (const url of fonts) {
    expect(url).toMatch(
      /(inter|jetbrains-mono)-latin-wght-normal[^/]*\.woff2$/,
    );
  }
  const families = new Set(
    fonts.map((url) => /(inter|jetbrains-mono)-latin/.exec(url)?.[1]),
  );
  expect(families).toEqual(new Set(["inter", "jetbrains-mono"]));
  console.log(`VD-R-09 font files requested: ${fonts.join(", ")}`);
});

test("VD-R-10 no horizontal scrolling at 1280 × 720", async ({ page }) => {
  await openLogin(page);
  expect(await scrollWidth(page)).toBeLessThanOrEqual(1280);
  await openIncidents(page);
  expect(await scrollWidth(page)).toBeLessThanOrEqual(1280);
});

test("VD-L-04 the login at 360 px in one column", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 740 });
  await openLogin(page);
  expect(await scrollWidth(page)).toBeLessThanOrEqual(360);
  const box = (await card(page).boundingBox())!;
  expect(box.x).toBeGreaterThanOrEqual(14);
  expect(box.x + box.width).toBeLessThanOrEqual(360 - 14);
});

test("VD-R-12 the focus ring on Tab", async ({ page }) => {
  await openLogin(page);
  const button = page.getByRole("button", { name: "Sign in" });
  for (let i = 0; i < 10; i++) {
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

test("VD-R-11 no animation under reduced motion", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await openIncidents(page);
  const animated = await page.evaluate(() =>
    [...document.querySelectorAll("*")]
      .filter((element) => getComputedStyle(element).animationName !== "none")
      .map((element) => `${element.tagName} ${element.className}`),
  );
  expect(animated).toEqual([]);
});

test("VD-S-01 the login card is centred on the navy page", async ({ page }) => {
  await openLogin(page);
  const placed = await card(page).evaluate((element) => {
    const box = element.getBoundingClientRect();
    const parent = getComputedStyle(element.parentElement!);
    return {
      centre: box.x + box.width / 2,
      viewport: document.documentElement.clientWidth / 2,
      background: `${parent.backgroundColor} ${parent.backgroundImage}`,
    };
  });
  expect(Math.abs(placed.centre - placed.viewport)).toBeLessThanOrEqual(1);
  expect(placed.background).toContain(NAVY);
});

test("VD-S-01 the login screens", async ({ page }) => {
  await openLogin(page);
  await screenshot(page, "login-1280");
  await page.setViewportSize({ width: 360, height: 740 });
  await screenshot(page, "login-360");
  await page.setViewportSize({ width: 1280, height: 720 });
  await fillSignIn(page, "presenter", `${password("presenter")}-wrong`);
  await expect(
    page.getByText("Username or password is incorrect."),
  ).toBeVisible();
  await screenshot(page, "login-refused");
  await fillSignIn(page, "presenter");
  await expect(page.getByRole("heading", { name: "Incidents" })).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByText("You are signed out.")).toBeVisible();
  await expect(page.getByLabel("Username")).toBeVisible();
  await screenshot(page, "login-signed-out");
});

test("VD-S-02 the no-access page", async ({ page }) => {
  await signIn(page, "noc-l1");
  await expect(page.getByRole("heading", { name: "No access" })).toBeVisible();
  await screenshot(page, "no-access-1280");
});

test("VD-S-03 the error states", async ({ page }) => {
  await signIn(page, "presenter");
  await page.goto("/incidents/00000000-0000-4000-8000-000000000000");
  await expect(
    page.getByText("Could not load this page. Not found."),
  ).toBeVisible();
  await screenshot(page, "not-found-1280");
  await page.goto("http://vigil-app:8080/login");
  await expect(
    page.getByText("Could not load this page. This address is not set up."),
  ).toBeVisible();
  await screenshot(page, "unknown-host");
});

test("VD-S-04 the empty incident list", async ({ page }) => {
  await openIncidents(page);
  await expect(
    page.getByText(
      "No incidents yet. Techfinity is watching 2 monitoring sources.",
    ),
  ).toBeVisible();
  await screenshot(page, "incidents-empty-1280");
});
