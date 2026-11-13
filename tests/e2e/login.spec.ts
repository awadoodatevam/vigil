// DC-S01 Login, the no-access page and the screens' common parts (P-28 spec Section 7).
import { expect, test } from "@playwright/test";
import { LABEL, banner, fillSignIn, password, signIn } from "./helpers";

test(
  "DC-S01-01 the login page is shown without a session",
  { tag: "@smoke" },
  async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
    await expect(page.getByLabel("Username")).toBeVisible();
    await expect(page.getByLabel("Password")).toHaveAttribute(
      "type",
      "password",
    );
    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  },
);

test(
  "DC-S01-02 the login page shows the banner, Techfinity, Jazz and the form, in one column at 375 px",
  { tag: "@smoke" },
  async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    await page.goto("/login");
    await expect(banner(page)).toHaveText(LABEL);
    await expect(page.getByText("Techfinity", { exact: true })).toBeVisible();
    await expect(page.getByText("Jazz", { exact: true })).toBeVisible();
    const boxes = [];
    for (const locator of [
      page.getByText("Techfinity", { exact: true }),
      page.getByText("Jazz", { exact: true }),
      page.getByRole("heading", { name: "Sign in" }),
      page.getByLabel("Username"),
      page.getByLabel("Password"),
      page.getByRole("button", { name: "Sign in" }),
    ]) {
      boxes.push((await locator.boundingBox())!);
    }
    for (let i = 1; i < boxes.length; i++) {
      expect(boxes[i].y).toBeGreaterThanOrEqual(
        boxes[i - 1].y + boxes[i - 1].height,
      );
      expect(Math.round(boxes[i].x)).toBe(Math.round(boxes[0].x));
    }
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(375);
  },
);

test(
  "SL-A3-06 the customer name and environment label are the configured ones",
  { tag: "@smoke" },
  async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByText("Jazz", { exact: true })).toBeVisible();
    await expect(banner(page)).toHaveText("Demo environment — fictional data");
  },
);

test("SL-DR-03 the environment label is on the login page and the incident list", async ({
  page,
}) => {
  await page.goto("/login");
  await expect(banner(page)).toHaveText(LABEL);
  await fillSignIn(page, "presenter");
  await expect(page.getByRole("heading", { name: "Incidents" })).toBeVisible();
  await expect(banner(page)).toHaveText(LABEL);
});

test("PM-M24-02 the banner and the customer are on every screen of P-28", async ({
  page,
}) => {
  await page.goto("/login");
  await expect(banner(page)).toHaveText(LABEL);
  await expect(page.getByText("Jazz", { exact: true })).toBeVisible();
  await fillSignIn(page, "presenter");
  await expect(page.getByRole("heading", { name: "Incidents" })).toBeVisible();
  await expect(banner(page)).toHaveText(LABEL);
  await expect(
    page.getByRole("banner").getByText("Jazz", { exact: true }),
  ).toBeVisible();
  await page.goto("/workflow");
  await expect(
    page.getByText("Could not load this page. Not found."),
  ).toBeVisible();
  await expect(banner(page)).toHaveText(LABEL);
  await expect(
    page.getByRole("banner").getByText("Jazz", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
  await fillSignIn(page, "noc-l1");
  await expect(page.getByRole("heading", { name: "No access" })).toBeVisible();
  await expect(banner(page)).toHaveText(LABEL);
  await expect(
    page.getByRole("banner").getByText("Jazz", { exact: true }),
  ).toBeVisible();
});

test("DC-U-02 Techfinity is shown on the login page and in the header", async ({
  page,
}) => {
  await page.goto("/login");
  await expect(page.getByText("Techfinity", { exact: true })).toBeVisible();
  await fillSignIn(page, "presenter");
  await expect(
    page.getByRole("banner").getByText("Techfinity", { exact: true }),
  ).toBeVisible();
});

test("DC-U-15 the tab titles", async ({ page }) => {
  await page.goto("/login");
  await expect(page).toHaveTitle("Sign in — Techfinity");
  await fillSignIn(page, "presenter");
  await expect(page.getByRole("heading", { name: "Incidents" })).toBeVisible();
  await expect(page).toHaveTitle("Incidents — Techfinity");
  await page.goto("/audit");
  await expect(
    page.getByText("Could not load this page. Not found."),
  ).toBeVisible();
  await expect(page).toHaveTitle("Techfinity");
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
  await fillSignIn(page, "noc-l1");
  await expect(page.getByRole("heading", { name: "No access" })).toBeVisible();
  await expect(page).toHaveTitle("No access — Techfinity");
});

test("DC-U-01 the banner stays at the top while the page scrolls", async ({
  page,
}) => {
  await page.setViewportSize({ width: 375, height: 300 });
  await signIn(page, "presenter");
  await expect(
    page.getByText("Alerts received in the last 10 minutes:", { exact: false }),
  ).toBeVisible();
  await page.mouse.wheel(0, 1000);
  await expect
    .poll(() => page.evaluate(() => window.scrollY))
    .toBeGreaterThan(0);
  const box = await banner(page).boundingBox();
  expect(box?.y).toBe(0);
  await expect(banner(page)).toBeInViewport();
});

test("SL-A4-01 presenter signs in", async ({ page }) => {
  await signIn(page, "presenter");
  await expect(
    page.getByRole("banner").getByText("Demo Presenter"),
  ).toBeVisible();
});

test("DC-S01-03 a wrong password is refused and the password field is emptied", async ({
  page,
}) => {
  await page.goto("/login");
  await fillSignIn(page, "presenter", `${password("presenter")}-wrong`);
  await expect(
    page.getByText("Username or password is incorrect."),
  ).toBeVisible();
  await expect(page.getByLabel("Password")).toHaveValue("");
  await expect(page.getByLabel("Username")).toHaveValue("presenter");
  await expect(page).toHaveURL(/\/login$/);
});

test("DC-S01-03 presenter signs in and sees the incident list", async ({
  page,
}) => {
  await page.goto("/login");
  await fillSignIn(page, "presenter");
  await expect(page).toHaveURL(/\/incidents$/);
  await expect(page.getByRole("heading", { name: "Incidents" })).toBeVisible();
});

test("DC-U-13 /login with a valid session opens next; a reload of /login shows no message", async ({
  page,
}) => {
  await signIn(page, "presenter");
  await page.goto("/login?next=%2Fincidents");
  await expect(page).toHaveURL(/\/incidents$/);
  await expect(page.getByRole("heading", { name: "Incidents" })).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByText("You are signed out.")).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page.getByLabel("Username")).toBeVisible();
  await expect(page.getByText("You are signed out.")).toHaveCount(0);
});

