// Helpers of the Playwright tests of P-28: the seed passwords of the e2e secrets folder and the sign-in; P-44: the
// screenshots of the screens for the review.
import { mkdirSync, readFileSync } from "node:fs";
import { expect, type Page } from "@playwright/test";

export const SECRETS = "/run/secrets/vigil";
export const LABEL = "Demo environment — fictional data";

export function password(username: string): string {
  return readFileSync(`${SECRETS}/seed-password-${username}`, "utf8");
}

/** Fills the login form of the page shown and signs in. */
export async function fillSignIn(
  page: Page,
  username: string,
  secret = password(username),
): Promise<void> {
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(secret);
  await page.getByRole("button", { name: "Sign in" }).click();
}

/** Opens /login and signs in; waits until the login screen is left. */
export async function signIn(page: Page, username: string): Promise<void> {
  await page.goto("/login");
  await fillSignIn(page, username);
  await expect(page).not.toHaveURL(/\/login/);
}

export function banner(page: Page) {
  return page.getByTestId("environment-banner");
}

/** The run folder's screenshots (P-44 spec Section 6.9): on the host {VIGIL_LOCAL_HOME or ~/.vigil-local}/{project}/e2e/screens/. */
export const SCREENS = "/e2e/run/screens";

/** Writes a full-page screenshot of the page shown as {name}.png into the run folder. */
export async function screenshot(page: Page, name: string): Promise<void> {
  mkdirSync(SCREENS, { recursive: true });
  await page.screenshot({ path: `${SCREENS}/${name}.png`, fullPage: true });
}
