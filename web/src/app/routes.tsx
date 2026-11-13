// The routes of P-28, the incident workspace of P-29 and the mobile pages of P-30 (AR-W-01, complete list): every
// other path, the paths of AR-W-01 not built yet included, shows the not-found state (CTX-D-085 (1)). The workspace and
// the mobile pages load as lazy screens, so a mobile page loads only the code it needs (AR-W-07, CTX-D-091 (3)).
import { lazy, Suspense, type ReactNode } from "react";
import { Navigate, type RouteObject } from "react-router";
import type { TenantSettings } from "../api/types";
import { IncidentsPage } from "../incidents/IncidentsPage";
import { LoginPage } from "../login/LoginPage";
import { NoAccessPage } from "../login/NoAccessPage";
import { Loading } from "../states/states";
import { NotFoundPage } from "./NotFoundPage";

export type Screen =
  | "login"
  | "no-access"
  | "incidents"
  | "workspace"
  | "acknowledge"
  | "approve"
  | "to-incidents"
  | "not-found";

/** The route table: path and screen. */
export const ROUTE_TABLE: readonly { path: string; screen: Screen }[] = [
  { path: "/login", screen: "login" },
  { path: "/no-access", screen: "no-access" },
  { path: "/incidents", screen: "incidents" },
  { path: "/incidents/:incidentId", screen: "workspace" },
  { path: "/m/acknowledge/:incidentId", screen: "acknowledge" },
  { path: "/m/approve/:requestId", screen: "approve" },
  { path: "/", screen: "to-incidents" },
  { path: "*", screen: "not-found" },
];

/** The screens loaded with React.lazy (complete list). */
export const LAZY_SCREENS = ["workspace", "acknowledge", "approve"] as const;

const WorkspacePage = lazy(() =>
  import("../workspace/WorkspacePage").then((m) => ({
    default: m.WorkspacePage,
  })),
);
const AcknowledgePage = lazy(() =>
  import("../mobile/AcknowledgePage").then((m) => ({
    default: m.AcknowledgePage,
  })),
);
const ApprovalPage = lazy(() =>
  import("../mobile/ApprovalPage").then((m) => ({ default: m.ApprovalPage })),
);

function Lazy({ children }: { children: ReactNode }) {
  return <Suspense fallback={<Loading />}>{children}</Suspense>;
}

function element(screen: Screen, branding: TenantSettings) {
  switch (screen) {
    case "login":
      return <LoginPage branding={branding} />;
    case "no-access":
      return <NoAccessPage />;
    case "incidents":
      return <IncidentsPage />;
    case "workspace":
      return (
        <Lazy>
          <WorkspacePage />
        </Lazy>
      );
    case "acknowledge":
      return (
        <Lazy>
          <AcknowledgePage />
        </Lazy>
      );
    case "approve":
      return (
        <Lazy>
          <ApprovalPage />
        </Lazy>
      );
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
