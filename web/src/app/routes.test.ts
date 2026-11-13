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
    "/incidents/:incidentId",
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

it("AR-W-01 /incidents/:incidentId is the workspace", () => {
  expect(ROUTE_TABLE).toContainEqual({
    path: "/incidents/:incidentId",
    screen: "workspace",
  });
  expect(ROUTE_TABLE.at(-1)).toEqual({ path: "*", screen: "not-found" });
});

it("AR-I-100 the incident address /incidents/{incident id} opens the workspace screen", () => {
  const id = "0192f1a2-7b3c-7d4e-8f50-6a7b8c9d0e1f";
  expect(screenOf(`/incidents/${id}`)).toBe("workspace");
  const matches = matchRoutes(routes(BRANDING), `/incidents/${id}`);
  expect(matches?.[0].params.incidentId).toBe(id);
  expect(screenOf(`/incidents/${id}/more`)).toBe("not-found");
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
