import type { Query } from "@tanstack/react-query";
import { expect, it } from "vitest";
import type { ApprovalPage } from "../api/types";
import { approvalPage } from "../test/mobile";
import {
  acknowledgeQuery,
  approvalInterval,
  approvalQuery,
  POLL_MS,
} from "./queries";

const ID = "00000000-0000-4000-8000-000000000002";

it("AR-I-05 the Acknowledge page polls every 1 s; the Approval page while pending", () => {
  expect(POLL_MS).toBe(1000);
  const acknowledge = acknowledgeQuery(ID);
  expect(acknowledge.queryKey).toEqual(["acknowledgement", ID]);
  expect(acknowledge.refetchInterval).toBe(1000);
  expect(acknowledge.refetchIntervalInBackground).toBe(true);

  const approval = approvalQuery(ID);
  expect(approval.queryKey).toEqual(["approval-request", ID]);
  expect(approval.refetchIntervalInBackground).toBe(true);
  expect(approval.refetchInterval).toBe(approvalInterval);

  const read = (data?: ApprovalPage) =>
    ({ state: { data } }) as unknown as Pick<Query<ApprovalPage>, "state">;
  expect(approvalInterval(read())).toBe(false);
  for (const state of [
    "approved",
    "rejected",
    "expired",
    "preauthorized",
    "blocked",
    "refused",
    "cancelled",
  ] as const) {
    expect(approvalInterval(read(approvalPage({ state }))), state).toBe(false);
  }
  expect(approvalInterval(read(approvalPage({ state: "pending" })))).toBe(1000);
});
