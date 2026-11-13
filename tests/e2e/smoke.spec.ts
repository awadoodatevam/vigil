// The smoke suite of the local installation (P-26 spec Section 7, CTX-D-081 (1)): the page, a login with a seed
// password, the seed import and the branding. P-28 adds the login page, the banner and the incident list (login.spec.ts,
// incidents.spec.ts).
import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";

const SECRETS = "/run/secrets/vigil";
const STATE = "/run/state";

test(
  "BP-R-24 Vigil answers at demo.vigil.test:8080 inside the compose network",
  { tag: "@smoke" },
  async ({ page }) => {
    const response = await page.goto("/");
    expect(response?.status()).toBe(200);
    expect(await response?.text()).toContain('<div id="root">');
  },
);

test(
  "BP-R-25 login over plain HTTP sets a session cookie without Secure",
  { tag: "@smoke" },
  async ({ request }) => {
    const password = readFileSync(`${SECRETS}/seed-password-presenter`, "utf8");
    const login = await request.post("/api/v1/session", {
      data: { username: "presenter", password },
    });
    expect(login.status()).toBe(200);
    expect((await login.json()).display_name).toBe("Demo Presenter");
    const cookies = login
      .headersArray()
      .filter((header) => header.name.toLowerCase() === "set-cookie")
      .map((header) => header.value);
    const session = cookies.find((cookie) =>
      cookie.startsWith("vigil_session="),
    );
    expect(session).toBeDefined();
    expect(
      session?.split(";").map((part) => part.trim().toLowerCase()),
    ).not.toContain("secure");
    const current = await request.get("/api/v1/session", {
      headers: { Cookie: session!.split(";")[0] },
    });
    expect(current.status()).toBe(200);
    const user = await current.json();
    expect(user.username).toBe("presenter");
    expect(user.display_name).toBe("Demo Presenter");
  },
);

test(
  "AR-Q-10 the bundle built by bundle.py imported with all 17 parts succeeded",
  { tag: "@smoke" },
  async () => {
    const answer = JSON.parse(
      readFileSync(`${STATE}/seed-import.json`, "utf8"),
    );
    expect(answer.state).toBe("succeeded");
    expect(answer.parts).toHaveLength(17);
    for (const part of answer.parts) {
      expect(part.outcome, part.module).toBe("succeeded");
    }
  },
);

test(
  "AR-Q-10 the imported tenant answers its branding",
  { tag: "@smoke" },
  async ({ request }) => {
    const response = await request.get("/api/v1/public/branding");
    expect(response.status()).toBe(200);
    const branding = await response.json();
    // product_display_name and customer_name of docs/31-demo-data/01-tenant-identity.yaml.
    expect(branding.product_display_name).toBe("Techfinity");
    expect(branding.customer_name).toBe("Jazz");
  },
);
