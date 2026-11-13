// The reads every screen shares: branding (AR-I-10), the current user (AR-I-13) and the tenant settings (AR-I-15).
import { queryOptions } from "@tanstack/react-query";
import { apiGet } from "../api/client";
import type { TenantSettings, User } from "../api/types";

export const brandingQuery = queryOptions({
  queryKey: ["branding"],
  queryFn: ({ signal }) => apiGet("/api/v1/public/branding", { signal }),
});

export const sessionQuery = queryOptions({
  queryKey: ["session"],
  queryFn: ({ signal }) => apiGet("/api/v1/session", { signal }),
});

/** The login screen's own read of AR-I-13, never answered from an earlier read. */
export const loginSessionQuery = queryOptions({
  queryKey: ["session", "login"],
  queryFn: ({ signal }) => apiGet("/api/v1/session", { signal }),
  gcTime: 0,
});

export const tenantQuery = queryOptions({
  queryKey: ["tenant"],
  queryFn: ({ signal }) => apiGet("/api/v1/tenant", { signal }),
});

export function hasView(user: User): boolean {
  return user.permissions.includes("view");
}

/** The default page after sign-in (DC-S01-03). */
export function defaultPage(user: User): string {
  return hasView(user) ? "/incidents" : "/no-access";
}

export type { TenantSettings, User };
