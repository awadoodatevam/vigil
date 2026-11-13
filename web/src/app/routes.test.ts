import { matchRoutes } from "react-router";
import { expect, it } from "vitest";
import type { Answer } from "../api/client";
import { apiGet } from "../api/client";
import type { components } from "../api/generated/vigil-api";
import { ROUTE_TABLE, routes } from "./routes";

const BRANDING = {
  product_display_name: "Techfinity",
  customer_name: "Jazz",
  has_logo: false,
  environment_label: "Demo environment — fictional data",
};

function screenOf(path: string): string | undefined {
  const matches = matchRoutes(routes(BRANDING), path);
  return matches?.[matches.length - 1].route.id;
}

it("AR-W-01 the route table of P-28", () => {
  expect(ROUTE_TABLE.map((route) => route.path)).toEqual([
    "/login",
    "/no-access",
    "/incidents",
    "/",
    "*",
  ]);
  expect(screenOf("/login")).toBe("login");
  expect(screenOf("/no-access")).toBe("no-access");
  expect(screenOf("/incidents")).toBe("incidents");
  expect(screenOf("/")).toBe("to-incidents");
});

it("AR-W-01 every other path, the paths not built yet included, shows the not-found state", () => {
  for (const path of [
    "/incidents/00000000-0000-4000-8000-000000000000",
    "/m/acknowledge/00000000-0000-4000-8000-000000000000",
    "/m/approve/00000000-0000-4000-8000-000000000000",
    "/workflow",
    "/setup",
    "/routing",
    "/ai",
    "/dashboard",
    "/audit",
    "/anything/else",
  ]) {
    expect(screenOf(path), path).toBe("not-found");
  }
});

// A type-level test: `tsc --noEmit` (npm run typecheck) fails when the client's types are not the generated ones.
type Strip = Answer<"/api/v1/alerts/strip", "get">;
type Page = Answer<"/api/v1/incidents", "get">;
const stripIsGenerated: Strip extends components["schemas"]["AlertStrip"]
  ? true
  : false = true;
const pageIsGenerated: Page extends components["schemas"]["IncidentPage"]
  ? true
  : false = true;
const logoutHasNoBody: Answer<"/api/v1/session", "delete"> extends undefined
  ? true
  : false = true;

export function contractChecks() {
  // @ts-expect-error a path that the contract does not have
  void apiGet("/api/v1/not-in-the-contract");
  // @ts-expect-error a query parameter that the operation does not take
  void apiGet("/api/v1/incidents", { query: { page: "2" } });
}

it("BP-R-13 the API client uses the generated types", () => {
  expect([stripIsGenerated, pageIsGenerated, logoutHasNoBody]).toEqual([
    true,
    true,
    true,
  ]);
});