test("DC-U-13 a page opened without a session opens after sign-in", async ({
  page,
}) => {
  const address = "/incidents/00000000-0000-4000-8000-000000000000";
  await page.goto(address);
  await expect(page).toHaveURL(
    /\/login\?next=%2Fincidents%2F00000000-0000-4000-8000-000000000000$/,
  );
  await expect(
    page.getByText("Your session ended. Sign in again."),
  ).toHaveCount(0);
  await fillSignIn(page, "presenter");
  await expect(page).toHaveURL(new RegExp(`${address}$`));
  await expect(
    page.getByText("Could not load this page. Not found."),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
});

test("DC-S01-05 noc-l1 sees the no-access page", async ({ page }) => {
  await signIn(page, "noc-l1");
  await expect(page).toHaveURL(/\/no-access$/);
  const check = async () => {
    await expect(
      page.getByRole("heading", { name: "No access" }),
    ).toBeVisible();
    await expect(
      page.getByText(
        "There is nothing to show here for your account. Open the link in your Techfinity message to acknowledge or approve.",
      ),
    ).toBeVisible();
    const header = page.getByRole("banner");
    await expect(header).toHaveText(
      /^TechfinityJazzHira Baig \(NOC L1\)Sign out$/,
    );
    await expect(header.getByRole("navigation")).toHaveCount(0);
    await expect(header.getByText(/Times in UTC/)).toHaveCount(0);
  };
  await check();
  await page.goto("/incidents");
  await expect(page).toHaveURL(/\/no-access$/);
  await check();
});

test("DC-U-05 a user without view never sees the incident list", async ({
  page,
}) => {
  await signIn(page, "noc-l1");
  await page.goto("/incidents");
  await expect(page).toHaveURL(/\/no-access$/);
  await expect(page.getByRole("heading", { name: "Incidents" })).toHaveCount(0);
  await expect(
    page.getByText("Alerts received in the last 10 minutes", { exact: false }),
  ).toHaveCount(0);
  await expect(page.getByRole("button")).toHaveCount(1);
  await expect(page.getByRole("button")).toHaveText("Sign out");
  await page.goto("/");
  await expect(page).toHaveURL(/\/no-access$/);
});

test("DC-S01-04 sign out shows You are signed out.", async ({ page }) => {
  await signIn(page, "presenter");
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByText("You are signed out.")).toBeVisible();
  await page.goto("/incidents");
  await expect(page).toHaveURL(/\/login\?next=%2Fincidents$/);
});

test("AR-B-10 a host with no alias shows the error state", async ({ page }) => {
  await page.goto("http://vigil-app:8080/login");
  await expect(
    page.getByText("Could not load this page. This address is not set up."),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
  await expect(page.getByLabel("Username")).toHaveCount(0);
  await expect(banner(page)).toHaveCount(0);
  await expect(page).toHaveTitle("");
  await page.goto("http://vigil-app:8080/incidents");
  await expect(
    page.getByText("Could not load this page. This address is not set up."),
  ).toBeVisible();
});

test("AR-W-01 / opens the incident list", async ({ page }) => {
  await signIn(page, "presenter");
  await page.goto("/");
  await expect(page).toHaveURL(/\/incidents$/);
  await expect(page.getByRole("heading", { name: "Incidents" })).toBeVisible();
});
