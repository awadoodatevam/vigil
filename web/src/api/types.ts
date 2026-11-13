// Names for the schemas of the generated contract types (BP-R-13).
import type { components } from "./generated/vigil-api";

type Schemas = components["schemas"];

export type TenantSettings = Schemas["TenantSettings"];
export type User = Schemas["User"];
export type Permission = User["permissions"][number];
export type AlertStrip = Schemas["AlertStrip"];
export type IncidentPage = Schemas["IncidentPage"];
export type IncidentRow = Schemas["IncidentRow"];
export type ErrorCode = Schemas["ErrorContent"]["code"];
