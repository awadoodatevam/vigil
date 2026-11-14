import { matchRoutes } from "react-router";
import { expect, it } from "vitest";
import type { Answer } from "../api/client";
import { apiGet } from "../api/client";
import type { components } from "../api/generated/vigil-api";
import { LAZY_SCREENS, ROUTE_TABLE, routes } from "./routes";

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
    "/m/acknowledge/:incidentId",
    "/m/approve/:requestId",
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

const ID = "00000000-0000-4000-8000-000000000000";

it("AR-W-01 the mobile routes", () => {
  expect(ROUTE_TABLE).toContainEqual({
    path: "/m/acknowledge/:incidentId",
    screen: "acknowledge",
  });
  expect(ROUTE_TABLE).toContainEqual({
    path: "/m/approve/:requestId",
    screen: "approve",
  });
  const paths = ROUTE_TABLE.map((route) => route.path);
  expect(paths.indexOf("/m/acknowledge/:incidentId")).toBe(
    paths.indexOf("/incidents/:incidentId") + 1,
  );
  expect(paths.indexOf("/m/approve/:requestId")).toBe(paths.indexOf("/") - 1);
  expect(screenOf("/m/acknowledge")).toBe("not-found");
  expect(screenOf("/m/approve")).toBe("not-found");
  expect(screenOf(`/m/acknowledge/${ID}/more`)).toBe("not-found");
});

it("SL-A4-04 /m/acknowledge/{id} is the Acknowledge page", () => {
  expect(screenOf(`/m/acknowledge/${ID}`)).toBe("acknowledge");
});

it("SL-A4-05 /m/approve/{id} is the Approval page", () => {
  expect(screenOf(`/m/approve/${ID}`)).toBe("approve");
});

it("AR-I-101 the Acknowledge address takes an incident id", () => {
  const matches = matchRoutes(routes(BRANDING), `/m/acknowledge/${ID}`);
  expect(matches?.[0].params).toEqual({ incidentId: ID });
});

it("AR-I-102 the Approval address takes a request id", () => {
  const matches = matchRoutes(routes(BRANDING), `/m/approve/${ID}`);
  expect(matches?.[0].params).toEqual({ requestId: ID });
});

it("AR-W-07 the workspace and the mobile pages are the lazy screens", () => {
  expect([...LAZY_SCREENS]).toEqual(["workspace", "acknowledge", "approve"]);
  for (const route of routes(BRANDING)) {
    const lazy = (LAZY_SCREENS as readonly string[]).includes(route.id!);
    const element = route.element as {
      props?: { children?: { type?: { $$typeof?: symbol } } };
    };
    const child = element.props?.children?.type?.$$typeof;
    expect(child === Symbol.for("react.lazy"), route.id).toBe(lazy);
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
