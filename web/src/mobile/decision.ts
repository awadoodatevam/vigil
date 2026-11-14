// The two actions of the mobile pages (DC-S04-04, DC-S05-04): the acknowledgement (AR-I-33) and the decision (AR-I-35),
// which carries exactly the decision and the snapshot hash of the answer the page shows (DM-E-59).
import { apiPost, type ClientDeps } from "../api/client";
import type { DecisionRequest } from "../api/types";

export type DecisionKind = DecisionRequest["decision"];

export function sendAcknowledgement(incidentId: string, deps?: ClientDeps) {
  return apiPost("/api/v1/incidents/{incident_id}/acknowledgements", {
    params: { incident_id: incidentId },
    deps,
  });
}

export function sendDecision(
  requestId: string,
  decision: DecisionKind,
  snapshotHashShown: string,
  deps?: ClientDeps,
) {
  const body: DecisionRequest = {
    decision,
    snapshot_hash_shown: snapshotHashShown,
  };
  return apiPost("/api/v1/approval-requests/{request_id}/decisions", {
    params: { request_id: requestId },
    body,
    deps,
  });
}
