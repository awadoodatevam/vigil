// DC-S03 Incident workspace on a tenant without a storm (P-29 spec Section 7): an address that names no incident.
import { expect, test } from "@playwright/test";
import { signIn } from "./helpers";

test("DC-S03-01 an unknown incident shows Could not load this page. Not found.", async ({
  page,
}) => {
  await signIn(page, "presenter");
  await page.goto("/incidents/00000000-0000-4000-8000-000000000000");
  await expect(
    page.getByText("Could not load this page. Not found.", { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
  await expect(page).toHaveTitle("Techfinity");
  await expect(page.locator("main section")).toHaveCount(0);
});
