// The reads of the mobile pages (P-30 spec Section 6.2 item 5; AR-I-04, AR-I-05, AR-W-02): the Acknowledge page polls
// AR-I-32 every 1 s; the Approval page polls AR-I-34 every 1 s while the request is pending.
import { queryOptions, type Query } from "@tanstack/react-query";
import { apiGet } from "../api/client";
import type { ApprovalPage } from "../api/types";
import { polling } from "../live/polling";

export const POLL_MS = 1000;

export const acknowledgeQuery = (incidentId: string) =>
  queryOptions({
    queryKey: ["acknowledgement", incidentId],
    queryFn: ({ signal }) =>
      apiGet("/api/v1/incidents/{incident_id}/acknowledgement", {
        params: { incident_id: incidentId },
        signal,
      }),
    ...polling(POLL_MS),
  });

/** 1 s while the latest answer's state is `pending`, else no polling (AR-I-05). */
export function approvalInterval(
  query: Pick<Query<ApprovalPage>, "state">,
): number | false {
  return query.state.data?.state === "pending" ? POLL_MS : false;
}

export const approvalQuery = (requestId: string) =>
  queryOptions({
    queryKey: ["approval-request", requestId],
    queryFn: ({ signal }) =>
      apiGet("/api/v1/approval-requests/{request_id}", {
        params: { request_id: requestId },
        signal,
      }),
    refetchInterval: approvalInterval,
    refetchIntervalInBackground: true,
  });

/** The Acknowledge page's topics besides `tenant` (AR-I-04 row DC-S04). */
export function acknowledgeTopics(incidentId: string): string[] {
  return [`incident:${incidentId}`];
}

/** The Approval page's topics besides `tenant` (AR-I-04 row DC-S05): its incident once AR-I-34 has answered. */
export function approvalTopics(
  requestId: string,
  incidentId: string | undefined,
): string[] {
  return [
    `approval:${requestId}`,
    ...(incidentId ? [`incident:${incidentId}`] : []),
  ];
}
