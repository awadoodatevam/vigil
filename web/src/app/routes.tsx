// The routes of P-28 (AR-W-01, P-28 spec Section 6.3, complete list): every other path, the paths of AR-W-01 not
// built yet included, shows the not-found state (CTX-D-085 (1)).
import { Navigate, type RouteObject } from "react-router";
import type { TenantSettings } from "../api/types";
import { IncidentsPage } from "../incidents/IncidentsPage";
import { LoginPage } from "../login/LoginPage";
import { NoAccessPage } from "../login/NoAccessPage";
import { NotFoundPage } from "./NotFoundPage";

export type Screen =
  "login" | "no-access" | "incidents" | "to-incidents" | "not-found";

/** The route table: path and screen. */
export const ROUTE_TABLE: readonly { path: string; screen: Screen }[] = [
  { path: "/login", screen: "login" },
  { path: "/no-access", screen: "no-access" },
  { path: "/incidents", screen: "incidents" },
  { path: "/", screen: "to-incidents" },
  { path: "*", screen: "not-found" },
];

function element(screen: Screen, branding: TenantSettings) {
  switch (screen) {
    case "login":
      return <LoginPage branding={branding} />;
    case "no-access":
      return <NoAccessPage />;
    case "incidents":
      return <IncidentsPage />;
    case "to-incidents":
      return <Navigate to="/incidents" replace />;
    case "not-found":
      return <NotFoundPage />;
  }
}

export function routes(branding: TenantSettings): RouteObject[] {
  return ROUTE_TABLE.map(({ path, screen }) => ({
    path,
    element: element(screen, branding),
    id: screen,
  }));
}
