// The reads of the incident workspace (P-29 spec Section 6.2 item 3, complete list): one query per operation, keyed so
// that a live notice re-reads its parts by prefix; the recovery part is polled every 1 s while it observes (AR-I-05).
import { queryOptions } from "@tanstack/react-query";
import { apiGet } from "../api/client";
import type { RecoverySection } from "../api/types";

export const RECOVERY_INTERVAL_MS = 1000;

const params = (id: string) => ({ params: { incident_id: id } });

export const headerQuery = (id: string) =>
  queryOptions({
    queryKey: ["incident", id],
    queryFn: ({ signal }) =>
      apiGet("/api/v1/incidents/{incident_id}", { ...params(id), signal }),
  });

export const alertsQuery = (id: string) =>
  queryOptions({
    queryKey: ["incident-alerts", id],
    queryFn: ({ signal }) =>
      apiGet("/api/v1/incidents/{incident_id}/alerts", {
        ...params(id),
        signal,
      }),
  });

export const evidenceQuery = (id: string) =>
  queryOptions({
    queryKey: ["incident", id, "evidence"],
    queryFn: ({ signal }) =>
      apiGet("/api/v1/incidents/{incident_id}/evidence", {
        ...params(id),
        signal,
      }),
  });

export const investigationQuery = (id: string) =>
  queryOptions({
    queryKey: ["incident", id, "investigation"],
    queryFn: ({ signal }) =>
      apiGet("/api/v1/incidents/{incident_id}/investigation", {
        ...params(id),
        signal,
      }),
  });

export const responseQuery = (id: string) =>
  queryOptions({
    queryKey: ["incident", id, "response"],
    queryFn: ({ signal }) =>
      apiGet("/api/v1/incidents/{incident_id}/response", {
        ...params(id),
        signal,
      }),
  });

/** 1000 ms while any verification of the last answer is observing, else no polling. */
export function recoveryInterval(
  data: RecoverySection | undefined,
): number | false {
  return data?.verifications.some((v) => v.state === "observing")
    ? RECOVERY_INTERVAL_MS
    : false;
}

export const recoveryQuery = (id: string) =>
  queryOptions({
    queryKey: ["incident", id, "recovery"],
    queryFn: ({ signal }) =>
      apiGet("/api/v1/incidents/{incident_id}/recovery", {
        ...params(id),
        signal,
      }),
    refetchInterval: (query) => recoveryInterval(query.state.data),
    refetchIntervalInBackground: true,
  });

export const runQuery = (id: string) =>
  queryOptions({
    queryKey: ["incident", id, "run"],
    queryFn: ({ signal }) =>
      apiGet("/api/v1/incidents/{incident_id}/run", { ...params(id), signal }),
  });

export const timelineQuery = (id: string) =>
  queryOptions({
    queryKey: ["incident", id, "timeline"],
    queryFn: ({ signal }) =>
      apiGet("/api/v1/incidents/{incident_id}/timeline", {
        ...params(id),
        signal,
      }),
  });

export const notifiedQuery = (id: string) =>
  queryOptions({
    queryKey: ["incident", id, "notified"],
    queryFn: ({ signal }) =>
      apiGet("/api/v1/incidents/{incident_id}/notified-teams", {
        ...params(id),
        signal,
      }),
  });
