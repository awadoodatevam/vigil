// Playwright configuration of the local installation's tests (P-26 spec Section 6.7), run in vigil/e2e:local by
// deploy/local.sh e2e with this folder at /e2e/tests and the run folder at /e2e/run.
import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  outputDir: "/e2e/run/results",
  reporter: [
    ["list"],
    ["html", { outputFolder: "/e2e/run/report", open: "never" }],
  ],
  use: {
    baseURL: process.env.VIGIL_BASE_URL ?? "http://demo.vigil.test:8080",
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
