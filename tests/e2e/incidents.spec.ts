// DC-S02 Incident list on a tenant that has had no storm (P-28 spec Section 7): right after deploy/local.sh up or reset.
import { expect, test } from "@playwright/test";
import { signIn } from "./helpers";

test.use({ timezoneId: "Asia/Karachi" });

test(
  "DC-S02-01 the incident list shows its heading, the header and the strip",
  { tag: "@smoke" },
  async ({ page }) => {
    await signIn(page, "presenter");
    await expect(
      page.getByRole("heading", { name: "Incidents" }),
    ).toBeVisible();
    const header = page.getByRole("banner");
    await expect(header).toHaveText(
      /^TechfinityJazzIncidentsTimes in UTC\+05:00Demo PresenterSign out$/,
    );
    const current = header.getByRole("link", { name: "Incidents" });
    await expect(current).toHaveAttribute("aria-current", "page");
    await expect(page.getByTestId("alerts-strip")).toBeVisible();
  },
);

test("PM-M24-01 the login and incident list screens exist", async ({
  page,
}) => {
  await page.goto("/login");
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await signIn(page, "presenter");
  await expect(page).toHaveURL(/\/incidents$/);
  await expect(page.getByRole("heading", { name: "Incidents" })).toBeVisible();
});

test("DC-S02-02 the strip of a tenant without alerts", async ({ page }) => {
  await signIn(page, "presenter");
  const strip = page.getByTestId("alerts-strip");
  await expect(
    strip.getByText("Alerts received in the last 10 minutes: 0", {
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    strip.getByText("No alerts in the last 10 minutes.", { exact: true }),
  ).toBeVisible();
  await expect(
    strip.getByText("Monitoring: 2 of 2 sources healthy", { exact: true }),
  ).toBeVisible();
  await expect(
    strip.getByText("Grouping in progress", { exact: false }),
  ).toHaveCount(0);
  await expect(
    page.getByText(
      "No incidents yet. Techfinity is watching 2 monitoring sources.",
    ),
  ).toBeVisible();
});

test("SL-A4-02 the incident list opens after sign-in", async ({ page }) => {
  await signIn(page, "presenter");
  await expect(page).toHaveURL(/\/incidents$/);
  await expect(page.getByRole("heading", { name: "Incidents" })).toBeVisible();
  await expect(page.getByTestId("alerts-strip")).toBeVisible();
});
