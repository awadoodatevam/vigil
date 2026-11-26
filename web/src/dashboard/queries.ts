// The read of the dashboard (AR-I-80; DC-S10-04, DC-U-08, CTX-D-097 (2)): when the screen opens and on "Refresh" or
// "Try again" only. No polling and no topic re-reads it; no value of an earlier visit is kept.
import { queryOptions } from "@tanstack/react-query";
import { apiGet } from "../api/client";
import type { Dashboard } from "../api/types";

export type FetchDashboard = (signal: AbortSignal) => Promise<Dashboard>;

const read: FetchDashboard = (signal) =>
  apiGet("/api/v1/dashboard", { signal });

export function dashboardQuery(fetch: FetchDashboard = read) {
  return queryOptions({
    queryKey: ["dashboard"],
    queryFn: ({ signal }) => fetch(signal),
    refetchOnMount: "always",
    gcTime: 0,
  });
}

/** The dashboard's live topics besides the layout's `tenant` (AR-I-04): none. */
export const TOPICS: readonly string[] = [];
